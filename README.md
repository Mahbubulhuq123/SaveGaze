# SaveGaze (حفظ البصر) 🛡️

**SaveGaze** is an open-source, on-device Android privacy and digital wellness utility inspired by **HaramBlur** and **BlockP**. It automatically detects, classifies, and selectively blurs semi-nude and nude content across any application on Android using on-device AI while strictly preserving human faces.

---

## ✨ Features

1. **AI-Powered Semi-Nude & Nude Content Detection**
   - Runs **100% on-device** using the NudeNet v3 YOLO model (`best_320s.onnx`) accelerated via ONNX Runtime.
   - Detects 18 distinct anatomical and exposure classes (including exposed and covered intimate regions).
   - **Zero Cloud / Absolute Privacy**: No screenshots, videos, or network requests leave your device.

2. **Gender Preference & Face Preservation**
   - **Face Preservation**: Faces (`FACE_FEMALE`, `FACE_MALE`) are strictly segregated and never blurred.
   - **Gender Filtering**:
     - `Female Only`: Blurs female intimate areas (exposed & covered) + neutral sensitive parts.
     - `Male Only`: Blurs male intimate areas (exposed & covered) + neutral sensitive parts.
     - `Both`: Blurs all detected intimate areas while keeping all faces clear.

3. **Universal Screen-Wide Blurring (Any App)**
   - Utilizes Android 11+ (API 30) `AccessibilityService.takeScreenshot()` at configurable intervals (~500ms).
   - Draws smooth, non-intrusive canvas blur rectangles using `TYPE_ACCESSIBILITY_OVERLAY` (`FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE`).
   - Touch interactions pass through seamlessly to the underlying apps.

4. **Modern UI & DataStore Settings**
   - Built with **Jetpack Compose + Material 3**.
   - Reactive status card showing real-time protection state with one-click shortcut to Accessibility settings.
   - Sensitivity slider (Low / Medium / High) and Blur intensity adjustment (10px–40px radius).

---

## 🏗️ Architecture & Technology Stack

| Layer | Technology |
|---|---|
| **Language** | Kotlin (Java 17 target) |
| **Minimum SDK** | Android 11 (API level 30) |
| **Target SDK** | Android 14 (API level 34) |
| **ML Inference** | ONNX Runtime Android (`com.microsoft.onnxruntime:onnxruntime-android:1.16.3`) |
| **AI Model** | NudeNet v3 320n YOLO detection ONNX model (packaged in assets) |
| **Overlay Engine** | `WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY` |
| **User Interface** | Jetpack Compose + Material 3 |
| **Preferences** | AndroidX DataStore Preferences |

---

## 📁 Project Structure

```
SaveGaze/
├── app/
│   ├── build.gradle                          # Dependencies (Compose, ONNX, DataStore)
│   ├── src/
│   │   ├── main/
│   │   │   ├── AndroidManifest.xml           # Service declaration & permissions
│   │   │   ├── assets/
│   │   │   │   └── best_320s.onnx            # NudeNet v3 on-device AI model (12.15 MB)
│   │   │   ├── java/com/savegaze/
│   │   │   │   ├── MainActivity.kt           # Compose activity & tab navigation
│   │   │   │   ├── SaveGazeApplication.kt    # Application entry point
│   │   │   │   ├── data/
│   │   │   │   │   └── PreferencesManager.kt # DataStore settings management
│   │   │   │   ├── ml/
│   │   │   │   │   ├── DetectionResult.kt    # Enums, bounding boxes, filtered models
│   │   │   │   │   └── NudeNetDetector.kt    # ONNX runtime inference & NMS
│   │   │   │   ├── service/
│   │   │   │   │   ├── OverlayManager.kt     # System overlay window manager
│   │   │   │   │   └── SaveGazeAccessibilityService.kt # Core screenshot & blur loop
│   │   │   │   ├── ui/
│   │   │   │   │   ├── screens/
│   │   │   │   │   │   ├── HomeScreen.kt     # Dashboard & quick toggles
│   │   │   │   │   │   └── SettingsScreen.kt # Gender filter & sensitivity sliders
│   │   │   │   │   └── theme/
│   │   │   │   │       └── Theme.kt          # Material 3 theme
│   │   │   │   └── util/
│   │   │   │       ├── BitmapUtils.kt        # CHW buffer conversion & CPU StackBlur
│   │   │   │       └── Constants.kt          # Taxonomies & thresholds
│   │   │   └── res/
│   │   │       └── xml/
│   │   │           └── accessibility_service_config.xml
│   │   └── test/
│   │       └── java/com/savegaze/ml/
│   │           └── DetectionFilterTest.kt    # JUnit test suite (100% pass)
├── build.gradle                              # Root Gradle build script
├── settings.gradle                           # Project settings
├── gradlew.bat                               # Gradle wrapper executable
└── README.md
```

