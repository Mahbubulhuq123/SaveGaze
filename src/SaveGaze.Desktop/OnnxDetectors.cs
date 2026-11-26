using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using Microsoft.ML.OnnxRuntime;
using Microsoft.ML.OnnxRuntime.Tensors;
using SaveGaze.Core;

namespace SaveGaze.Desktop;

public static class ModelPathLocator
{
    public static string Resolve(string fileName)
    {
        string[] candidates =
        [
            Path.Combine(AppContext.BaseDirectory, "models", fileName),
            Path.Combine(AppContext.BaseDirectory, fileName),
            Path.GetFullPath(Path.Combine(AppContext.BaseDirectory, "..", "..", "..", "..", "..", "models", fileName)),
            Path.Combine(@"K:\SaveGaze-PC\models", fileName)
        ];
        foreach (string p in candidates)
        {
            if (File.Exists(p)) return p;
        }
        throw new FileNotFoundException($"Required ONNX model not found: {fileName}", fileName);
    }

    public static SessionOptions CreateDefaultOptions(int threads = 2)
    {
        return new SessionOptions
        {
            IntraOpNumThreads = threads,
            InterOpNumThreads = 1,
            GraphOptimizationLevel = GraphOptimizationLevel.ORT_ENABLE_ALL
        };
    }

    public static unsafe void FillChw01(Bitmap src, float[] dst, int targetW, int targetH)
    {
        using Bitmap? owned = (src.Width == targetW && src.Height == targetH)
            ? null
            : ResizeBitmap(src, targetW, targetH);
        Bitmap bmp = owned ?? src;
        var rect = new Rectangle(0, 0, targetW, targetH);
        var data = bmp.LockBits(rect, ImageLockMode.ReadOnly, PixelFormat.Format32bppArgb);
        try
        {
            int channelSize = targetW * targetH;
            byte* basePtr = (byte*)data.Scan0;
            int stride = data.Stride;
            const float inv255 = 1f / 255f;

            for (int y = 0; y < targetH; y++)
            {
                byte* row = basePtr + y * stride;
                int rowOff = y * targetW;
                for (int x = 0; x < targetW; x++)
                {
                    int idx = rowOff + x;
                    int pxOff = x * 4;
                    dst[idx] = row[pxOff + 2] * inv255;
                    dst[idx + channelSize] = row[pxOff + 1] * inv255;
                    dst[idx + 2 * channelSize] = row[pxOff] * inv255;
                }
            }
        }
        finally
        {
            bmp.UnlockBits(data);
        }
    }

    public static unsafe (float ScaleX, float ScaleY) FillTopLeftLetterboxChw01(
        Bitmap src, float[] dst, int netW, int netH)
    {
        int srcW = src.Width;
        int srcH = src.Height;
        float scale = Math.Min((float)netW / srcW, (float)netH / srcH);
        int newW = Math.Clamp((int)(srcW * scale), 1, netW);
        int newH = Math.Clamp((int)(srcH * scale), 1, netH);

        Array.Clear(dst);
        using Bitmap? owned = (srcW == newW && srcH == newH) ? null : ResizeBitmap(src, newW, newH);
        Bitmap bmp = owned ?? src;

        var rect = new Rectangle(0, 0, newW, newH);
        var data = bmp.LockBits(rect, ImageLockMode.ReadOnly, PixelFormat.Format32bppArgb);
        try
        {
            int channelSize = netW * netH;
            byte* basePtr = (byte*)data.Scan0;
            int stride = data.Stride;
            const float inv255 = 1f / 255f;

            for (int y = 0; y < newH; y++)
            {
                byte* row = basePtr + y * stride;
                int dstRow = y * netW;
                for (int x = 0; x < newW; x++)
                {
                    int dstIdx = dstRow + x;
                    int pxOff = x * 4;
                    dst[dstIdx] = row[pxOff + 2] * inv255;
                    dst[dstIdx + channelSize] = row[pxOff + 1] * inv255;
                    dst[dstIdx + 2 * channelSize] = row[pxOff] * inv255;
                }
            }
        }
        finally
        {
            bmp.UnlockBits(data);
        }

        return ((float)srcW / newW, (float)srcH / newH);
    }

