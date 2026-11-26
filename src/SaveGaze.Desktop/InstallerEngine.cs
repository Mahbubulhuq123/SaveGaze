using System.Diagnostics;
using System.Text.Json;
using Microsoft.Win32;
using SaveGaze.Core;

namespace SaveGaze.Desktop;

/// <summary>
/// Handles clean installation, migration from legacy ContentGuard service/registry,
/// Chrome/Brave/Edge Native Messaging registration, Hosts blocklist management, and password-shielded uninstall.
/// Never modifies C:\Users\user\ContentGuard backup files.
/// </summary>
public static class InstallerEngine
{
    private const string HostsPath = @"C:\Windows\System32\drivers\etc\hosts";

    public static (bool Ok, string Log) Install(string installDir, string? extensionId = null, string? password = null)
    {
        var logs = new List<string>();
        try
        {
            Directory.CreateDirectory(installDir);
            string cfgPath = Path.Combine(installDir, "config.json");
            // Use portable config.json inside installDir when writable (keeps K:\ self-contained), else AppData
            try
            {
                string probe = Path.Combine(installDir, ".write_test");
                File.WriteAllText(probe, "1");
                File.Delete(probe);
            }
            catch
            {
                cfgPath = AppConfig.GetDefaultConfigPath();
            }

            var cfg = AppConfig.Load(cfgPath);
            cfg.InstallDir = installDir;
            if (!string.IsNullOrWhiteSpace(extensionId)) cfg.ExtensionId = extensionId.Trim();
            else if (string.IsNullOrWhiteSpace(cfg.ExtensionId)) cfg.ExtensionId = BrandConfig.DefaultExtensionId;
            if (!string.IsNullOrWhiteSpace(password)) cfg.PasswordHash = ContentGuardRules.HashPassword(password);
            cfg.Save(cfgPath);

            // 1. Migrate/disable legacy ContentGuard service & registry (leave C:\Users\user\ContentGuard intact!)
            DisableLegacyContentGuardRegistration(logs);

            // 2. Register Native Messaging Host for Chrome, Brave, Edge
            RegisterNativeMessagingHost(installDir, cfg.ExtensionId, logs);

            // 3. Register Startup Scheduled Task / Run fallback
            RegisterStartup(installDir, logs);

            // 4. Incognito Policy
            if (cfg.BlockIncognito)
            {
                EnforceIncognitoPolicies();
                logs.Add("Disabled Incognito / InPrivate / Private browsing via HKCU policies.");
            }

            // 5. Hosts File Blocking (best effort; requires Admin)
            if (cfg.BlockHostsFile)
            {
                TryApplyHostsBlocklist(installDir, logs);
            }

            return (true, string.Join(Environment.NewLine, logs));
        }
        catch (Exception ex)
        {
            logs.Add("Install error: " + ex.Message);
            return (false, string.Join(Environment.NewLine, logs));
        }
    }

    public static (bool Ok, string Log) Uninstall(string installDir)
    {
        var logs = new List<string>();
        try
        {
            RunQuiet("schtasks", $"/Delete /TN \"{BrandConfig.ScheduledTaskName}\" /F");
            try
            {
                using var run = Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Run", writable: true);
                run?.DeleteValue(BrandConfig.RunRegistryValueName, false);
            }
            catch { }
            logs.Add("Removed startup task and Run entry.");

            foreach (string root in new[]
            {
                $@"Software\Google\Chrome\NativeMessagingHosts\{BrandConfig.NativeHostName}",
                $@"Software\BraveSoftware\Brave-Browser\NativeMessagingHosts\{BrandConfig.NativeHostName}",
                $@"Software\Microsoft\Edge\NativeMessagingHosts\{BrandConfig.NativeHostName}"
            })
            {
                try { Registry.CurrentUser.DeleteSubKeyTree(root, false); } catch { }
            }
            logs.Add("Removed Native Messaging registry entries.");

            RestoreIncognitoPolicies();
            logs.Add("Restored browser private browsing policies.");

            TryRemoveHostsMarkers(BrandConfig.HostsMarkerBegin, BrandConfig.HostsMarkerEnd, logs);

            return (true, string.Join(Environment.NewLine, logs));
        }
        catch (Exception ex)
        {
            logs.Add("Uninstall error: " + ex.Message);
            return (false, string.Join(Environment.NewLine, logs));
        }
    }

