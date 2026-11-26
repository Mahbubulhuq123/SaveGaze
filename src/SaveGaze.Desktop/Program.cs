using System.IO;
using System.IO.Pipes;
using SaveGaze.Core;

namespace SaveGaze.Desktop;

internal static class Program
{
    [STAThread]
    private static int Main(string[] args)
    {
        Application.SetHighDpiMode(HighDpiMode.PerMonitorV2);
        Application.EnableVisualStyles();
        Application.SetCompatibleTextRenderingDefault(false);

        if (args.Any(a => a.Equals("--native-host", StringComparison.OrdinalIgnoreCase)) ||
            args.Any(a => a.StartsWith("chrome-extension://", StringComparison.OrdinalIgnoreCase)))
        {
            return NativeMessagingHost.RunLoop();
        }

        if (args.Any(a => a.Equals("--verify", StringComparison.OrdinalIgnoreCase)))
        {
            int idx = Array.FindIndex(args, a => a.Equals("--verify", StringComparison.OrdinalIgnoreCase));
            string action = idx >= 0 && idx + 1 < args.Length ? args[idx + 1] : "Protection Feature";
            var cfg = AppConfig.Load();
            bool passed = VerificationDialogForm.VerifyProtectionChange(action, cfg.PasswordHash, null, requireSentence: true);
            return passed ? 0 : 1;
        }

        if (args.Any(a => a.Equals("--install", StringComparison.OrdinalIgnoreCase)))
        {
            string? extId = GetArgValue(args, "--extension-id");
            string? pw = GetArgValue(args, "--password");
            var (ok, _) = InstallerEngine.Install(AppContext.BaseDirectory, extId, pw);
            return ok ? 0 : 1;
        }

        if (args.Any(a => a.Equals("--uninstall", StringComparison.OrdinalIgnoreCase)))
        {
            var cfg = AppConfig.Load();
            if (!VerificationDialogForm.VerifyProtectionChange($"Uninstall {BrandConfig.AppName}", cfg.PasswordHash, null, requireSentence: true))
            {
                return 1;
            }
            var (ok, log) = InstallerEngine.Uninstall(AppContext.BaseDirectory);
            MessageBox.Show(log, BrandConfig.DesktopDisplayName, MessageBoxButtons.OK,
                ok ? MessageBoxIcon.Information : MessageBoxIcon.Warning);
            return ok ? 0 : 1;
        }

        using var singleInstance = new Mutex(initiallyOwned: true, BrandConfig.MutexName, out bool createdNew);
        if (!createdNew)
        {
            NativeMessagingHost.NotifyDesktopService("show_ui");
            return 0;
        }

        bool minimized = args.Any(a => a.Equals("--minimized", StringComparison.OrdinalIgnoreCase));
        var form = new MainForm(startMinimized: minimized);
        StartPipeListener(form);
        Application.Run(form);
        return 0;
    }

    private static string? GetArgValue(string[] args, string flag)
    {
        int idx = Array.FindIndex(args, a => a.Equals(flag, StringComparison.OrdinalIgnoreCase));
        return idx >= 0 && idx + 1 < args.Length ? args[idx + 1] : null;
    }

    private static void StartPipeListener(MainForm form)
    {
        Task.Run(async () =>
        {
            while (!form.IsDisposed)
            {
                try
                {
                    using var server = new NamedPipeServerStream(BrandConfig.PipeName, PipeDirection.In);
                    await server.WaitForConnectionAsync();
                    using var reader = new StreamReader(server);
                    string? line = await reader.ReadLineAsync();
                    if (string.IsNullOrWhiteSpace(line)) continue;

                    if (line == "reload_config")
                    {
                        form.ReloadConfigFromDisk();
                    }
                    else if (line == "show_ui")
                    {
                        form.BeginInvoke(() =>
                        {
                            form.ShowInTaskbar = true;
                            form.Show();
                            form.WindowState = FormWindowState.Normal;
                            form.BringToFront();
                        });
                    }
                    else if (line.StartsWith("intimate_caption:", StringComparison.Ordinal))
                    {
                        form.HandleRemoteIntimateCaption(line["intimate_caption:".Length..]);
                    }
                }
                catch
                {
                    await Task.Delay(400);
                }
            }
        });
    }
}