---

## 📦 Prebuilt APKs & Architecture Support

SaveGaze is distributed with dedicated builds for every Android architecture as well as a universal bundle:

| Package | Target Architecture | Description |
|---|---|---|
| **[`SaveGaze.apk`](SaveGaze.apk)** | **Universal** (All ABIs) | Runs on **every Android phone, tablet, and emulator**. Guaranteed universal compatibility. |
| **[`SaveGaze-arm64-v8a.apk`](release/SaveGaze-arm64-v8a.apk)** | `arm64-v8a` | **Recommended for modern phones** (Samsung, Pixel, Xiaomi, OnePlus, Motorola, etc.). |
| **[`SaveGaze-armeabi-v7a.apk`](release/SaveGaze-armeabi-v7a.apk)** | `armeabi-v7a` | For older or budget 32-bit ARM Android devices. |
| **[`SaveGaze-x86_64.apk`](release/SaveGaze-x86_64.apk)** | `x86_64` | For 64-bit Android Studio emulators and ChromeOS Android subsystem. |
| **[`SaveGaze-x86.apk`](release/SaveGaze-x86.apk)** | `x86` | For legacy 32-bit x86 emulators. |

---

## 🚀 How to Build & Install

### Prerequisites
1. **JDK 17 or JDK 21** (e.g. Microsoft OpenJDK 21 or Eclipse Temurin).
2. **Android SDK** with Platform 34 and Build-Tools 34.0.0.

### 1. Build APKs
Run the following command from the project root:

```bash
./gradlew :app:assembleDebug
```

The compiled APKs for all architectures are generated at:
```
app/build/outputs/apk/debug/
├── SaveGaze.apk              # Universal build (all architectures)
├── SaveGaze-arm64-v8a.apk    # 64-bit ARM
├── SaveGaze-armeabi-v7a.apk  # 32-bit ARM
├── SaveGaze-x86_64.apk       # 64-bit x86
└── SaveGaze-x86.apk          # 32-bit x86
```

### 2. Install on Device or Emulator
Connect your Android 9+ (API 28+) device via USB (or start an AVD emulator) and run:

```bash
# Universal (works on any device)
adb install -r SaveGaze.apk

# Or install the targeted architecture directly:
adb install -r release/SaveGaze-arm64-v8a.apk
```

---

## 📱 Device Setup & Permissions

Once installed on your device, follow these steps to enable system-wide protection:

1. **Launch SaveGaze** from your app drawer.
2. Tap the **"Protection Inactive"** card or open your phone's **Settings > Accessibility**.
3. Under **Downloaded Apps**, select **SaveGaze**.
4. Toggle **Use SaveGaze** to **ON** and accept the system accessibility prompt.
5. If prompted, grant the **"Display over other apps"** permission.
6. Open your favorite browser, social feed, or video player. Any detected semi-nude or nude content matching your gender preference will be automatically obscured with a blur overlay while faces remain clear!