    private static void DisableLegacyContentGuardRegistration(List<string> logs)
    {
        try
        {
            RunQuiet("schtasks", $"/Delete /TN \"{BrandConfig.LegacyScheduledTaskName}\" /F");
            using var run = Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Run", writable: true);
            run?.DeleteValue(BrandConfig.LegacyRunRegistryValueName, false);
            foreach (string root in new[]
            {
                $@"Software\Google\Chrome\NativeMessagingHosts\{BrandConfig.LegacyNativeHostName}",
                $@"Software\BraveSoftware\Brave-Browser\NativeMessagingHosts\{BrandConfig.LegacyNativeHostName}"
            })
            {
                try { Registry.CurrentUser.DeleteSubKeyTree(root, false); } catch { }
            }
            foreach (var p in Process.GetProcessesByName("ContentGuard"))
            {
                try { p.Kill(); } catch { }
            }
            TryRemoveHostsMarkers(BrandConfig.LegacyHostsMarkerBegin, BrandConfig.LegacyHostsMarkerEnd, logs);
            logs.Add("Migrated & disabled legacy ContentGuard task/registry (original backup folder kept untouched).");
        }
        catch { }
    }

    public static void RegisterNativeMessagingHost(string installDir, string extensionId, List<string>? logs = null)
    {
        string exePath = Path.Combine(installDir, "SaveGaze.exe");
        string batPath = Path.Combine(installDir, "savegaze-native-host.cmd");
        File.WriteAllText(batPath, $"@echo off\r\n\"{exePath}\" --native-host %*\r\n");

        var allowed = new List<string> { $"chrome-extension://{BrandConfig.DefaultExtensionId}/" };
        if (!string.IsNullOrWhiteSpace(extensionId) &&
            !extensionId.Equals(BrandConfig.DefaultExtensionId, StringComparison.OrdinalIgnoreCase))
        {
            allowed.Add($"chrome-extension://{extensionId}/");
        }

        string manifestPath = Path.Combine(installDir, $"{BrandConfig.NativeHostName}.json");
        var manifest = new
        {
            name = BrandConfig.NativeHostName,
            description = $"{BrandConfig.DesktopDisplayName} Native Messaging Host",
            path = batPath,
            type = "stdio",
            allowed_origins = allowed
        };
        File.WriteAllText(manifestPath, JsonSerializer.Serialize(manifest, new JsonSerializerOptions { WriteIndented = true }));

        foreach (string regRoot in new[]
        {
            $@"Software\Google\Chrome\NativeMessagingHosts\{BrandConfig.NativeHostName}",
            $@"Software\BraveSoftware\Brave-Browser\NativeMessagingHosts\{BrandConfig.NativeHostName}",
            $@"Software\Microsoft\Edge\NativeMessagingHosts\{BrandConfig.NativeHostName}"
        })
        {
            using var key = Registry.CurrentUser.CreateSubKey(regRoot);
            key?.SetValue("", manifestPath, RegistryValueKind.String);
        }
        logs?.Add($"Registered Native Messaging host ({BrandConfig.NativeHostName}) for Chrome, Brave, and Edge.");
    }

    private static void RegisterStartup(string installDir, List<string> logs)
    {
        string exePath = Path.Combine(installDir, "SaveGaze.exe");
        int code = RunQuiet("schtasks",
            $"/Create /TN \"{BrandConfig.ScheduledTaskName}\" /TR \"\\\"{exePath}\\\" --minimized\" /SC ONLOGON /RL HIGHEST /F");
        if (code == 0)
        {
            logs.Add($"Created Windows Scheduled Task: {BrandConfig.ScheduledTaskName}");
        }
        else
        {
            using var run = Registry.CurrentUser.CreateSubKey(@"Software\Microsoft\Windows\CurrentVersion\Run");
            run?.SetValue(BrandConfig.RunRegistryValueName, $"\"{exePath}\" --minimized", RegistryValueKind.String);
            logs.Add("Added HKCU Run startup entry (non-admin fallback).");
        }
    }