    public static unsafe void FillImageNetChw(Bitmap src, float[] dst, int size = 224)
    {
        using Bitmap? owned = (src.Width == size && src.Height == size) ? null : ResizeBitmap(src, size, size);
        Bitmap bmp = owned ?? src;
        var rect = new Rectangle(0, 0, size, size);
        var data = bmp.LockBits(rect, ImageLockMode.ReadOnly, PixelFormat.Format32bppArgb);
        try
        {
            int channelSize = size * size;
            byte* basePtr = (byte*)data.Scan0;
            int stride = data.Stride;
            const float inv255 = 1f / 255f;

            for (int y = 0; y < size; y++)
            {
                byte* row = basePtr + y * stride;
                int rowOff = y * size;
                for (int x = 0; x < size; x++)
                {
                    int idx = rowOff + x;
                    int pxOff = x * 4;
                    dst[idx] = (row[pxOff + 2] * inv255 - 0.485f) / 0.229f;
                    dst[idx + channelSize] = (row[pxOff + 1] * inv255 - 0.456f) / 0.224f;
                    dst[idx + 2 * channelSize] = (row[pxOff] * inv255 - 0.406f) / 0.225f;
                }
            }
        }
        finally
        {
            bmp.UnlockBits(data);
        }
    }

    public static Bitmap ResizeBitmap(Bitmap src, int width, int height)
    {
        var dest = new Bitmap(width, height, PixelFormat.Format32bppArgb);
        using var g = Graphics.FromImage(dest);
        g.InterpolationMode = InterpolationMode.Bilinear;
        g.PixelOffsetMode = PixelOffsetMode.Half;
        g.DrawImage(src, 0, 0, width, height);
        return dest;
    }

    public static unsafe BgraFrameSampler CaptureSampler(Bitmap bmp)
    {
        int w = bmp.Width;
        int h = bmp.Height;
        var rect = new Rectangle(0, 0, w, h);
        var data = bmp.LockBits(rect, ImageLockMode.ReadOnly, PixelFormat.Format32bppArgb);
        try
        {
            int stride = Math.Abs(data.Stride);
            byte[] buf = new byte[stride * h];
            System.Runtime.InteropServices.Marshal.Copy(data.Scan0, buf, 0, buf.Length);
            return new BgraFrameSampler(buf, w, h, stride);
        }
        finally
        {
            bmp.UnlockBits(data);
        }
    }
}

public sealed class NudeNetOnnxDetector : IDisposable
{
    private readonly InferenceSession _session;
    private readonly object _lock = new();
    private readonly float[] _inputBuf = new float[3 * NudeNetFilterEngine.ModelWidth * NudeNetFilterEngine.ModelHeight];
    private readonly float[][] _outRows = Enumerable.Range(0, 4 + NudeNetFilterEngine.NumClasses)
        .Select(_ => new float[NudeNetFilterEngine.NumPredictions])
        .ToArray();

    public float ConfidenceThreshold { get; set; } = 0.25f;
    public float IouThreshold { get; set; } = 0.45f;

    public NudeNetOnnxDetector()
    {
        _session = new InferenceSession(ModelPathLocator.Resolve("best_320s.onnx"), ModelPathLocator.CreateDefaultOptions(2));
    }

    public List<Detection> Detect(Bitmap bitmap, ISkinImageSampler sampler, bool includeQuadrants = true)
    {
        lock (_lock)
        {
            float fullW = bitmap.Width;
            float fullH = bitmap.Height;
            var all = new List<Detection>(64);

            all.AddRange(RunInference(bitmap, 0f, 0f, fullW, fullH));

            // Desktop screens are landscape or tall; tile 2x2 quadrants so smaller thumbnails/grid feeds stay sharp
            bool shouldTile = (includeQuadrants || all.Count >= 2) && Math.Max(fullW, fullH) >= 900f;
            if (shouldTile)
            {
                int quadW = Math.Max(1, (int)(fullW * 0.56f));
                int rightX = Math.Clamp((int)(fullW * 0.44f), 0, bitmap.Width - 1);
                int rightW = Math.Max(1, bitmap.Width - rightX);
                int quadH = Math.Max(1, (int)(fullH * 0.56f));
                int botY = Math.Clamp((int)(fullH * 0.44f), 0, bitmap.Height - 1);
                int botH = Math.Max(1, bitmap.Height - botY);

                (int x, int y, int w, int h)[] tiles =
                [
                    (0, 0, quadW, quadH),
                    (rightX, 0, rightW, quadH),
                    (0, botY, quadW, botH),
                    (rightX, botY, rightW, botH)
                ];

                foreach (var (xOff, yOff, w, h) in tiles)
                {
                    if (w <= 32 || h <= 32) continue;
                    using var tile = bitmap.Clone(new Rectangle(xOff, yOff, w, h), PixelFormat.Format32bppArgb);
                    all.AddRange(RunInference(tile, xOff, yOff, w, h));
                }
            }

            var consolidated = NudeNetFilterEngine.Nms(all, IouThreshold);
            var faces = consolidated.Where(d => d.Label.IsFace()).ToList();

            return consolidated.Where(det =>
            {
                if (det.Label.IsFace()) return true;
                if (det.Label.IsExposed())
                {
                    bool hasNearFace = faces.Any(f =>
                        MathF.Abs(f.Box.CenterX - det.Box.CenterX) < Math.Max(f.Box.Width * 2.5f, det.Box.Width * 1.5f) &&
                        det.Box.Y1 >= f.Box.Y1 - 10f && det.Box.Y1 <= f.Box.Y2 + f.Box.Height * 6.0f);
                    return sampler.HasHumanSkin(det.Box, hasNearFace ? 0.05f : 0.15f, hasNearFace);
                }
                return faces.Count > 0 || sampler.HasHumanSkin(det.Box, 0.08f, false);
            }).ToList();
        }
    }

