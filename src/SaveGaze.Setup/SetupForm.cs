using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.IO.Compression;
using System.Reflection;
using SaveGaze.Core;

namespace SaveGaze.Setup;

internal static class Program
{
    [STAThread]
    private static int Main(string[] args)
    {
        Application.EnableVisualStyles();
        Application.SetCompatibleTextRenderingDefault(false);

        bool silent = args.Any(a => a.Equals("--silent", StringComparison.OrdinalIgnoreCase));
        if (silent)
        {
            string dir = SetupEngine.ResolveDefaultInstallDir();
            return SetupEngine.InstallFromEmbeddedPayload(dir, null, null, launchAfter: true) ? 0 : 1;
        }

        Application.Run(new SetupForm());
        return 0;
    }
}

internal static class SetupEngine
{
    public static string ResolveDefaultInstallDir()
    {
        try
        {
            using var key = Microsoft.Win32.Registry.CurrentUser.OpenSubKey(
                $@"Software\Google\Chrome\NativeMessagingHosts\{BrandConfig.NativeHostName}");
            string? manifestPath = key?.GetValue("") as string;
            if (!string.IsNullOrWhiteSpace(manifestPath))
            {
                string? dir = Path.GetDirectoryName(manifestPath);
                if (!string.IsNullOrWhiteSpace(dir)) return dir;
            }
        }
        catch { }

        return Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            BrandConfig.AppName);
    }

    public static bool InstallFromEmbeddedPayload(
        string installDir,
        string? password,
        string? extensionId,
        bool launchAfter,
        Action<string>? onProgress = null)
    {
        try
        {
            onProgress?.Invoke("Stopping existing SaveGaze / ContentGuard processes...");
            foreach (string procName in new[] { BrandConfig.AppName, "SaveGaze", "ContentGuard" })
            {
                foreach (var p in Process.GetProcessesByName(procName))
                {
                    try { p.Kill(); p.WaitForExit(3000); } catch { }
                }
            }

            Directory.CreateDirectory(installDir);
            using var stream = Assembly.GetExecutingAssembly().GetManifestResourceStream("SaveGaze.Setup.payload.zip");
            if (stream == null)
            {
                onProgress?.Invoke("Error: Embedded payload not found in installer.");
                return false;
            }

            onProgress?.Invoke($"Extracting {BrandConfig.DesktopDisplayName} files to {installDir}...");
            string fullRoot = Path.GetFullPath(installDir);
            if (!fullRoot.EndsWith(Path.DirectorySeparatorChar.ToString())) fullRoot += Path.DirectorySeparatorChar;
            using (var archive = new ZipArchive(stream, ZipArchiveMode.Read))
            {
                foreach (var entry in archive.Entries)
                {
                    if (string.IsNullOrEmpty(entry.Name)) continue;
                    string destPath = Path.GetFullPath(Path.Combine(fullRoot, entry.FullName));
                    if (!destPath.StartsWith(fullRoot, StringComparison.OrdinalIgnoreCase)) continue;
                    string? parent = Path.GetDirectoryName(destPath);
                    if (!string.IsNullOrEmpty(parent)) Directory.CreateDirectory(parent);
                    entry.ExtractToFile(destPath, overwrite: true);
                }
            }

            onProgress?.Invoke("Configuring Windows integration, Native Messaging, and Incognito shield...");
            string exePath = Path.Combine(installDir, "SaveGaze.exe");
            var argList = new List<string> { "--install" };
            if (!string.IsNullOrWhiteSpace(password))
            {
                argList.Add("--password");
                argList.Add($"\"{password!.Replace("\"", "\\\"")}\"");
            }
            if (!string.IsNullOrWhiteSpace(extensionId))
            {
                argList.Add("--extension-id");
                argList.Add($"\"{extensionId!.Replace("\"", "")}\"");
            }

            using (var proc = Process.Start(new ProcessStartInfo
            {
                FileName = exePath,
                Arguments = string.Join(" ", argList),
                WorkingDirectory = installDir,
                CreateNoWindow = true,
                UseShellExecute = false
            }))
            {
                proc?.WaitForExit(20000);
            }

            CreateShortcut(
                Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory), $"{BrandConfig.DesktopDisplayName}.lnk"),
                exePath, installDir);
            CreateShortcut(
                Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Programs), $"{BrandConfig.DesktopDisplayName}.lnk"),
                exePath, installDir);

            onProgress?.Invoke("Installation complete!");
            if (launchAfter && File.Exists(exePath))
            {
                Process.Start(new ProcessStartInfo { FileName = exePath, WorkingDirectory = installDir, UseShellExecute = true });
            }
            return true;
        }
        catch (Exception ex)
        {
            onProgress?.Invoke("Install failed: " + ex.Message);
            return false;
        }
    }

    private static void CreateShortcut(string lnkPath, string targetExe, string workDir)
    {
        try
        {
            string ps = $"$ws = New-Object -ComObject WScript.Shell; " +
                        $"$sc = $ws.CreateShortcut('{lnkPath.Replace("'", "''")}'); " +
                        $"$sc.TargetPath = '{targetExe.Replace("'", "''")}'; " +
                        $"$sc.WorkingDirectory = '{workDir.Replace("'", "''")}'; " +
                        $"$sc.Save()";
            using var p = Process.Start(new ProcessStartInfo
            {
                FileName = "powershell",
                Arguments = $"-NoProfile -Command \"{ps}\"",
                CreateNoWindow = true,
                UseShellExecute = false
            });
            p?.WaitForExit(5000);
        }
        catch { }
    }
}