    public static void EnforceIncognitoPolicies()
    {
        try
        {
            using (var k = Registry.CurrentUser.CreateSubKey(@"Software\Policies\Google\Chrome"))
                k?.SetValue("IncognitoModeAvailability", 1, RegistryValueKind.DWord);
            using (var k = Registry.CurrentUser.CreateSubKey(@"Software\Policies\BraveSoftware\Brave"))
                k?.SetValue("IncognitoModeAvailability", 1, RegistryValueKind.DWord);
            using (var k = Registry.CurrentUser.CreateSubKey(@"Software\Policies\Microsoft\Edge"))
                k?.SetValue("InPrivateModeAvailability", 1, RegistryValueKind.DWord);
            using (var k = Registry.CurrentUser.CreateSubKey(@"Software\Policies\Mozilla\Firefox"))
                k?.SetValue("DisablePrivateBrowsing", 1, RegistryValueKind.DWord);
        }
        catch { }
    }

    public static void RestoreIncognitoPolicies()
    {
        try
        {
            using (var k = Registry.CurrentUser.OpenSubKey(@"Software\Policies\Google\Chrome", true))
                k?.DeleteValue("IncognitoModeAvailability", false);
            using (var k = Registry.CurrentUser.OpenSubKey(@"Software\Policies\BraveSoftware\Brave", true))
                k?.DeleteValue("IncognitoModeAvailability", false);
            using (var k = Registry.CurrentUser.OpenSubKey(@"Software\Policies\Microsoft\Edge", true))
                k?.DeleteValue("InPrivateModeAvailability", false);
            using (var k = Registry.CurrentUser.OpenSubKey(@"Software\Policies\Mozilla\Firefox", true))
                k?.DeleteValue("DisablePrivateBrowsing", false);
        }
        catch { }
    }

    public static void TryApplyHostsBlocklist(string installDir, List<string> logs)
    {
        try
        {
            string listPath = Path.Combine(installDir, "assets", "hosts-blocklist.txt");
            if (!File.Exists(listPath)) listPath = Path.Combine(AppContext.BaseDirectory, "assets", "hosts-blocklist.txt");
            if (!File.Exists(listPath)) return;

            string hosts = File.ReadAllText(HostsPath);
            if (hosts.Contains(BrandConfig.HostsMarkerBegin))
            {
                logs.Add("Hosts file already contains SaveGaze entries.");
                return;
            }

            var domains = File.ReadAllLines(listPath)
                .Select(l => l.Trim())
                .Where(l => l.Length > 0 && !l.StartsWith('#'))
                .Distinct(StringComparer.OrdinalIgnoreCase)
                .ToList();

            var lines = new List<string> { "", BrandConfig.HostsMarkerBegin };
            lines.AddRange(domains.Select(d => $"127.0.0.1 {d}"));
            lines.Add(BrandConfig.HostsMarkerEnd);
            lines.Add("");

            File.AppendAllText(HostsPath, string.Join(Environment.NewLine, lines));
            logs.Add($"Added {domains.Count} adult domains to Windows hosts file.");
        }
        catch
        {
            logs.Add("Skipped hosts file write (requires Administrator privileges).");
        }
    }

    private static void TryRemoveHostsMarkers(string beginMarker, string endMarker, List<string> logs)
    {
        try
        {
            if (!File.Exists(HostsPath)) return;
            string hosts = File.ReadAllText(HostsPath);
            int s = hosts.IndexOf(beginMarker, StringComparison.Ordinal);
            int e = hosts.IndexOf(endMarker, StringComparison.Ordinal);
            if (s < 0 || e < 0 || e < s) return;
            string cleaned = (hosts[..s] + hosts[(e + endMarker.Length)..]).TrimEnd() + Environment.NewLine;
            File.WriteAllText(HostsPath, cleaned);
            logs.Add("Cleaned hosts block markers.");
        }
        catch
        {
            logs.Add("Could not modify hosts file (non-admin).");
        }
    }

    private static int RunQuiet(string exe, string args)
    {
        try
        {
            using var p = Process.Start(new ProcessStartInfo
            {
                FileName = exe,
                Arguments = args,
                CreateNoWindow = true,
                UseShellExecute = false,
                RedirectStandardOutput = true,
                RedirectStandardError = true
            });
            p?.WaitForExit(8000);
            return p?.ExitCode ?? -1;
        }
        catch { return -1; }
    }
}
