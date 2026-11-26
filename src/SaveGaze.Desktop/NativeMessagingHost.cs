using System.IO;
using System.IO.Pipes;
using System.Text;
using System.Text.Json;
using SaveGaze.Core;

namespace SaveGaze.Desktop;

/// <summary>
/// Chrome/Brave/Edge Native Messaging Host over stdin/stdout (4-byte little-endian length prefix + UTF-8 JSON).
/// Also signals running SaveGaze desktop instance over Named Pipe when blocks/settings change.
/// </summary>
public static class NativeMessagingHost
{
    public static int RunLoop()
    {
        using var stdin = Console.OpenStandardInput();
        using var stdout = Console.OpenStandardOutput();

        while (true)
        {
            string? msgJson = ReadFrame(stdin);
            if (msgJson == null) return 0;

            string responseJson;
            try
            {
                responseJson = HandleMessage(msgJson);
            }
            catch (Exception ex)
            {
                responseJson = JsonSerializer.Serialize(new { status = "error", error = ex.Message });
            }

            WriteFrame(stdout, responseJson);
        }
    }

    private static string? ReadFrame(Stream stdin)
    {
        byte[] lenBuf = new byte[4];
        int read = ReadExact(stdin, lenBuf, 0, 4);
        if (read == 0) return null;
        if (read < 4) return null;

        int msgLen = BitConverter.ToInt32(lenBuf, 0);
        if (msgLen <= 0 || msgLen > 2 * 1024 * 1024) return null;

        byte[] payload = new byte[msgLen];
        int payloadRead = ReadExact(stdin, payload, 0, msgLen);
        if (payloadRead < msgLen) return null;

        return Encoding.UTF8.GetString(payload);
    }

    private static int ReadExact(Stream s, byte[] buf, int offset, int count)
    {
        int total = 0;
        while (total < count)
        {
            int n = s.Read(buf, offset + total, count - total);
            if (n <= 0) return total;
            total += n;
        }
        return total;
    }

    private static void WriteFrame(Stream stdout, string json)
    {
        byte[] payload = Encoding.UTF8.GetBytes(json);
        byte[] len = BitConverter.GetBytes(payload.Length);
        stdout.Write(len, 0, 4);
        stdout.Write(payload, 0, payload.Length);
        stdout.Flush();
    }

