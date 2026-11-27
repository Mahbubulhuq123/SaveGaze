// Centralized branding for the SaveGaze browser extension.
// Change names/IDs here to rebrand the extension cleanly.
(function (root) {
  const BRAND = {
    appName: "SaveGaze",
    fullName: "SaveGaze PC",
    arabicTag: "حفظ البصر",
    nativeHost: "com.savegaze.host",
    passwordSaltPrefix: "contentguard_salt_2024:",
    legacyPasswordSaltSuffix: "contentguard_salt_2024",
    version: "1.1.0",
    tagline: "On-device protection. Synced with SaveGaze PC — zero data leaves your device."
  };
  root.__saveGazeBrand = BRAND;
})(typeof self !== "undefined" ? self : window);
