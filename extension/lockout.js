(function () {
  const BRAND = window.__saveGazeBrand || { appName: "SaveGaze" };
  document.title = `Access Locked - ${BRAND.appName}`;
  document.getElementById("sg-title").textContent = `${BRAND.appName} Lockout`;

  const params = new URLSearchParams(location.search);
  const domain = params.get("domain") || "";
  const until = parseInt(params.get("until") || "0", 10);
  if (domain) document.getElementById("cg-domain").textContent = "Domain: " + domain;

  history.pushState(null, null, location.href);
  window.addEventListener("popstate", () => history.pushState(null, null, location.href));
  document.addEventListener("contextmenu", (e) => e.preventDefault());
  document.addEventListener("keydown", (e) => {
    if ((e.ctrlKey || e.metaKey) && (e.key === "r" || e.key === "R")) e.preventDefault();
    if (e.key === "F12" || e.key === "Escape") e.preventDefault();
  });

  const timerEl = document.getElementById("cg-timer");
  function updateTimer() {
    const remaining = until - Date.now();
    if (remaining <= 0) {
      timerEl.textContent = "0:00";
      return;
    }
    const mins = Math.floor(remaining / 60000);
    const secs = Math.floor((remaining % 60000) / 1000);
    timerEl.textContent = `${mins}:${secs.toString().padStart(2, "0")}`;
    requestAnimationFrame(updateTimer);
  }
  updateTimer();
})();
