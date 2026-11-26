using System.Diagnostics;
using System.Drawing;
using System.Net.Http.Headers;
using SaveGaze.Core;

namespace SaveGaze.Desktop;

public sealed class UpdateDialogForm : Form
{
    private readonly PcUpdateInfo _info;
    private readonly AppConfig _config;
    private readonly ProgressBar _progressBar;
    private readonly Label _lblStatus;
    private readonly Button _btnUpdate;
    private readonly Button _btnLater;
    private readonly Button _btnSkip;
    private CancellationTokenSource? _dlCts;

    public UpdateDialogForm(PcUpdateInfo info, AppConfig config)
    {
        _info = info;
        _config = config;

        Text = $"{BrandConfig.DesktopDisplayName} — Update Available";
        Size = new Size(520, 400);
        StartPosition = FormStartPosition.CenterParent;
        FormBorderStyle = FormBorderStyle.FixedDialog;
        MaximizeBox = false;
        MinimizeBox = false;
        BackColor = Color.FromArgb(15, 23, 42);
        ForeColor = Color.White;
        Font = new Font("Segoe UI", 9.5f);

        Controls.Add(new Label
        {
            Text = $"New Version Available: v{info.VersionName}",
            Font = new Font("Segoe UI", 14f, FontStyle.Bold),
            ForeColor = Color.FromArgb(196, 181, 253),
            Location = new Point(22, 18),
            Size = new Size(460, 32)
        });

        string sizeText = info.SizeBytes > 0 ? $" ({info.SizeBytes / (1024.0 * 1024.0):F1} MB)" : "";
        Controls.Add(new Label
        {
            Text = $"Installed: v{BrandConfig.AppVersion}  ->  Latest: v{info.VersionName}{sizeText}",
            Font = new Font("Segoe UI", 9.5f, FontStyle.Bold),
            ForeColor = Color.FromArgb(74, 222, 128),
            Location = new Point(24, 54),
            Size = new Size(460, 22)
        });

        var txtNotes = new TextBox
        {
            Multiline = true,
            ReadOnly = true,
            ScrollBars = ScrollBars.Vertical,
            Location = new Point(24, 86),
            Size = new Size(456, 150),
            BackColor = Color.FromArgb(30, 41, 59),
            ForeColor = Color.FromArgb(226, 232, 240),
            BorderStyle = BorderStyle.FixedSingle,
            Text = string.IsNullOrWhiteSpace(info.ReleaseNotes)
                ? "Performance improvements, updated AI guard rules, and bug fixes."
                : info.ReleaseNotes
        };
        Controls.Add(txtNotes);

        _progressBar = new ProgressBar
        {
            Location = new Point(24, 248),
            Size = new Size(456, 18),
            Minimum = 0,
            Maximum = 100,
            Visible = false
        };
        Controls.Add(_progressBar);

        _lblStatus = new Label
        {
            Text = $"Asset: {info.AssetName}",
            Font = new Font("Segoe UI", 8.5f),
            ForeColor = Color.FromArgb(148, 163, 184),
            Location = new Point(24, 272),
            Size = new Size(456, 22)
        };
        Controls.Add(_lblStatus);

        _btnUpdate = new Button
        {
            Text = "Download & Install Update",
            Font = new Font("Segoe UI", 9.5f, FontStyle.Bold),
            BackColor = Color.FromArgb(124, 58, 237),
            ForeColor = Color.White,
            FlatStyle = FlatStyle.Flat,
            Location = new Point(275, 308),
            Size = new Size(205, 38)
        };
        _btnUpdate.FlatAppearance.BorderSize = 0;
        _btnUpdate.Click += async (_, _) => await DownloadAndRunAsync();
        Controls.Add(_btnUpdate);

        _btnLater = new Button
        {
            Text = "Remind in 24h",
            BackColor = Color.FromArgb(51, 65, 85),
            ForeColor = Color.White,
            FlatStyle = FlatStyle.Flat,
            Location = new Point(150, 308),
            Size = new Size(115, 38)
        };
        _btnLater.FlatAppearance.BorderSize = 0;
        _btnLater.Click += (_, _) =>
        {
            _config.RemindMeLaterUntilMs = DateTimeOffset.UtcNow.AddHours(24).ToUnixTimeMilliseconds();
            _config.Save();
            Close();
        };
        Controls.Add(_btnLater);

        _btnSkip = new Button
        {
            Text = "Skip Version",
            BackColor = Color.FromArgb(30, 41, 59),
            ForeColor = Color.FromArgb(148, 163, 184),
            FlatStyle = FlatStyle.Flat,
            Location = new Point(24, 308),
            Size = new Size(115, 38)
        };
        _btnSkip.FlatAppearance.BorderSize = 0;
        _btnSkip.Click += (_, _) =>
        {
            _config.DismissedUpdateVersion = _info.VersionName;
            _config.Save();
            Close();
        };
        Controls.Add(_btnSkip);

        FormClosing += (_, _) => _dlCts?.Cancel();
    }

