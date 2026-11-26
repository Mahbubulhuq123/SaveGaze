// SaveGaze Content Script
// - Live typing single-word blocked keyword guard inside search bars (YouTube, Google, Facebook, Instagram, etc.)
// - URL, domain & page content keyword guard
// - Closed-caption / subtitle intimate guard forwarded to SaveGaze PC native host
// - Unclosable lockout overlay

(function () {
  "use strict";

  if (window.__saveGazeInjected) return;
  window.__saveGazeInjected = true;

  const BRAND = window.__saveGazeBrand || { appName: "SaveGaze" };
  const CAPTION_REGEX = /[\[(]\s*(?:woman|girl|female|man|couple|both)?\s*(?:moaning|moans|groaning|groans|panting|heavy breathing|kissing|making out|sexual\s+\w+|orgasm)\s*[\])]/i;

  let settings = null;
  let pageBlocked = false;
  let lastCaptionNotifyMs = 0;

  async function loadSettings() {
    return new Promise((resolve) => {
      chrome.runtime.sendMessage({ type: "GET_SETTINGS" }, (response) => {
        settings = response?.settings || {
          standardBlocking: true,
          crossBlockLimit: true,
          customKeywords: [],
          customWebsites: []
        };
        resolve(settings);
      });
    });
  }

  async function checkLockout() {
    const domain = window.location.hostname;
    return new Promise((resolve) => {
      chrome.runtime.sendMessage({ type: "CHECK_LOCKOUT", domain }, (response) => {
        resolve(response || { locked: false });
      });
    });
  }

  function escapeRegex(str) {
    return str.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
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

    let decoded = text;
    try {
      decoded = decodeURIComponent(text.replace(/\+/g, " "));
    } catch {}

    const parts = trimmedKw.split(/[\s+_\-]+/).filter(Boolean);
    if (parts.length === 0) return false;

    const patternStr = "(?<![a-zA-Z0-9])" + parts.map(escapeRegex).join("[\\s+_\\-]+") + "(?![a-zA-Z0-9])";
    return new RegExp(patternStr, "i").test(decoded);
  }

  function findBlockedMatchInText(text, includeThresholdForDefault) {
    if (!settings?.standardBlocking || !text) return null;

    const domains = [...(window.__saveGazeDomains || []), ...(settings.customWebsites || [])];
    const lower = text.toLowerCase();
    for (const d of domains) {
      const clean = normalizeHost(d);
      if (clean && clean.length >= 5 && matchesWholeWord(lower, clean)) {
        return `Blocked domain: ${clean}`;
      }
    }

    for (const kw of settings.customKeywords || []) {
      if (matchesWholeWord(lower, kw)) {
        return `Custom blocked keyword: ${kw}`;
      }
    }

    const defaultKws = window.__saveGazeKeywords || [];
    if (!includeThresholdForDefault) {
      for (const kw of defaultKws) {
        if (matchesWholeWord(lower, kw)) {
          return `Blocked keyword: ${kw}`;
        }
      }
    } else {
      let hits = 0;
      for (const kw of defaultKws) {
        if (matchesWholeWord(lower, kw)) {
          hits++;
          if (hits >= 3) return `Multiple adult keywords detected (${hits} matches)`;
        }
      }
    }
    return null;
  }

  function checkDomain() {
    if (!settings?.standardBlocking) return;
    const domains = [...(window.__saveGazeDomains || []), ...(settings.customWebsites || [])];
    const host = normalizeHost(window.location.hostname);
    if (!host) return;
    for (const d of domains) {
      const clean = normalizeHost(d);
      if (clean && (host === clean || host.endsWith("." + clean))) {
        blockPage(`Blocked domain: ${clean}`);
        return;
      }
    }
  }

  function scanPageText() {
    if (!settings?.standardBlocking || pageBlocked) return;
    const urlReason = findBlockedMatchInText(window.location.href, false);
    if (urlReason) {
      blockPage(urlReason);
      return;
    }

    const titleReason = findBlockedMatchInText(document.title || "", false);
    if (titleReason) {
      blockPage(titleReason);
      return;
    }

    const bodyText = (document.body?.innerText || "").substring(0, 50000);
    const bodyReason = findBlockedMatchInText(bodyText, true);
    if (bodyReason) {
      blockPage(bodyReason);
    }
  }

  function blockPage(reason) {
    if (pageBlocked) return;
    pageBlocked = true;
    chrome.runtime.sendMessage({
      type: "CONTENT_BLOCKED",
      reason,
      url: window.location.href
    });
  }

  function isSearchOrEditableElement(el) {
    if (!el || !(el instanceof HTMLElement)) return false;
    if (el.tagName === "INPUT") {
      const t = (el.getAttribute("type") || "text").toLowerCase();
      return t === "text" || t === "search" || t === "url";
    }
    if (el.tagName === "TEXTAREA") return true;
    if (el.isContentEditable) return true;
    const role = (el.getAttribute("role") || "").toLowerCase();
    return role === "searchbox" || role === "combobox" || role === "textbox";
  }

  function readElementInputText(el) {
    if (!el) return "";
    if (typeof el.value === "string") return el.value;
    return el.innerText || el.textContent || "";
  }

  function setupLiveSearchInputGuard() {
    let debounceId = null;
    const inspectTarget = (target) => {
      if (!settings?.standardBlocking || pageBlocked) return;
      if (!isSearchOrEditableElement(target)) return;
      const val = readElementInputText(target);
      if (!val || val.length > 300) return;

      const hit = findBlockedMatchInText(val, false);
      if (hit) {
        if ("value" in target) target.value = "";
        else target.textContent = "";
        blockPage(`Blocked search input (${hit})`);
      }
    };

    document.addEventListener(
      "input",
      (e) => {
        const target = e.target;
        clearTimeout(debounceId);
        debounceId = setTimeout(() => inspectTarget(target), 80);
      },
      true
    );

    document.addEventListener(
      "keydown",
      (e) => {
        if (e.key === "Enter") inspectTarget(e.target);
      },
      true
    );
  }

  function setupCaptionObserver() {
    const checkCaptions = () => {
      const now = Date.now();
      if (now - lastCaptionNotifyMs < 2500) return;

      const nodes = document.querySelectorAll(
        ".ytp-caption-segment, .captions-text, .player-timedtext-text-container, [class*='subtitle'], [class*='caption']"
      );
      for (const node of nodes) {
        const txt = node.textContent || "";
        const m = txt.match(CAPTION_REGEX);
        if (m) {
          lastCaptionNotifyMs = now;
          chrome.runtime.sendMessage({ type: "INTIMATE_CAPTION_HIT", caption: m[0] });
          break;
        }
      }
    };

    const observer = new MutationObserver(() => checkCaptions());
    if (document.body || document.documentElement) {
      observer.observe(document.body || document.documentElement, {
        childList: true,
        subtree: true,
        characterData: true
      });
    }
  }

  function showLockoutOverlay(until) {
    if (document.getElementById("sg-lockout-overlay")) return;
    const overlay = document.createElement("div");
    overlay.id = "sg-lockout-overlay";
    overlay.className = "cg-lockout-overlay";

    overlay.innerHTML = `
      <div class="cg-lockout-content">
        <div class="cg-lockout-icon">&#128737;</div>
        <h1>${BRAND.appName} Lockout</h1>
        <p>You are temporarily locked out due to repeated blocked content attempts.</p>
        <div class="cg-lockout-timer">5:00</div>
        <p class="cg-lockout-sub">Please wait for the cooldown period to finish.</p>
      </div>
    `;
    document.documentElement.appendChild(overlay);

    const updateTimer = () => {
      const remaining = until - Date.now();
      if (remaining <= 0) {
        overlay.remove();
        return;
      }
      const mins = Math.floor(remaining / 60000);
      const secs = Math.floor((remaining % 60000) / 1000);
      const timerEl = overlay.querySelector(".cg-lockout-timer");
      if (timerEl) timerEl.textContent = `${mins}:${secs.toString().padStart(2, "0")}`;
      requestAnimationFrame(updateTimer);
    };
    updateTimer();
  }

  async function init() {
    await loadSettings();
    const lockoutStatus = await checkLockout();
    if (lockoutStatus.locked) {
      showLockoutOverlay(lockoutStatus.until);
      return;
    }

    checkDomain();
    if (!pageBlocked) scanPageText();
    if (!pageBlocked) {
      setupLiveSearchInputGuard();
      setupCaptionObserver();
    }
  }

  init();
})();
