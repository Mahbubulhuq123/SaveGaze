"use strict";

const BRAND = window.__saveGazeBrand;
document.getElementById("brand-title").textContent = BRAND.fullName;
document.getElementById("brand-version").textContent = "v" + BRAND.version;
document.getElementById("brand-footer").textContent = BRAND.tagline;

const defaultSettings = {
  standardBlocking: true,
  blockIncognito: true,
  crossBlockLimit: true,
  passwordHash: null,
  customKeywords: [],
  customWebsites: [],
  customApps: []
};

const toggles = {
  standardBlocking: document.getElementById("toggle-standard"),
  blockIncognito: document.getElementById("toggle-incognito"),
  crossBlockLimit: document.getElementById("toggle-crossblock")
};

const listInputs = {
  keywords: { input: document.getElementById("kw-input"), add: document.getElementById("kw-add"), list: document.getElementById("kw-list"), key: "customKeywords" },
  websites: { input: document.getElementById("ws-input"), add: document.getElementById("ws-add"), list: document.getElementById("ws-list"), key: "customWebsites" },
  apps:     { input: document.getElementById("app-input"), add: document.getElementById("app-add"), list: document.getElementById("app-list"), key: "customApps" }
};

const pwCurrent = document.getElementById("pw-current");
const pwNew = document.getElementById("pw-new");
const pwSet = document.getElementById("pw-set");
const pwMsg = document.getElementById("pw-msg");
const statusEl = document.getElementById("cg-status");
const nativeStatusEl = document.getElementById("native-status");

let settings = {};
let hasRemotePassword = false;
let activeTimer = null;

function sendMessage(msg) {
  return new Promise((resolve, reject) => {
    chrome.runtime.sendMessage(msg, (response) => {
      if (chrome.runtime.lastError) reject(new Error(chrome.runtime.lastError.message));
      else resolve(response);
    });
  });
}

function showMsg(el, text, type = "info") {
  el.textContent = text;
  el.className = "cg-msg " + type;
  el.style.display = "block";
  setTimeout(() => { el.style.display = "none"; }, 4000);
}

async function loadSettings() {
  try {
    const res = await sendMessage({ type: "GET_SETTINGS" });
    settings = res.settings || defaultSettings;
    hasRemotePassword = !!res.nativeHasPassword;
    if (res.nativeConnected) {
      nativeStatusEl.textContent = `Synced with ${BRAND.fullName} Desktop`;
      nativeStatusEl.classList.add("connected");
    } else {
      nativeStatusEl.textContent = "Standalone mode (Install desktop app for AI screen blur)";
      nativeStatusEl.classList.remove("connected");
    }
    render();
  } catch (e) {
    settings = defaultSettings;
    render();
  }
}

function render() {
  Object.entries(toggles).forEach(([key, el]) => {
    el.checked = !!settings[key];
  });
  Object.values(listInputs).forEach(({ list, key }) => {
    renderList(list, settings[key] || [], key);
  });
  pwSet.disabled = !pwNew.value;
}

function renderList(listEl, items, storageKey) {
  listEl.innerHTML = "";
  (items || []).forEach((item, idx) => {
    const li = document.createElement("li");
    li.className = "cg-list-item";
    const span = document.createElement("span");
    span.className = "cg-list-text";
    span.textContent = item;
    const btn = document.createElement("button");
    btn.className = "cg-btn-remove";
    btn.textContent = "×";
    btn.addEventListener("click", () => removeListItem(storageKey, idx));
    li.appendChild(span);
    li.appendChild(btn);
    listEl.appendChild(li);
  });
}

async function saveSettings() {
  try {
    await sendMessage({ type: "SAVE_SETTINGS", settings });
    showMsg(statusEl, "Settings saved & synced.", "success");
  } catch (e) {
    showMsg(statusEl, "Save failed: " + e.message, "error");
  }
}

const COMMITMENT_SENTENCES = [
  "I acknowledge that bypassing this protection harms my personal growth, focus, and integrity; I consciously commit to staying disciplined 100%.",
  "Discipline is choosing between what you want now and what you want most; I will not compromise my standards for temporary impulses #2026.",
  "Surrendering to short-term temptation destroys long-term character; I affirm my resolve to master my mind and reject digital poison today!",
  "True freedom requires self-restraint and unwavering perseverance; I solemnly declare that I will resist destructive digital vices forever.",
  "Every time I conquer a weak impulse, my willpower becomes stronger; I refuse to let dopamine addiction dictate my life choices & future.",
  "Instant gratification is the enemy of greatness; I choose clarity, self-control, and moral strength over fleeting digital stimulation.",
  "I take full responsibility for my habits and actions; I will conquer procrastination and lust by building unbreakable inner willpower!",
  "A disciplined mind leads to an honorable life; I refuse to surrender my dignity, peace of mind, and valuable time to cheap dopamine."
];

