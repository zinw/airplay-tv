# Building & Debugging Guide

This document provides a comprehensive guide for setting up the developer environment, building **AirPlay TV** from source, and debugging native C++ and Kotlin media pipelines on Android TV devices.

---

## 🛠️ 1. Development Environment & Prerequisites

### Required Toolchain
- **JDK**: Java Development Kit 17 (Temurin 17 or OpenJDK 17 recommended)
- **Android SDK**:
  - `compileSdk`: **34** (Android 14)
  - `minSdk`: **26** (Android 8.0 Oreo)
  - `targetSdk`: **34**
- **Android NDK**: **`27.0.12077973`** (Required for 16KB memory page alignment and modern C++20 toolchains)
- **CMake**: **`3.22.1+`**
- **Git**: Supporting submodules

### Installing SDK & NDK Components
You can install the exact NDK and CMake using Android Studio's SDK Manager or CLI `sdkmanager`:
```bash
# Using command-line tools
yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" \
    "platforms;android-34" \
    "build-tools;34.0.0" \
    "ndk;27.0.12077973" \
    "cmake;3.22.1"
```

---

## 🏗️ 2. Repository Setup & Submodules

AirPlay TV relies on four native submodules (`UxPlay`, `ffmpeg`, `libplist`, `openssl-cmake`):

```bash
# Clone the repository with submodules
git clone --recursive https://github.com/flymop/airplay-tv.git
cd airplay-tv

# If cloned without --recursive:
git submodule update --init --recursive
```

### Automated UxPlay Patching
The Gradle build includes an automated task (`applyUxplayPatches`) in `app/build.gradle.kts`. When CMake configures, Gradle automatically verifies and applies necessary runtime patches from `app/src/main/cpp/patches/UxPlay/` to the UxPlay source tree (such as socket buffer optimizations, FCUP URL rewrites, and thread sync tweaks).

---

## 📦 3. Compiling from Source

### Debug Build
```bash
./gradlew assembleDebug
```
Output: `app/build/outputs/apk/debug/app-debug.apk`

### Release Build (Production Ready)
```bash
./gradlew assembleRelease
```
Output: `app/build/outputs/apk/release/app-release.apk`

### Architecture & ABI Targets
AirPlay TV builds native `.so` binaries for:
- `arm64-v8a` (Modern Android TV & Google TV devices, Chromecast with Google TV 4K)
- `armeabi-v7a` (32-bit TV boxes, Mi Box, fire TV sticks)
- `x86_64` (Android TV Emulators and x86 devices)

---

## 🚀 4. Automated CI/CD Release Pipeline

AirPlay TV includes an automated GitHub Actions workflow (`.github/workflows/release.yml`) configured for continuous release delivery:

```
[git push to main / master]
             │
             ├── 1. Checkout repository with all submodules recursively
             ├── 2. Provision JDK 17 (Temurin) & Gradle cache
             ├── 3. Install NDK 27.0.12077973 & CMake 3.22.1 via sdkmanager
             ├── 4. Extract 8-character commit hash version (v<SHORT_SHA>)
             ├── 5. Build production release APK (./gradlew assembleRelease)
             ├── 6. Generate SHA-256 checksums
             └── 7. Create GitHub Release & publish APK assets via softprops/action-gh-release
```

### Release Versioning
- Releases are tagged using the 8-character commit hash (e.g. `vd89e2860`).
- Artifacts:
  - `AirPlayTV-<SHORT_SHA>-release.apk` (Signed production APK for all ABIs)
  - `AirPlayTV-<SHORT_SHA>-release.apk.sha256` (SHA-256 verification checksum)

---

## 📺 5. Remote Wireless Debugging on Android TV

Android TV devices typically do not have direct USB connections available. Wireless ADB debugging is the standard workflow.

### Step 1: Enable Developer Options & USB Debugging
1. On Android TV: **Settings** $\rightarrow$ **System** $\rightarrow$ **About** $\rightarrow$ Click **Android TV OS build** 7 times to enable Developer Options.
2. Go to **Settings** $\rightarrow$ **System** $\rightarrow$ **Developer Options** $\rightarrow$ Enable **USB Debugging** and **Wireless Debugging**.

