using System.Drawing;
using System.Drawing.Imaging;
using System.Windows.Forms;
using SaveGaze.Core;
using SaveGaze.Desktop;
using Xunit;

namespace SaveGaze.Tests;

public sealed class OnnxModelSmokeTests
{
    [Fact]
    public void AllFourOnnxModelsLoadAndRunCleanlyOnSyntheticInputs()
    {
        using var bmp = new Bitmap(640, 360, PixelFormat.Format32bppArgb);
        using (var g = Graphics.FromImage(bmp))
        {
            g.Clear(Color.FromArgb(30, 41, 59));
        }

        var sampler = ModelPathLocator.CaptureSampler(bmp);

        using var nude = new NudeNetOnnxDetector();
        var nudeDets = nude.Detect(bmp, sampler, includeQuadrants: false);
        Assert.NotNull(nudeDets);

        using var gender = new GenderPersonOnnxDetector();
        var genderDets = gender.Detect(bmp, includeTiles: false);
        Assert.NotNull(genderDets);

        using var scene = new IntimateSceneOnnxDetector();
        var scores = scene.Classify(bmp);
        Assert.NotNull(scores);
        Assert.InRange(scores!.Nsfl + scores.Nsfw + scores.Sfw, 0.90f, 1.10f);

        using var audio = new AudioIntimateOnnxDetector();
        var waveform = new float[15_600];
        for (int i = 0; i < waveform.Length; i++) waveform[i] = 0.05f * MathF.Sin(i * 0.1f);
        var audioRes = audio.ScoreWaveform(waveform, 0.035f);
        Assert.InRange(audioRes.Score, 0f, 1f);
    }

    [Fact]
    public void OverlaySupportsCaptureExclusionAndWasapiMonitorStartsCleanly()
    {
        Exception? uiError = null;
        bool overlayActive = false;

        var staThread = new Thread(() =>
        {
            try
            {
                using var host = new Form { ShowInTaskbar = false, WindowState = FormWindowState.Minimized };
                host.CreateControl();
                var _ = host.Handle;

                using var overlay = new OverlayManager(host);
                overlayActive = overlay.IsOverlayActive;
                overlay.SetStyleAndOpacity(BlurStyle.SolidBlack, 100);
                overlay.UpdateBlurRegions([new BoundingBox(50, 50, 220, 220)], []);
                Application.DoEvents();
                overlay.ClearBlurRegions();
                Application.DoEvents();
            }
            catch (Exception ex)
            {
                uiError = ex;
            }
        });
        staThread.SetApartmentState(ApartmentState.STA);
        staThread.Start();
        staThread.Join(5000);

        Assert.Null(uiError);
        Assert.True(overlayActive, "Expected WDA_EXCLUDEFROMCAPTURE to succeed on Windows 10/11");

        using var audioDet = new AudioIntimateOnnxDetector();
        using var monitor = new WasapiAudioMonitor(audioDet);
        monitor.Start();
        monitor.Stop();
    }
}