function renderCanvasSentence(canvas, text) {
  const ctx = canvas.getContext("2d");
  const w = canvas.width;
  const h = canvas.height;
  ctx.fillStyle = "#0f172a";
  ctx.fillRect(0, 0, w, h);

  ctx.strokeStyle = "#1e293b";
  ctx.lineWidth = 1;
  for (let x = 0; x < w; x += 16) {
    ctx.beginPath(); ctx.moveTo(x, 0); ctx.lineTo(x, h); ctx.stroke();
  }
  for (let y = 0; y < h; y += 16) {
    ctx.beginPath(); ctx.moveTo(0, y); ctx.lineTo(w, y); ctx.stroke();
  }

  ctx.fillStyle = "#f8fafc";
  ctx.font = "bold 11px system-ui, -apple-system, sans-serif";
  ctx.textAlign = "center";
  ctx.textBaseline = "middle";

  const words = text.split(" ");
  const lines = [];
  let curLine = "";
  for (const word of words) {
    const testLine = curLine ? curLine + " " + word : word;
    if (ctx.measureText(testLine).width > w - 16) {
      lines.push(curLine);
      curLine = word;
    } else {
      curLine = testLine;
    }
  }
  if (curLine) lines.push(curLine);

  const lineH = 15;
  const startY = (h - lines.length * lineH) / 2 + 7;
  lines.forEach((l, idx) => ctx.fillText(l, w / 2, startY + idx * lineH));
}

function triggerChallenge(actionName) {
  return new Promise((resolve) => {
    const modal = document.getElementById("cg-challenge-modal");
    const titleEl = document.getElementById("modal-title");
    const subEl = document.getElementById("modal-subtitle");
    const pwWrap = document.getElementById("modal-password-wrap");
    const pwInput = document.getElementById("modal-password");
    const txtInput = document.getElementById("modal-input");
    const canvas = document.getElementById("modal-canvas");
    const timerEl = document.getElementById("modal-timer");
    const progressEl = document.getElementById("modal-progress");
    const modalStatusEl = document.getElementById("modal-status");
    const errorEl = document.getElementById("modal-error");
    const btnCancel = document.getElementById("modal-btn-cancel");
    const btnSubmit = document.getElementById("modal-btn-submit");

    const needPw = !!(settings.passwordHash || hasRemotePassword);
    pwWrap.style.display = needPw ? "flex" : "none";

    const targetSentence = COMMITMENT_SENTENCES[Math.floor(Math.random() * COMMITMENT_SENTENCES.length)];
    let remainingSec = 40;

    titleEl.textContent = `Defense: ${actionName}`;
    subEl.textContent = `To authorize "${actionName}", accurately type the commitment sentence before 40 seconds expire.`;
    pwInput.value = "";
    txtInput.value = "";
    errorEl.textContent = "";
    btnSubmit.disabled = true;
    renderCanvasSentence(canvas, targetSentence);
    modal.style.display = "flex";

    const blockPaste = (e) => {
      e.preventDefault();
      errorEl.textContent = "Copy-pasting is strictly blocked!";
      return false;
    };
    txtInput.onpaste = blockPaste;
    txtInput.oncontextmenu = blockPaste;
    txtInput.ondrop = blockPaste;
    txtInput.onkeydown = (e) => {
      if ((e.ctrlKey || e.metaKey) && (e.key === "v" || e.key === "V")) blockPaste(e);
    };

    const cleanup = () => {
      if (activeTimer) { clearInterval(activeTimer); activeTimer = null; }
      modal.style.display = "none";
      txtInput.oninput = null;
      btnCancel.onclick = null;
      btnSubmit.onclick = null;
    };

    if (activeTimer) clearInterval(activeTimer);
    timerEl.textContent = "Time Remaining: 0:40";
    timerEl.style.color = "#facc15";
    progressEl.style.width = "100%";

    activeTimer = setInterval(() => {
      remainingSec--;
      if (remainingSec <= 0) {
        cleanup();
        resolve(false);
        return;
      }
      timerEl.textContent = `Time Remaining: 0:${remainingSec < 10 ? "0" : ""}${remainingSec}`;
      progressEl.style.width = `${(remainingSec / 40) * 100}%`;
      if (remainingSec <= 10) timerEl.style.color = "#ef4444";
    }, 1000);

    txtInput.oninput = () => {
      const cur = txtInput.value;
      let matched = 0;
      const len = Math.min(cur.length, targetSentence.length);
      for (let i = 0; i < len; i++) {
        if (cur[i] === targetSentence[i]) matched++;
        else break;
      }
      modalStatusEl.textContent = `${matched} / ${targetSentence.length} characters matched`;
      const exact = matched === targetSentence.length && cur.length === targetSentence.length;
      modalStatusEl.style.color = exact ? "#4ade80" : (matched === cur.length ? "#94a3b8" : "#f43f5e");
      btnSubmit.disabled = !exact;
    };

    btnCancel.onclick = () => { cleanup(); resolve(false); };
    btnSubmit.onclick = async () => {
      if (needPw) {
        if (!pwInput.value) { errorEl.textContent = "Master Password is required."; pwInput.focus(); return; }
        const res = await sendMessage({ type: "VERIFY_PASSWORD", password: pwInput.value });
        if (!res.valid) { errorEl.textContent = "Incorrect Master Password!"; pwInput.focus(); return; }
      }
      if (txtInput.value.trim() !== targetSentence.trim()) {
        errorEl.textContent = "Sentence does not match exactly.";
        return;
      }
      cleanup();
      resolve(true);
    };
    (needPw ? pwInput : txtInput).focus();
  });
}