### Step 2: Connect via ADB
```bash
# Direct IP connect (Android 10 and below, or standard port 5555)
adb connect <TV_IP_ADDRESS>:5555

# Or Pair using Android 11+ Wireless Debugging Pairing Code
adb pair <TV_IP_ADDRESS>:<PAIRING_PORT> <6_DIGIT_CODE>
adb connect <TV_IP_ADDRESS>:<DEBUG_PORT>

# Verify connection
adb devices
```

### Step 3: Install & Launch
```bash
# Install Debug APK
./gradlew installDebug

# Or Install Release APK
adb install -r app/build/outputs/apk/release/app-release.apk

# Launch AirPlay TV activity
adb shell am start -n com.flymop.airplaytv/.MainActivity
```

---

## 🔍 6. Live Logcat Debugging

Filter Android logcat streams by subsystem:

### A. Native AirPlay Protocol & RTSP Core
```bash
adb logcat -v time -s AirPlayNative UxPlay NativeBridge
```
- Tracks RTSP negotiation (`SETUP`, `RECORD`, `TEARDOWN`), FairPlay Ed25519 pairing, binary plist exchange, and client connections.

### B. Audio Engine & Oboe Latency
```bash
adb logcat -v time -s AudioRenderer AudioEngine OboeAudio TimelineBuffer
```
- Logs audio buffer backlog (ms), target cushion adaptation, frame drops, trims, and AAudio MMAP stream status.

### C. Video Renderer & Hardware MediaCodec
```bash
adb logcat -v time -s VideoRenderer VideoPipeline MediaCodec C2Amlogic
```
- Logs hardware decoder initialization (`c2.amlogic.hevc.decoder`, `c2.android.avc.decoder`), keyframe NAL detection, and OpenGL ES render timing.

### D. Discovery & Background Service
```bash
adb logcat -v time -s AirPlayService NsdServiceManager
```
- Logs mDNS registration, service name conflict resolution, multicast lock acquisition, and DACP media session state.

---

## 📊 7. In-App Performance HUD

AirPlay TV includes a built-in real-time diagnostics overlay (Performance HUD).

### How to Enable:
1. Open the in-app **SETTINGS** modal $\rightarrow$ Toggle **Performance HUD**.
2. Or press the **MENU** / **BLUE** color key on your TV remote controller during active playback.

### HUD Metrics Explained:
- **Video FPS**: Current frames per second decoded and rendered to the surface.
- **Video Bitrate**: Real-time RTP video stream bitrate (Mbps).
- **Codec**: Active hardware decoder format (`H.264 / AVC` vs `H.265 / HEVC`).
- **Audio Backlog**: Unplayed PCM data held in the native ring buffer (ms).
- **Tuned Cushion**: Target dynamic jitter cushion computed from network arrival statistics (ms).
- **Audio XRuns / Drops / Trims**: Number of audio packet underflows or buffer adjustments performed to eliminate delay drift.

---

## 🚨 8. Common Troubleshooting

| Issue | Root Cause | Solution |
| :--- | :--- | :--- |
| **TV not showing in iOS Control Center** | Multicast / mDNS blocked by Wi-Fi router | Ensure TV and iPhone are on the same Wi-Fi SSID. Disable "AP Isolation" / "Client Isolation" and enable "IGMP Snooping" / "Multicast Enhancement" in router settings. |
| **Port 7000 already in use** | Google Cast or another streaming service is holding port 7000 | AirPlay TV automatically falls back to an available dynamic port and updates the Bonjour mDNS TXT record. Verify in logcat: `adb logcat -s NativeBridge`. |
| **NDK CMake Build Error: `Not building OpenSSL again`** | Stale CMake cache in NDK build directory | Clean CMake cache: `rm -rf app/.cxx/` then run `./gradlew assembleDebug`. |
| **16KB Page Alignment Warning** | Older native libraries | Ensure `-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON` is present in `app/build.gradle.kts`. |
| **Video freezing on surface changes** | Codec directly bound to SurfaceView | AirPlay TV uses decoupled EGL `SurfaceTexture` rendering in `VideoPipeline.kt`. Verify the GL thread is running: `adb logcat -s VideoPipeline`. |