    private static string HandleMessage(string rawJson)
    {
        using var doc = JsonDocument.Parse(rawJson);
        var root = doc.RootElement;
        string cmd = root.TryGetProperty("command", out var c) ? (c.GetString() ?? "")
            : root.TryGetProperty("action", out var a) ? (a.GetString() ?? "")
            : "";

        JsonElement data = root.TryGetProperty("data", out var d) ? d : root;
        var cfg = AppConfig.Load();

        switch (cmd)
        {
            case "ping":
                return JsonSerializer.Serialize(new
                {
                    status = "ok",
                    appName = BrandConfig.AppName,
                    version = BrandConfig.AppVersion,
                    protectionEnabled = cfg.ProtectionEnabled
                });

            case "check_status":
            case "get_config":
                return JsonSerializer.Serialize(new
                {
                    status = "ok",
                    version = BrandConfig.AppVersion,
                    hasPassword = !string.IsNullOrWhiteSpace(cfg.PasswordHash),
                    config = new
                    {
                        standardBlocking = cfg.KeywordBlockerEnabled || cfg.WebsiteBlockerEnabled,
                        websiteBlocker = cfg.WebsiteBlockerEnabled,
                        keywordBlocker = cfg.KeywordBlockerEnabled,
                        blockIncognito = cfg.BlockIncognito,
                        crossBlockLimit = cfg.CrossBlockLockoutEnabled,
                        intimateGuardEnabled = cfg.IntimateGuardEnabled,
                        customKeywords = cfg.CustomKeywords,
                        customWebsites = cfg.CustomWebsites,
                        customApps = cfg.CustomApps,
                        lockoutUntilMs = cfg.LockoutUntilMs
                    }
                });

            case "sync_lists":
            case "update_config":
                if (data.TryGetProperty("customKeywords", out var kws) && kws.ValueKind == JsonValueKind.Array)
                    cfg.CustomKeywords = kws.EnumerateArray().Select(x => x.GetString() ?? "").ToList();
                if (data.TryGetProperty("customWebsites", out var ws) && ws.ValueKind == JsonValueKind.Array)
                    cfg.CustomWebsites = ws.EnumerateArray().Select(x => x.GetString() ?? "").ToList();
                if (data.TryGetProperty("customApps", out var apps) && apps.ValueKind == JsonValueKind.Array)
                    cfg.CustomApps = apps.EnumerateArray().Select(x => x.GetString() ?? "").ToList();
                if (data.TryGetProperty("blockIncognito", out var bi) && bi.ValueKind is JsonValueKind.True or JsonValueKind.False)
                    cfg.BlockIncognito = bi.GetBoolean();
                if (data.TryGetProperty("crossBlockLimit", out var cb) && cb.ValueKind is JsonValueKind.True or JsonValueKind.False)
                    cfg.CrossBlockLockoutEnabled = cb.GetBoolean();
                if (data.TryGetProperty("standardBlocking", out var sb) && sb.ValueKind is JsonValueKind.True or JsonValueKind.False)
                {
                    cfg.KeywordBlockerEnabled = sb.GetBoolean();
                    cfg.WebsiteBlockerEnabled = sb.GetBoolean();
                }
                cfg.Save();
                NotifyDesktopService("reload_config");
                return JsonSerializer.Serialize(new { status = "ok" });

            case "verify_password":
                string pw = data.TryGetProperty("password", out var pwEl) ? (pwEl.GetString() ?? "") : "";
                return JsonSerializer.Serialize(new
                {
                    status = "ok",
                    valid = ContentGuardRules.VerifyPassword(pw, cfg.PasswordHash)
                });

            case "set_password":
                string oldPw = data.TryGetProperty("oldPassword", out var op) ? (op.GetString() ?? "") : "";
                string newPw = data.TryGetProperty("newPassword", out var np) ? (np.GetString() ?? "") : "";
                if (!string.IsNullOrWhiteSpace(cfg.PasswordHash) && !ContentGuardRules.VerifyPassword(oldPw, cfg.PasswordHash))
                    return JsonSerializer.Serialize(new { status = "error", error = "Invalid current password" });
                cfg.PasswordHash = string.IsNullOrEmpty(newPw) ? "" : ContentGuardRules.HashPassword(newPw);
                cfg.Save();
                NotifyDesktopService("reload_config");
                return JsonSerializer.Serialize(new { status = "ok" });

            case "verify_challenge":
                string actionName = data.TryGetProperty("actionName", out var an) ? (an.GetString() ?? "Protection Feature") : "Protection Feature";
                bool passed = VerificationDialogForm.VerifyProtectionChange(actionName, cfg.PasswordHash, null, requireSentence: true);
                return JsonSerializer.Serialize(new { status = "ok", passed });

            case "disable_incognito":
                InstallerEngine.EnforceIncognitoPolicies();
                return JsonSerializer.Serialize(new { status = "ok" });

            case "record_block":
            case "trigger_lockout":
                string reason = data.TryGetProperty("reason", out var rEl) ? (rEl.GetString() ?? "Blocked Content") : "Blocked Content";
                string detail = data.TryGetProperty("detail", out var dEl) ? (dEl.GetString() ?? "") : "";
                long nowMs = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
                var upd = ContentGuardRules.RecordCrossBlockEvent(cfg.BlockTimestampsMs, nowMs, cfg.CrossBlockLockoutEnabled);
                cfg.BlockTimestampsMs = upd.Timestamps.ToList();
                cfg.TotalBlockedCount++;
                if (upd.TriggeredLockout || cmd == "trigger_lockout")
                {
                    cfg.LockoutUntilMs = upd.TriggeredLockout ? upd.LockoutUntilMs : nowMs + ContentGuardRules.LockoutDurationMs;
                }
                cfg.Save();
                NotifyDesktopService("reload_config");
                return JsonSerializer.Serialize(new
                {
                    status = "ok",
                    triggeredLockout = cfg.LockoutUntilMs > nowMs,
                    lockoutUntilMs = cfg.LockoutUntilMs,
                    reason,
                    detail
                });

            case "intimate_caption_hit":
                string caption = data.TryGetProperty("caption", out var cap) ? (cap.GetString() ?? "[caption]") : "[caption]";
                NotifyDesktopService($"intimate_caption:{caption}");
                return JsonSerializer.Serialize(new { status = "ok" });

            case "enforce_extension":
            case "extension_uninstalled":
                return JsonSerializer.Serialize(new { status = "ok" });

            default:
                return JsonSerializer.Serialize(new { status = "error", error = $"Unknown command: {cmd}" });
        }
    }

    public static void NotifyDesktopService(string command)
    {
        try
        {
            using var client = new NamedPipeClientStream(".", BrandConfig.PipeName, PipeDirection.Out);
            client.Connect(350);
            byte[] bytes = Encoding.UTF8.GetBytes(command + "\n");
            client.Write(bytes, 0, bytes.Length);
            client.Flush();
        }
        catch { }
    }
}
