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
git clone --recursive https://github.com/zinw/airplay-tv.git
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

Debug and release both sign with the committed keystore at
`keystore/airplaytv-upload.keystore` (see `keystore/README.md`). Do **not** rely on
`~/.android/debug.keystore` — that caused v1.0.1 / v1.0.2 / v1.0.3 to each ship a
different cert and blocked USB/OTA overlays.

### Upgrade / signing
- **1.0.4+ → later 1.0.x**: `adb install -r` / in-app OTA should work (same upload key).
- **1.0.1 / 1.0.2 / 1.0.3 → 1.0.4**: uninstall once first; those Releases used three
  different ephemeral Android Debug certs and cannot match the new key.

Outputs (generated in `app/build/outputs/apk/release/`):
- `app-armeabi-v7a-release.apk`: Lightweight build for 32-bit ARM TVs & TV sticks (~13 MB)
- `app-arm64-v8a-release.apk`: Lightweight build for 64-bit ARM TVs & Shields (~17 MB)
- `app-x86_64-release.apk`: Build for x86_64 Android TV / Emulators (~17 MB)
- `app-universal-release.apk`: Universal all-in-one APK containing all ABIs (~30 MB)

### Architecture & ABI Targets
AirPlay TV builds native `.so` binaries for:
- `arm64-v8a` (Modern Android TV & Google TV devices, Chromecast with Google TV 4K)
- `armeabi-v7a` (32-bit TV boxes, Mi Box, fire TV sticks)
- `x86_64` (Android TV Emulators and x86 devices)

---

## 🚀 4. Automated CI/CD Release Pipeline

Tag-driven release workflow (`.github/workflows/release.yml`):

```
[bump versionName/versionCode in app/build.gradle.kts → commit → git tag vX.Y.Z → push tag]
             │
             ├── 1. Checkout (fetch-depth 0; shallow submodules + fallback); JDK 17 + Gradle cache; NDK/CMake on ubuntu-24.04
             ├── 2. Fail if tag vX.Y.Z ≠ versionName X.Y.Z
             ├── 3. ./gradlew assembleRelease (committed upload keystore)
             ├── 4. Rename to AirPlayTV-<version>-<abi>.apk (+ universal)
             ├── 5. SHA-256 sums; publish GitHub Release (body includes versionCode: N)
             └── workflow_dispatch: same build, upload workflow artifact only (no Release)
```

### Release versioning
- **Source of truth**: `versionName` / `versionCode` in `app/build.gradle.kts`.
- **Tag rule**: push `v{versionName}` only after the bump is on the branch tip (CI rejects mismatches).
- **Assets** (OTA-compatible names): `AirPlayTV-<version>-arm64-v8a.apk`, `…-armeabi-v7a.apk`, `…-x86_64.apk`, `…-universal.apk`, plus `.sha256` / `SHA256SUMS.txt`.

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

# Or Install Release APK (e.g. armeabi-v7a or arm64-v8a)
adb install -r app/build/outputs/apk/release/app-armeabi-v7a-release.apk

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

#### Mirror picture QA: mosaic vs black (Honor / arm64)
```bash
adb logcat -s VideoRenderer:I
```
| Symptom | What you see | Logcat signals |
| --- | --- | --- |
| **Black after rename** | Cold start OK; change device name / toggle H.265 etc. then remirror → full black; force-stop app fixes it | Missing `VIDEO_SERVER_RESET` / `VIDEO_MIRROR_START` after restart, or `VIDEO_CODEC_THREAD recreate`; no `VIDEO_FIRST_FRAME` on reconnect |
| **Black (other)** | Surface blank after connect / flush | Missing `VIDEO_FIRST_FRAME`, or long `VIDEO_AWAIT_KEYFRAME` without `VIDEO_KEYFRAME_START` |
| **Mosaic / stale tiles** | Picture up but partial regions stop refreshing | `VIDEO_FIRST_FRAME` already happened; `VIDEO_DROP_RESTART` / `Decoder input queue full` |
| Healthy | Live picture | `VIDEO_MIRROR_START` → `VIDEO_KEYFRAME_START` → `VIDEO_FIRST_FRAME` (after rename also expect `VIDEO_SERVER_RESET`) |

**Rename repro:** Settings → Device name → save → remirror from iPhone. Expect picture (not black). Do **not** reintroduce soft-flush / IDR-only / suppress-render (PR #7/#8).

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
- **Signal bars** (top-right): Wi‑Fi RSSI mapped to 0–3 neon-green bars.
- **Decoder**: Active MediaCodec name (e.g. `OMX.hisi.video.decoder.avc`).
- **rec / dec**: Received (RAOP feed) vs decoded/presented frames per second.
- **Resolution**: Live mirror stream width×height.
- **Band**: Connected Wi‑Fi frequency band (`5G` = 5 GHz, `2.4G`, `6G`). Missing values show as `—`.
- Legacy extras (bitrate, drops, audio cushion) remain available via logcat / `collectDebugInfo()`.

---

## 🚨 8. Common Troubleshooting

| Issue | Root Cause | Solution |
| :--- | :--- | :--- |
| **TV not showing in iOS Control Center** | Multicast / mDNS blocked by Wi-Fi router | Ensure TV and iPhone are on the same Wi-Fi SSID. Disable "AP Isolation" / "Client Isolation" and enable "IGMP Snooping" / "Multicast Enhancement" in router settings. |
| **Port 7000 already in use** | Google Cast or another streaming service is holding port 7000 | AirPlay TV automatically falls back to an available dynamic port and updates the Bonjour mDNS TXT record. Verify in logcat: `adb logcat -s NativeBridge`. |
| **NDK CMake Build Error: `Not building OpenSSL again`** | Stale CMake cache in NDK build directory | Clean CMake cache: `rm -rf app/.cxx/` then run `./gradlew assembleDebug`. |
| **16KB Page Alignment Warning** | Older native libraries | Ensure `-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON` is present in `app/build.gradle.kts`. |
| **Video freezing on surface changes** | Codec directly bound to SurfaceView | AirPlay TV uses decoupled EGL `SurfaceTexture` rendering in `VideoPipeline.kt`. Verify the GL thread is running: `adb logcat -s VideoPipeline`. |
