using NAudio.CoreAudioApi;
using NAudio.Wave;
using SaveGaze.Core;

namespace SaveGaze.Desktop;

/// <summary>
/// Captures Windows system playback audio via WASAPI Loopback (zero microphone, zero Screen Cast prompt),
/// resamples to 16kHz mono float32, gates strictly on non-silent audio RMS, and scores 0.975s sliding windows with YAMNet.
/// </summary>
public sealed class WasapiAudioMonitor : IDisposable
{
    private const int TargetSampleRate = 16_000;
    private const int WindowSamples = 15_600; // 0.975s
    private const int HopSamples = 12_800;    // ~0.80s hop

    private readonly AudioIntimateOnnxDetector _detector;
    private readonly float[] _ring = new float[WindowSamples];
    private int _ringFill;
    private int _sinceLastHop;
    private double _resamplePhase;

    private WasapiLoopbackCapture? _capture;
    private readonly object _stateLock = new();
    private volatile bool _running;

    public AudioIntimateResult LatestResult { get; private set; } = new(0f, "", 0f, false);
    public long LastScoredAtMs { get; private set; }
    public bool IsCapturing => _running;
    public string StatusMessage { get; private set; } = "Idle";

    public event Action<AudioIntimateResult>? ResultScored;

    public WasapiAudioMonitor(AudioIntimateOnnxDetector detector)
    {
        _detector = detector;
    }

    public bool Start()
    {
        lock (_stateLock)
        {
            if (_running) return true;
            try
            {
                using var enumerator = new MMDeviceEnumerator();
                if (!enumerator.HasDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia))
                {
                    StatusMessage = "No playback audio device";
                    return false;
                }

                _capture = new WasapiLoopbackCapture();
                _capture.DataAvailable += OnDataAvailable;
                _capture.RecordingStopped += (_, e) =>
                {
                    _running = false;
                    StatusMessage = e.Exception != null ? $"Stopped: {e.Exception.Message}" : "Stopped";
                };
                _ringFill = 0;
                _sinceLastHop = 0;
                _resamplePhase = 0;
                _capture.StartRecording();
                _running = true;
                StatusMessage = "WASAPI Loopback Active";
                return true;
            }
            catch (Exception ex)
            {
                StatusMessage = $"Audio unavailable: {ex.Message}";
                StopInternal();
                return false;
            }
        }
    }

    private void OnDataAvailable(object? sender, WaveInEventArgs e)
    {
        if (!_running || e.BytesRecorded <= 0 || _capture == null) return;
        var fmt = _capture.WaveFormat;
        int channels = Math.Max(1, fmt.Channels);
        int srcRate = Math.Max(8000, fmt.SampleRate);
        int bytesPerSample = fmt.BitsPerSample / 8;
        int frameBytes = bytesPerSample * channels;
        if (frameBytes <= 0) return;

        int srcFrames = e.BytesRecorded / frameBytes;
        if (srcFrames == 0) return;

        // Convert multi-channel WASAPI buffer to mono float[-1..1]
        var mono = new float[srcFrames];
        bool isFloat = fmt.Encoding == WaveFormatEncoding.IeeeFloat ||
            (fmt.Encoding == WaveFormatEncoding.Extensible && bytesPerSample == 4);

        for (int i = 0; i < srcFrames; i++)
        {
            double sum = 0;
            int frameOffset = i * frameBytes;
            for (int c = 0; c < channels; c++)
            {
                int off = frameOffset + c * bytesPerSample;
                float sample = isFloat && bytesPerSample == 4
                    ? BitConverter.ToSingle(e.Buffer, off)
                    : bytesPerSample == 2
                        ? BitConverter.ToInt16(e.Buffer, off) / 32768f
                        : 0f;
                sum += sample;
            }
            mono[i] = (float)(sum / channels);
        }

        // Resample to 16kHz mono via linear interpolation
        double step = (double)srcRate / TargetSampleRate;
        var resampled = new List<float>((int)(srcFrames / step) + 4);
        while (_resamplePhase < srcFrames - 1)
        {
            int idx = (int)_resamplePhase;
            float frac = (float)(_resamplePhase - idx);
            float v = mono[idx] + (mono[idx + 1] - mono[idx]) * frac;
            resampled.Add(Math.Clamp(v, -1f, 1f));
            _resamplePhase += step;
        }
        _resamplePhase -= srcFrames;
        if (_resamplePhase < 0) _resamplePhase = 0;

        if (resampled.Count == 0) return;

        foreach (float s in resampled)
        {
            if (_ringFill < WindowSamples)
            {
                _ring[_ringFill++] = s;
            }
            else
            {
                Array.Copy(_ring, 1, _ring, 0, WindowSamples - 1);
                _ring[WindowSamples - 1] = s;
            }
            _sinceLastHop++;
        }

        if (_ringFill >= WindowSamples && _sinceLastHop >= HopSamples)
        {
            _sinceLastHop = 0;
            double sumSq = 0;
            for (int i = 0; i < WindowSamples; i++)
            {
                double v = _ring[i];
                sumSq += v * v;
            }
            float rms = (float)Math.Sqrt(sumSq / WindowSamples);

            AudioIntimateResult result;
            if (rms < AudioIntimateRules.SilenceRmsGate)
            {
                result = new AudioIntimateResult(0f, "", rms, false);
            }
            else
            {
                var snapshot = new float[WindowSamples];
                Array.Copy(_ring, snapshot, WindowSamples);
                result = _detector.ScoreWaveform(snapshot, rms);
            }

            LatestResult = result;
            LastScoredAtMs = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
            ResultScored?.Invoke(result);
        }
    }

    public void Stop()
    {
        lock (_stateLock) StopInternal();
    }

    public void ResetWindow()
    {
        lock (_stateLock)
        {
            _ringFill = 0;
            _sinceLastHop = 0;
            LastScoredAtMs = 0L;
            LatestResult = new AudioIntimateResult(0f, "", 0f, false);
        }
    }

    private void StopInternal()
    {
        _running = false;
        try { _capture?.StopRecording(); } catch { }
        try { _capture?.Dispose(); } catch { }
        _capture = null;
        LatestResult = new AudioIntimateResult(0f, "", 0f, false);
        StatusMessage = "Stopped";
    }

    public void Dispose() => Stop();
}