    private List<Detection> RunInference(Bitmap tile, float offsetX, float offsetY, float tileW, float tileH)
    {
        ModelPathLocator.FillChw01(tile, _inputBuf, NudeNetFilterEngine.ModelWidth, NudeNetFilterEngine.ModelHeight);
        var tensor = new DenseTensor<float>(_inputBuf, [1, 3, NudeNetFilterEngine.ModelHeight, NudeNetFilterEngine.ModelWidth]);
        using var results = _session.Run([NamedOnnxValue.CreateFromTensor("images", tensor)]);
        var outTensor = results.First(r => r.Name == "output0").AsTensor<float>();

        int channels = _outRows.Length;
        int preds = NudeNetFilterEngine.NumPredictions;
        for (int c = 0; c < channels; c++)
        {
            for (int i = 0; i < preds; i++)
            {
                _outRows[c][i] = outTensor[0, c, i];
            }
        }

        return NudeNetFilterEngine.PostProcess(_outRows, tileW, tileH, offsetX, offsetY, ConfidenceThreshold);
    }

    public void Dispose() => _session.Dispose();
}

public sealed class GenderPersonOnnxDetector : IDisposable
{
    private readonly InferenceSession _session;
    private readonly object _lock = new();
    private readonly float[] _inputBuf = new float[3 * GenderPersonDecoder.ModelWidth * GenderPersonDecoder.ModelHeight];

    public float ConfidenceThreshold { get; set; } = 0.25f;
    public float IouThreshold { get; set; } = 0.45f;

    public GenderPersonOnnxDetector()
    {
        _session = new InferenceSession(ModelPathLocator.Resolve("pordav4x3.onnx"), ModelPathLocator.CreateDefaultOptions(2));
    }

    public List<PersonGenderBox> Detect(Bitmap bitmap, bool includeTiles = true)
    {
        lock (_lock)
        {
            float fullW = bitmap.Width;
            float fullH = bitmap.Height;
            var all = new List<PersonGenderBox>(32);
            all.AddRange(RunInference(bitmap, 0f, 0f, fullW, fullH));

            if (includeTiles && fullW >= 1200f)
            {
                // Split wide desktop screens into overlapping left/right halves so distant persons match 544x320 aspect cleanly
                int halfW = Math.Max(1, (int)(fullW * 0.58f));
                int rightX = Math.Clamp((int)(fullW * 0.42f), 0, bitmap.Width - 1);
                int rightW = Math.Max(1, bitmap.Width - rightX);

                using (var leftTile = bitmap.Clone(new Rectangle(0, 0, halfW, bitmap.Height), PixelFormat.Format32bppArgb))
                    all.AddRange(RunInference(leftTile, 0f, 0f, fullW, fullH));
                using (var rightTile = bitmap.Clone(new Rectangle(rightX, 0, rightW, bitmap.Height), PixelFormat.Format32bppArgb))
                    all.AddRange(RunInference(rightTile, rightX, 0f, fullW, fullH));
            }
            else if (includeTiles && (fullH / fullW) > 1.35f && fullH > 600f)
            {
                int stripH = Math.Max(1, (int)(fullH * 0.36f));
                int[] offsets = [0, (int)(fullH * 0.22f), (int)(fullH * 0.44f), (int)(fullH * 0.64f)];
                foreach (int yOff in offsets)
                {
                    int safeH = Math.Min(stripH, bitmap.Height - yOff);
                    if (safeH <= 32) continue;
                    using var tile = bitmap.Clone(new Rectangle(0, yOff, bitmap.Width, safeH), PixelFormat.Format32bppArgb);
                    all.AddRange(RunInference(tile, 0f, yOff, fullW, fullH));
                }
            }

            return GenderPersonDecoder.ApplyNms(all, IouThreshold);
        }
    }

