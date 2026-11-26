# SaveGaze PC (حفظ البصر)

**SaveGaze PC** is the full Windows desktop + browser extension edition of [SaveGaze](https://github.com/Mahbubulhuq123/SaveGaze), upgrading ContentGuard into a system-wide, 100% on-device AI screen shield, intimate audio/video guard, and tamper-resistant content blocker.

## Features

1. **System-Wide AI Screen Blur Overlay (All Windows Apps)**
   - Works across Chrome, Brave, Edge, Firefox, VLC, Stremio, Nuvio, MPV, Kodi, Telegram, and desktop apps.
   - Runs **NudeNet v3 (`best_320s.onnx`) + PordaV4x3 (`pordav4x3.onnx`)** locally via ONNX Runtime.
   - **Gender Filter**: `Female Only`, `Male Only`, or `Both`.
   - **Face Blurring Toggle**: When OFF, extends body blur upward through neck/throat to the jawline while leaving upper faces visible; when ON, blurs target faces too.
   - **Overlay Styles & Opacity**: `Solid Black`, `Dark Frosted`, `Light Censor` (50%–100% opacity), click-through layered window excluded from screen capture (`WDA_EXCLUDEFROMCAPTURE`).
   - **Low-CPU Optimizations**: Skips scanning when Windows workstation is locked, runs a lightweight 14×14 screen-diff hash excluding active blur regions before ONNX inference, and uses adaptive tiling.

2. **Intimate Video, Scene & Audio Guard**
   - **Visual Scene & Geometry**: `image_safety_xs.onnx` + close-up kissing & bed-pose geometry.
   - **Windows WASAPI Loopback Audio AI**: Captures system playback audio without microphone or Screen Cast popups, gates strictly on non-silent audio RMS, and detects moaning/groaning/panting via `yamnet.onnx`.
   - **Blur First, Then Act**: Covers the screen immediately before executing context-aware actions (Reels/Shorts → Next Reel; Video Players → Seek Forward; General Browser → Pause & Close Tab).
   - **Subtitle/Caption & DRM Guard**: Detects `[moaning]` / `(kissing)` captions and supports Warn/Auto-Block policies for DRM apps/sites.

3. **Enhanced Browser Extension & Tamper Shield**
   - Live typing single-word blocked keyword guard inside web search bars.
   - Domain blocking (`declarativeNetRequest` + Hosts file), Incognito/Private browsing policy lockout, and 5-in-10m cross-block cooldown lockout.
   - Master Password + 40-second anti-paste commitment challenge for disabling shields or uninstalling.
   - In-app GitHub Release auto-updater (`pc-v*` tags + `pc-version.json`).

4. **Centralized Branding**
   - Desktop branding/IDs live in [`src/SaveGaze.Core/BrandConfig.cs`](src/SaveGaze.Core/BrandConfig.cs).
   - Browser extension branding/IDs live in [`extension/brand.js`](extension/brand.js).

## Build on Windows

```powershell
# All SDK/NuGet/output artifacts stay under K:\SaveGaze-PC
.\scripts\build-release.ps1 -Version 1.0.0
```
