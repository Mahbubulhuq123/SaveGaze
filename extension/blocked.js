(function () {
  const BRAND = window.__saveGazeBrand || { appName: "SaveGaze" };
  const QUOTES = [
    "Your future self will thank you.",
    "Discipline is choosing between what you want now and what you want most.",
    "Every urge passes. Ride it out.",
    "You are stronger than this impulse.",
    "Focus on what matters. Everything else is noise."
  ];

  document.title = `Blocked - ${BRAND.appName}`;
  document.getElementById("sg-title").textContent = `Blocked by ${BRAND.appName}`;

  const params = new URLSearchParams(location.search);
  const reason = params.get("reason") || `This content was blocked by ${BRAND.appName}.`;
  const url = params.get("url") || "";
  document.getElementById("cg-reason").textContent = reason + (url ? " (" + url + ")" : "");
  document.getElementById("cg-quote").textContent = QUOTES[Math.floor(Math.random() * QUOTES.length)];

  document.getElementById("btn-go-back").addEventListener("click", () => {
    window.history.back();
  });
})();