public sealed class SetupForm : Form
{
    private readonly TextBox _txtPath;
    private readonly TextBox _txtPassword;
    private readonly TextBox _txtExtId;
    private readonly Label _lblStatus;
    private readonly Button _btnInstall;

    public SetupForm()
    {
        Text = $"{BrandConfig.DesktopDisplayName} v{BrandConfig.AppVersion} — Setup";
        Size = new Size(560, 400);
        StartPosition = FormStartPosition.CenterScreen;
        FormBorderStyle = FormBorderStyle.FixedDialog;
        MaximizeBox = false;
        BackColor = Color.FromArgb(15, 23, 42);
        ForeColor = Color.White;
        Font = new Font("Segoe UI", 9.5f);

        Controls.Add(new Label
        {
            Text = $"Install {BrandConfig.DesktopDisplayName} ({BrandConfig.ArabicTag})",
            Font = new Font("Segoe UI", 14f, FontStyle.Bold),
            ForeColor = Color.FromArgb(196, 181, 253),
            Location = new Point(22, 18),
            AutoSize = true
        });
        Controls.Add(new Label
        {
            Text = "Includes On-Device ONNX Screen Blur, WASAPI Audio Guard, and Browser Extension.",
            ForeColor = Color.FromArgb(148, 163, 184),
            Location = new Point(24, 50),
            Size = new Size(500, 22)
        });

        string defaultDir = SetupEngine.ResolveDefaultInstallDir();
        Controls.Add(new Label { Text = "Install Directory:", Location = new Point(24, 88), AutoSize = true });
        _txtPath = new TextBox { Text = defaultDir, Location = new Point(24, 112), Size = new Size(410, 26), BackColor = Color.FromArgb(30, 41, 59), ForeColor = Color.White };
        var btnBrowse = new Button { Text = "Browse...", Location = new Point(442, 111), Size = new Size(80, 28), BackColor = Color.FromArgb(51, 65, 85), ForeColor = Color.White, FlatStyle = FlatStyle.Flat };
        btnBrowse.FlatAppearance.BorderSize = 0;
        btnBrowse.Click += (_, _) =>
        {
            using var dlg = new FolderBrowserDialog { SelectedPath = _txtPath.Text };
            if (dlg.ShowDialog(this) == DialogResult.OK) _txtPath.Text = dlg.SelectedPath;
        };
        Controls.Add(_txtPath);
        Controls.Add(btnBrowse);

        Controls.Add(new Label { Text = "Master Password (optional if already set):", Location = new Point(24, 152), AutoSize = true });
        _txtPassword = new TextBox { PasswordChar = '*', Location = new Point(24, 176), Size = new Size(498, 26), BackColor = Color.FromArgb(30, 41, 59), ForeColor = Color.White };
        Controls.Add(_txtPassword);

        Controls.Add(new Label { Text = "Chrome/Brave Extension ID (optional, can set later):", Location = new Point(24, 214), AutoSize = true });
        _txtExtId = new TextBox { Location = new Point(24, 238), Size = new Size(498, 26), BackColor = Color.FromArgb(30, 41, 59), ForeColor = Color.White };
        Controls.Add(_txtExtId);

        _lblStatus = new Label { Text = "Ready to install.", Location = new Point(24, 278), Size = new Size(498, 32), ForeColor = Color.FromArgb(74, 222, 128) };
        Controls.Add(_lblStatus);

        _btnInstall = new Button
        {
            Text = $"Install & Launch {BrandConfig.AppName}",
            Font = new Font("Segoe UI", 10f, FontStyle.Bold),
            BackColor = Color.FromArgb(124, 58, 237),
            ForeColor = Color.White,
            FlatStyle = FlatStyle.Flat,
            Location = new Point(280, 314),
            Size = new Size(242, 38)
        };
        _btnInstall.FlatAppearance.BorderSize = 0;
        _btnInstall.Click += async (_, _) => await RunInstallAsync();
        Controls.Add(_btnInstall);
    }

    private async Task RunInstallAsync()
    {
        _btnInstall.Enabled = false;
        string dir = _txtPath.Text.Trim();
        string pw = _txtPassword.Text;
        string ext = _txtExtId.Text.Trim();

        bool ok = await Task.Run(() => SetupEngine.InstallFromEmbeddedPayload(
            dir, pw, ext, launchAfter: true,
            msg => BeginInvoke(() => _lblStatus.Text = msg)));

        if (ok)
        {
            Close();
        }
        else
        {
            _btnInstall.Enabled = true;
            _lblStatus.ForeColor = Color.FromArgb(244, 63, 94);
        }
    }
}