    private List<PersonGenderBox> RunInference(Bitmap tile, float offsetX, float offsetY, float fullW, float fullH)
    {
        var (scaleX, scaleY) = ModelPathLocator.FillTopLeftLetterboxChw01(
            tile, _inputBuf, GenderPersonDecoder.ModelWidth, GenderPersonDecoder.ModelHeight);
        var tensor = new DenseTensor<float>(_inputBuf, [1, 3, GenderPersonDecoder.ModelHeight, GenderPersonDecoder.ModelWidth]);
        using var results = _session.Run([NamedOnnxValue.CreateFromTensor("images", tensor)]);

        var parsed = new List<PersonGenderBox>();
        for (int headIndex = 0; headIndex < GenderPersonDecoder.HeadNames.Length; headIndex++)
        {
            string headName = GenderPersonDecoder.HeadNames[headIndex];
            var node = results.FirstOrDefault(r => r.Name == headName);
            if (node == null) continue;
            var t = node.AsTensor<float>();
            int ch = t.Dimensions[1];
            int gridH = t.Dimensions[2];
            int gridW = t.Dimensions[3];

            var flat = new float[ch][];
            for (int c = 0; c < ch; c++)
            {
                var row = new float[gridH * gridW];
                for (int y = 0; y < gridH; y++)
                {
                    int rowBase = y * gridW;
                    for (int x = 0; x < gridW; x++)
                    {
                        row[rowBase + x] = t[0, c, y, x];
                    }
                }
                flat[c] = row;
            }

            parsed.AddRange(GenderPersonDecoder.DecodeHead(
                flat, gridH, gridW, headIndex, scaleX, scaleY,
                offsetX, offsetY, fullW, fullH, ConfidenceThreshold));
        }
        return parsed;
    }

    public void Dispose() => _session.Dispose();
}

public sealed class IntimateSceneOnnxDetector : IDisposable
{
    private readonly InferenceSession _session;
    private readonly object _lock = new();
    private readonly float[] _inputBuf = new float[3 * 224 * 224];
    private readonly string _inputName;

    public IntimateSceneOnnxDetector()
    {
        _session = new InferenceSession(ModelPathLocator.Resolve("image_safety_xs.onnx"), ModelPathLocator.CreateDefaultOptions(2));
        _inputName = _session.InputMetadata.Keys.FirstOrDefault() ?? "image";
    }

    public SceneSafetyScores? Classify(Bitmap bitmap)
    {
        lock (_lock)
        {
            ModelPathLocator.FillImageNetChw(bitmap, _inputBuf, 224);
            var tensor = new DenseTensor<float>(_inputBuf, [1, 3, 224, 224]);
            using var results = _session.Run([NamedOnnxValue.CreateFromTensor(_inputName, tensor)]);
            var probs = results.First().AsTensor<float>();
            if (probs.Length < 3) return null;
            return new SceneSafetyScores(probs[0, 0], probs[0, 1], probs[0, 2]);
        }
    }

    public void Dispose() => _session.Dispose();
}

public sealed class AudioIntimateOnnxDetector : IDisposable
{
    private readonly InferenceSession _session;
    private readonly object _lock = new();
    private readonly string _inputName;

    public AudioIntimateOnnxDetector()
    {
        _session = new InferenceSession(ModelPathLocator.Resolve("yamnet.onnx"), ModelPathLocator.CreateDefaultOptions(2));
        _inputName = _session.InputMetadata.Keys.FirstOrDefault() ?? "waveform";
    }

    public AudioIntimateResult ScoreWaveform(float[] waveform15600, float rms)
    {
        if (rms < AudioIntimateRules.SilenceRmsGate)
        {
            return new AudioIntimateResult(0f, "", rms, false);
        }

        lock (_lock)
        {
            var tensor = new DenseTensor<float>(waveform15600, [waveform15600.Length]);
            using var results = _session.Run([NamedOnnxValue.CreateFromTensor(_inputName, tensor)]);
            var outTensor = results.First().AsTensor<float>();
            int cols = outTensor.Dimensions[1];
            var frameProbs = new float[cols];
            for (int i = 0; i < cols; i++)
            {
                frameProbs[i] = outTensor[0, i];
            }
            return AudioIntimateRules.ScoreFrame(frameProbs, rms);
        }
    }

    public void Dispose() => _session.Dispose();
}