    private async Task DownloadAndRunAsync()
    {
        _btnUpdate.Enabled = false;
        _btnLater.Enabled = false;
        _btnSkip.Enabled = false;
        _progressBar.Visible = true;
        _lblStatus.Text = "Downloading update...";
        _dlCts = new CancellationTokenSource();

        try
        {
            string updateDir = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                BrandConfig.AppName, "updates");
            Directory.CreateDirectory(updateDir);
            string targetFile = Path.Combine(updateDir, _info.AssetName);
            string tmpFile = targetFile + ".tmp";

            using var client = new HttpClient { Timeout = TimeSpan.FromMinutes(10) };
            client.DefaultRequestHeaders.UserAgent.Add(new ProductInfoHeaderValue(BrandConfig.AppName + "PC", BrandConfig.AppVersion));

            using var resp = await client.GetAsync(_info.DownloadUrl, HttpCompletionOption.ResponseHeadersRead, _dlCts.Token);
            resp.EnsureSuccessStatusCode();
            long total = resp.Content.Headers.ContentLength ?? _info.SizeBytes;

            await using (var src = await resp.Content.ReadAsStreamAsync(_dlCts.Token))
            await using (var dst = File.Create(tmpFile))
            {
                byte[] buf = new byte[16384];
                long readTotal = 0;
                int n;
                while ((n = await src.ReadAsync(buf, _dlCts.Token)) > 0)
                {
                    await dst.WriteAsync(buf.AsMemory(0, n), _dlCts.Token);
                    readTotal += n;
                    if (total > 0)
                    {
                        int pct = Math.Clamp((int)(readTotal * 100 / total), 0, 100);
                        _progressBar.Value = pct;
                        _lblStatus.Text = $"Downloading: {readTotal / (1024.0 * 1024.0):F1} / {total / (1024.0 * 1024.0):F1} MB ({pct}%)";
                    }
                }
            }

            File.Move(tmpFile, targetFile, overwrite: true);
            _lblStatus.Text = "Download complete. Launching...";

            if (targetFile.EndsWith(".exe", StringComparison.OrdinalIgnoreCase))
            {
                Process.Start(new ProcessStartInfo { FileName = targetFile, UseShellExecute = true });
                Application.Exit();
            }
            else
            {
                Process.Start(new ProcessStartInfo { FileName = "explorer.exe", Arguments = $"/select,\"{targetFile}\"", UseShellExecute = true });
                Close();
            }
        }
        catch (Exception ex)
        {
            _lblStatus.ForeColor = Color.FromArgb(244, 63, 94);
            _lblStatus.Text = "Download failed: " + ex.Message;
            _btnUpdate.Enabled = true;
            _btnLater.Enabled = true;
            _btnSkip.Enabled = true;
        }
    }
}
