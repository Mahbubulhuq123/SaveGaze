importScripts("brand.js", "keywords.js");

const BRAND = self.__saveGazeBrand;
const NATIVE_HOST = BRAND.nativeHost;
const LOCKOUT_DURATION_MS = 5 * 60 * 1000;
const CROSS_BLOCK_LIMIT = 5;
const CROSS_BLOCK_WINDOW_MS = 10 * 60 * 1000;

const DEFAULT_SETTINGS = {
  standardBlocking: true,
  blockIncognito: true,
  crossBlockLimit: true,
  passwordHash: null,
  customKeywords: [],
  customWebsites: [],
  customApps: []
};

function escapeRegex(s) {
  return s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

function normalizeHost(raw) {
  let s = (raw || "").trim().toLowerCase();
  s = s.replace(/^https?:\/\//, "").split(/[/?#]/)[0].split(":")[0];
  return s.replace(/^www\./, "").replace(/\.+$/, "");
}

function matchesWholeWord(text, keyword) {
  if (!text || !keyword) return false;
  const trimmedKw = keyword.trim();
  if (!trimmedKw) return false;

  let decodedText = text;
  try {
    decodedText = decodeURIComponent(text.replace(/\+/g, " "));
  } catch {}

  const parts = trimmedKw.split(/[\s+_\-]+/).filter(Boolean);
  if (parts.length === 0) return false;

  const patternStr = "(?<![a-zA-Z0-9])" + parts.map(escapeRegex).join("[\\s+_\\-]+") + "(?![a-zA-Z0-9])";
  return new RegExp(patternStr, "i").test(decodedText);
}

function isDomainMatch(urlOrHost, domains) {
  let host = "";
  try {
    host = normalizeHost(new URL(urlOrHost).hostname);
  } catch {
    host = normalizeHost(urlOrHost);
  }
  if (!host || !host.includes(".")) return null;
  for (const d of domains) {
    const clean = normalizeHost(d);
    if (!clean) continue;
    if (host === clean || host.endsWith("." + clean)) return clean;
  }
  return null;
}

async function getMergedSettings() {
  const { settings = DEFAULT_SETTINGS } = await chrome.storage.sync.get("settings");
  try {
    const resp = await chrome.runtime.sendNativeMessage(NATIVE_HOST, { action: "check_status" });
    if (resp?.status === "ok" && resp.config) {
      const merged = {
        ...DEFAULT_SETTINGS,
        ...settings,
        standardBlocking: resp.config.standardBlocking ?? settings.standardBlocking,
        blockIncognito: resp.config.blockIncognito ?? settings.blockIncognito,
        crossBlockLimit: resp.config.crossBlockLimit ?? settings.crossBlockLimit,
        customKeywords: resp.config.customKeywords ?? settings.customKeywords,
        customWebsites: resp.config.customWebsites ?? settings.customWebsites,
        customApps: resp.config.customApps ?? settings.customApps
      };
      await chrome.storage.sync.set({ settings: merged });
      if (resp.config.lockoutUntilMs && resp.config.lockoutUntilMs > Date.now()) {
        await chrome.storage.local.set({ globalLockoutUntil: resp.config.lockoutUntilMs });
      }
      return { settings: merged, nativeConnected: true, nativeHasPassword: !!resp.hasPassword };
    }
  } catch {}
  return { settings: { ...DEFAULT_SETTINGS, ...settings }, nativeConnected: false, nativeHasPassword: false };
}

async function syncSettingsToNative(settings) {
  try {
    await chrome.runtime.sendNativeMessage(NATIVE_HOST, {
      action: "sync_lists",
      data: {
        standardBlocking: settings.standardBlocking,
        blockIncognito: settings.blockIncognito,
        crossBlockLimit: settings.crossBlockLimit,
        customKeywords: settings.customKeywords || [],
        customWebsites: settings.customWebsites || [],
        customApps: settings.customApps || []
      }
    });
  } catch {}
}

chrome.runtime.onInstalled.addListener(async (details) => {
  if (details.reason === "install") {
    await chrome.storage.sync.set({ settings: DEFAULT_SETTINGS });
    await chrome.storage.local.set({ blockLog: {}, lockouts: {}, globalLockoutUntil: 0 });
  }
  await getMergedSettings();
});

chrome.tabs.onUpdated.addListener(async (tabId, changeInfo, tab) => {
  const targetUrl = changeInfo.url || tab.url;
  if (!targetUrl) return;

  const lowerUrl = targetUrl.toLowerCase();
  if (
    lowerUrl.startsWith("chrome://") ||
    lowerUrl.startsWith("chrome-extension://") ||
    lowerUrl.startsWith("brave://") ||
    lowerUrl.startsWith("edge://") ||
    lowerUrl.startsWith("about:")
  ) {
    return;
  }

  const { settings } = await getMergedSettings();
  if (!settings.standardBlocking) return;

  const allDomains = [...(self.__saveGazeDomains || []), ...(settings.customWebsites || [])];
  const matchedDomain = isDomainMatch(targetUrl, allDomains);
  if (matchedDomain) {
    await recordBlockAndRedirect(tabId, targetUrl, `Blocked domain: ${matchedDomain}`);
    return;
  }

  const customKws = settings.customKeywords || [];
  for (const kw of customKws) {
    if (matchesWholeWord(lowerUrl, kw)) {
      await recordBlockAndRedirect(tabId, targetUrl, `Custom blocked keyword: ${kw}`);
      return;
    }
  }
  for (const kw of self.__saveGazeKeywords || []) {
    if (matchesWholeWord(lowerUrl, kw)) {
      await recordBlockAndRedirect(tabId, targetUrl, `Blocked keyword in URL: ${kw}`);
      return;
    }
  }
});

async function recordBlockAndRedirect(tabId, blockedUrl, reason) {
  const { settings } = await getMergedSettings();
  let domain = blockedUrl;
  try {
    domain = new URL(blockedUrl).hostname || blockedUrl;
  } catch {}

  let triggeredLockout = false;
  let lockoutUntil = 0;

  try {
    const resp = await chrome.runtime.sendNativeMessage(NATIVE_HOST, {
      action: "record_block",
      data: { reason, detail: domain }
    });
    if (resp?.triggeredLockout) {
      triggeredLockout = true;
      lockoutUntil = resp.lockoutUntilMs;
    }
  } catch {}

  if (!triggeredLockout && settings.crossBlockLimit) {
    const { blockLog = {} } = await chrome.storage.local.get("blockLog");
    const now = Date.now();
    const list = (blockLog.__all || []).filter((t) => now - t < CROSS_BLOCK_WINDOW_MS);
    list.push(now);
    blockLog.__all = list;

    if (list.length >= CROSS_BLOCK_LIMIT) {
      blockLog.__all = [];
      lockoutUntil = now + LOCKOUT_DURATION_MS;
      triggeredLockout = true;
      const { lockouts = {} } = await chrome.storage.local.get("lockouts");
      lockouts[domain] = lockoutUntil;
      await chrome.storage.local.set({ lockouts, globalLockoutUntil: lockoutUntil });
    }
    await chrome.storage.local.set({ blockLog });
  }

  if (triggeredLockout && lockoutUntil > Date.now()) {
    const lockoutUrl = chrome.runtime.getURL(
      `lockout.html?domain=${encodeURIComponent(domain)}&until=${lockoutUntil}`
    );
    await chrome.tabs.update(tabId, { url: lockoutUrl });
    return;
  }

  const blockedPageUrl = chrome.runtime.getURL(
    `blocked.html?url=${encodeURIComponent(blockedUrl)}&reason=${encodeURIComponent(reason)}`
  );
  await chrome.tabs.update(tabId, { url: blockedPageUrl });
}

chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  (async () => {
    try {
      switch (message.type) {
        case "PING":
          sendResponse({ ok: true, version: chrome.runtime.getManifest().version });
          break;

        case "GET_SETTINGS": {
          const data = await getMergedSettings();
          sendResponse(data);
          break;
        }

        case "SAVE_SETTINGS": {
          await chrome.storage.sync.set({ settings: message.settings });
          await syncSettingsToNative(message.settings);
          sendResponse({ ok: true });
          break;
        }

        case "CHECK_LOCKOUT": {
          const { lockouts = {}, globalLockoutUntil = 0 } = await chrome.storage.local.get([
            "lockouts",
            "globalLockoutUntil"
          ]);
          const now = Date.now();
          const until = Math.max(lockouts[message.domain] || 0, globalLockoutUntil || 0);
          if (until > now) {
            sendResponse({ locked: true, until });
          } else {
            if (lockouts[message.domain]) {
              delete lockouts[message.domain];
              await chrome.storage.local.set({ lockouts });
            }
            sendResponse({ locked: false });
          }
          break;
        }

        case "CONTENT_BLOCKED": {
          if (sender.tab?.id) {
            await recordBlockAndRedirect(sender.tab.id, message.url || sender.tab.url || "", message.reason);
          }
          sendResponse({ ok: true });
          break;
        }

        case "INTIMATE_CAPTION_HIT": {
          try {
            await chrome.runtime.sendNativeMessage(NATIVE_HOST, {
              action: "intimate_caption_hit",
              data: { caption: message.caption || "[caption]" }
            });
          } catch {}
          sendResponse({ ok: true });
          break;
        }

        case "VERIFY_PASSWORD": {
          try {
            const resp = await chrome.runtime.sendNativeMessage(NATIVE_HOST, {
              action: "verify_password",
              data: { password: message.password }
            });
            if (resp?.status === "ok") {
              sendResponse({ valid: !!resp.valid });
              break;
            }
          } catch {}

          const { settings = DEFAULT_SETTINGS } = await chrome.storage.sync.get("settings");
          if (!settings.passwordHash) {
            sendResponse({ valid: true });
          } else {
            const h1 = await hashPassword(message.password, true);
            const h2 = await hashPassword(message.password, false);
            sendResponse({ valid: h1 === settings.passwordHash || h2 === settings.passwordHash });
          }
          break;
        }

        case "SET_PASSWORD": {
          const { settings: s = DEFAULT_SETTINGS } = await chrome.storage.sync.get("settings");
          s.passwordHash = message.password ? await hashPassword(message.password, true) : null;
          await chrome.storage.sync.set({ settings: s });
          try {
            await chrome.runtime.sendNativeMessage(NATIVE_HOST, {
              action: "set_password",
              data: { oldPassword: message.oldPassword || "", newPassword: message.password || "" }
            });
          } catch {}
          sendResponse({ ok: true });
          break;
        }

        case "NATIVE_DISABLE_INCOGNITO": {
          try {
            const resp = await chrome.runtime.sendNativeMessage(NATIVE_HOST, { action: "disable_incognito" });
            sendResponse({ ok: true, response: resp });
          } catch (e) {
            sendResponse({ ok: false, error: e.message });
          }
          break;
        }

        case "NATIVE_CHECK_STATUS": {
          try {
            const resp = await chrome.runtime.sendNativeMessage(NATIVE_HOST, { action: "check_status" });
            sendResponse({ ok: true, response: resp });
          } catch (e) {
            sendResponse({ ok: false, error: e.message });
          }
          break;
        }

        default:
          sendResponse({ error: "Unknown message type" });
      }
    } catch (err) {
      sendResponse({ error: err.message });
    }
  })();
  return true;
});