Object.entries(toggles).forEach(([key, el]) => {
  el.addEventListener("change", async () => {
    if (!el.checked) {
      el.checked = true;
      const label = el.closest(".cg-toggle-label")?.querySelector("span")?.textContent || "Protection Feature";
      const passed = await triggerChallenge(`Disable ${label}`);
      if (!passed) return;
      el.checked = false;
    }
    settings[key] = el.checked;
    await saveSettings();
    if (key === "blockIncognito" && el.checked) {
      await sendMessage({ type: "NATIVE_DISABLE_INCOGNITO" });
    }
  });
});

function addListItem(tab) {
  const { input, list, key } = listInputs[tab];
  const val = input.value.trim().toLowerCase();
  if (!val) return;
  const arr = settings[key] || [];
  if (arr.includes(val)) return;
  arr.push(val);
  settings[key] = arr;
  input.value = "";
  renderList(list, arr, key);
  saveSettings();
}

async function removeListItem(key, idx) {
  const arr = settings[key] || [];
  const item = arr[idx];
  const passed = await triggerChallenge(`Unblock "${item}"`);
  if (!passed) return;
  arr.splice(idx, 1);
  settings[key] = arr;
  const entry = Object.values(listInputs).find((v) => v.key === key);
  renderList(entry.list, arr, key);
  await saveSettings();
}

Object.entries(listInputs).forEach(([tab, { input, add }]) => {
  add.addEventListener("click", () => addListItem(tab));
  input.addEventListener("keydown", (e) => { if (e.key === "Enter") addListItem(tab); });
});

document.querySelectorAll(".cg-tab").forEach((btn) => {
  btn.addEventListener("click", () => {
    document.querySelectorAll(".cg-tab").forEach((b) => b.classList.remove("active"));
    document.querySelectorAll(".cg-tab-panel").forEach((p) => p.classList.remove("active"));
    btn.classList.add("active");
    document.getElementById("tab-" + btn.dataset.tab).classList.add("active");
  });
});

pwNew.addEventListener("input", () => { pwSet.disabled = !pwNew.value; });
pwSet.addEventListener("click", async () => {
  const current = pwCurrent.value;
  const next = pwNew.value;
  if (!next) return;
  if (settings.passwordHash || hasRemotePassword) {
    const res = await sendMessage({ type: "VERIFY_PASSWORD", password: current });
    if (!res.valid) { showMsg(pwMsg, "Current password is incorrect.", "error"); return; }
  }
  await sendMessage({ type: "SET_PASSWORD", oldPassword: current, newPassword: next });
  hasRemotePassword = true;
  pwCurrent.value = "";
  pwNew.value = "";
  pwSet.disabled = true;
  showMsg(pwMsg, "Master password updated.", "success");
});

loadSettings();
