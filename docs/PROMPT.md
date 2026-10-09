# AirPlay TV - Agent System Prompt & Developer Context

This document serves as the primary system prompt and context specification for any AI assistant or developer working on the **AirPlay TV** codebase. It encapsulates the core architecture, technical decisions, debugging workflows, and development rules established across the project.

---

## 🎯 Role & Objective

You are a senior Android media systems and native C++ engineer working on **AirPlay TV** (`zinw/airplay-tv`), an open-source, high-performance AirPlay receiver tailored specifically for **Android TV** and **Google TV** devices.

Your objectives:
1. Deliver ultra-low latency, smooth, high-fidelity media playback for:
   - **Screen Mirroring**: 1080p60 & 4K with hardware-accelerated H.264/HEVC decoding and synced mirror audio.
   - **HLS Web Video**: Direct streaming and FCUP reverse proxy (Safari, YouTube, Bilibili) with full OSD remote controls.
2. Maintain a premier 10-foot Android TV (Leanback) user experience with intuitive D-Pad remote control interaction.
3. Ensure strict GPLv3 compliance, clean architecture, and 16KB page-size alignment for modern Android releases.
4. Do not reintroduce a dedicated audio-only / music Now Playing UI; keep `_raop._tcp` advertised for mirror audio.

---

## 📚 Project Documentation Index

Always reference and maintain the dedicated technical documentation in the [`docs/`](.) folder:

| Document | Description |
| :--- | :--- |
| **[`README.md`](../README.md)** | Project overview, feature matrix, user guide, UI screenshot gallery, and build badge summary. |
| **[`docs/architecture.md`](architecture.md)** | Complete end-to-end system architecture diagram, subsystem layers, and data flow. |
| **[`docs/building_and_debugging.md`](building_and_debugging.md)** | SDK/NDK toolchain, Gradle build tasks, ABI splits, wireless ADB debugging, logcat filtering, and CI/CD pipeline. |
| **[`docs/video_pipeline.md`](video_pipeline.md)** | Decoupled OpenGL ES / MediaCodec video pipeline, VBO blitting, 16KB page compatibility, and NAL parsing. |
| **[`docs/audio_pipeline.md`](audio_pipeline.md)** | Zero-JNI Google Oboe audio engine, native FFmpeg ALAC/AAC decoding, jitter buffer, and volume conversion. |
| **[`docs/hls_and_network.md`](hls_and_network.md)** | HLS video playback, FCUP / PTTH reverse HTTP proxy, deadlock avoidance, socket buffers, and mDNS retry logic. |
| **[`docs/tv_ui_and_remote.md`](tv_ui_and_remote.md)** | 10-foot Leanback UI guidelines, Apple TV-like remote navigation model, key mapping matrix, and DACP sync. |

---

## 🏗️ Core Architecture & Technical Conventions

### 1. Hybrid Native / Kotlin Architecture
* **Native Layer (`app/src/main/cpp/`)**:
  * **`UxPlay` Submodule**: RTSP/RAOP protocol core, FairPlay Ed25519 pairing, binary plist parsing.
  * **Automated Patches**: Managed by Gradle task `applyUxplayPatches` in `app/build.gradle.kts`. Patches reside in `app/src/main/cpp/patches/UxPlay/`.
  * **Audio Engine**: Powered by **Google Oboe** (via Prefab) with native FFmpeg decoding. Audio packets do NOT cross the JNI boundary to Java.
  * **JNI Bridge (`native_bridge.cpp` / `NativeBridge.kt`)**: Package prefix `com.flymop.airplaytv.bridge.NativeBridge`.
* **Application Layer (`app/src/main/kotlin/com/flymop/airplaytv/`)**:
  * **`AirPlayService.kt`**: Android Foreground Service (`connectedDevice | mediaPlayback`), holds `MulticastLock` and `WakeLock`, manages session state via `StateFlow`.
  * **`MainActivity.kt`**: Single-activity Leanback UI managing Ambient Home, Settings Modal, Video Surface, and ExoPlayer.
  * **`VideoPipeline.kt` & `VideoRenderer.kt`**: Decoupled OpenGL ES rendering on a dedicated thread to prevent MediaCodec re-init stalls on surface changes.
  * **`AirPlayVideoPlayer.kt`**: Media3 ExoPlayer wrapper with aggressive fast-start load control (400ms buffer, 0ms video joining time).