async function hashPassword(password, prefixedSalt) {
  const payload = prefixedSalt
    ? BRAND.passwordSaltPrefix + password
    : password + BRAND.legacyPasswordSaltSuffix;
  const data = new TextEncoder().encode(payload);
  const hashBuffer = await crypto.subtle.digest("SHA-256", data);
  return Array.from(new Uint8Array(hashBuffer))
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
}

chrome.alarms.create("cleanup-lockouts", { periodInMinutes: 1 });
chrome.alarms.onAlarm.addListener(async (alarm) => {
  if (alarm.name === "cleanup-lockouts") {
    const { lockouts = {}, globalLockoutUntil = 0 } = await chrome.storage.local.get([
      "lockouts",
      "globalLockoutUntil"
    ]);
    const now = Date.now();
    let changed = false;
    for (const domain of Object.keys(lockouts)) {
      if (lockouts[domain] <= now) {
        delete lockouts[domain];
        changed = true;
      }
    }
    if (changed) await chrome.storage.local.set({ lockouts });
    if (globalLockoutUntil && globalLockoutUntil <= now) {
      await chrome.storage.local.set({ globalLockoutUntil: 0 });
    }
  }
});

chrome.management.onUninstalled.addListener(async (id) => {
  try {
    await chrome.runtime.sendNativeMessage(NATIVE_HOST, {
      action: "extension_uninstalled",
      extensionId: id
    });
  } catch {}
});

chrome.runtime.onStartup.addListener(async () => {
  await getMergedSettings();
});