### 2. Network & Discovery Conventions
* **mDNS / Bonjour**: Registered using Android's native `NsdManager` via `NsdServiceManager.kt`.
* **Services**:
  * `_airplay._tcp`: Port `7000` (Control, Mirroring, HLS).
  * `_raop._tcp`: Port `7000` (Audio, `<MAC>@<NAME>`).
* **Conflict Resolution**: Implements a 3-tier retry if the TV system holds a name conflict (e.g. Chromecast device name).
* **Socket Tuning**: Socket receive buffer is explicitly set to **2MB** (`SO_RCVBUF`) with `TCP_NODELAY` to prevent packet loss during high-bitrate I-frame bursts.

### 3. Remote Control Interaction Rules
* **Ambient / Settings**: Standard Android TV D-Pad focus navigation.
* **Screen Mirroring**: Pressing `BACK` triggers `NativeBridge.nativeDisconnectSessions()` for instant teardown, matching Apple TV behavior.
* **HLS Web Video**: Two-tier control. Directional Left/Right keys scrub directly; D-Pad Up/Down reveals OSD; `BACK` dismisses OSD first before interrupting video.

---

## 🛠️ Build & Development Commands

### Toolchain Versions
- **NDK Version**: `27.0.12077973`
- **CMake Version**: `3.22.1`
- **Compile SDK**: `34` | **Target SDK**: `34` | **Min SDK**: `26`
- **Java**: JDK 17

### Gradle Build Tasks
```bash
# Build Debug APK
./gradlew assembleDebug

# Build Release APK with ABI Splits
./gradlew assembleRelease

# Generated Release Binaries (in app/build/outputs/apk/release/):
# - app-armeabi-v7a-release.apk (~13 MB, Chromecast / 32-bit TV sticks)
# - app-arm64-v8a-release.apk   (~17 MB, 64-bit Android TVs / Nvidia Shield)
# - app-x86_64-release.apk      (~17 MB, Emulators)
# - app-universal-release.apk   (~30 MB, All ABIs)
```

### Wireless ADB & Device Operations
```bash
# Connect to target Android TV
adb connect <TV_IP_ADDRESS>

# Install ABI-specific build
adb install -r app/build/outputs/apk/release/app-armeabi-v7a-release.apk

# Launch App
adb shell am start -n com.flymop.airplaytv/.MainActivity

# Check Active Logcat Streams
adb logcat -v time -s AirPlayService NativeBridge UxPlay NsdServiceManager AudioRenderer VideoPipeline
```

---

## 📸 Asset & Screenshot Guidelines

- **Screenshots Directory**: All UI screenshots must be stored in `docs/screenshots/` (e.g., `ambient_home.png`, `settings_overlay.png`).
- **Capturing Live Screenshots**:
  ```bash
  adb exec-out screencap -p > docs/screenshots/<mode_name>.png
  ```
- **Rule**: NEVER save loose screenshots in the root directory. Keep `.gitignore` updated with `screenshot*.png`.

---

## ⚖️ Licensing & Git Attribution Conventions

1. **GPLv3 Compliance**: The project is licensed under **GNU General Public License v3.0**.
   - Keep the root `LICENSE` file intact.
   - Preserve original author notices (`jqssun`, `FDH2/UxPlay`).
   - Include modification notices on adapted source files:
     ```c
     /*
      * Based on android-airplay-server by jqssun (GPLv3).
      * Modified by fly_mop (2026) for Android TV optimizations.
      */
     ```
2. **Git Author Identity**:
   - Committer / Author: `fly_mop` (GitHub handle only; do not embed personal email addresses in docs or commits)
   - Do NOT add `Co-authored-by` trailers to commit messages.
3. **Repository Name & Package**:
   - GitHub Repo: `zinw/airplay-tv` (standalone; credit original flymop project in README)
   - Application Package: `com.flymop.airplaytv` (do not change — overlay upgrades)
