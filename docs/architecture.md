# System Architecture

**AirPlay TV** is engineered as a high-performance, hybrid AirPlay receiver for Android TV, combining a native C/C++ core with modern Android media and Leanback UI components.

---

## 1. High-Level Architecture Diagram

```
+---------------------------------------------------------------------------------+
|                                 iOS / macOS Sender                              |
|   (Screen Mirroring, Safari HLS Video, YouTube AirPlay)                         |
+---------------------------------------------------------------------------------+
                                         |
                       mDNS (Zeroconf) / RTSP / HTTP / RTP
                                         |
                                         v
+---------------------------------------------------------------------------------+
|                                Android TV Platform                              |
|                                                                                 |
|  +---------------------------------------------------------------------------+  |
|  |                            Application Layer                              |  |
|  |                                                                           |  |
|  |  +--------------------+   +---------------------+   +------------------+  |  |
|  |  |  MainActivity (UI) |   | AirPlayService (BG) |   | Settings Overlay |  |  |
|  |  |  - 10-foot Leanback|   | - Foreground Service|   | - SharedPreferences|  |
|  |  |  - D-Pad Navigator |   | - MulticastLock/Wake|   | - Instant Apply  |  |
|  |  |  - HUD Display     |   | - MediaSession (DACP|   |                  |  |
|  |  +--------------------+   +---------------------+   +------------------+  |  |
|  +---------------------------------------------------------------------------+  |
|                                         |                                       |
|                                    JNI Bridge                                   |
|                                         |                                       |
|  +---------------------------------------------------------------------------+  |
|  |                               Native Layer                                |  |
|  |                                                                           |  |
|  |  +----------------------+  +---------------------+  +------------------+  |  |
|  |  |  UxPlay RAOP Core    |  |  Native Audio Engine|  |  Android DNSSD   |  |  |
|  |  |  - RTSP Server       |  |  - Google Oboe      |  |  Shim            |  |  |
|  |  |  - FairPlay / ED25519|  |  - FFmpeg (ALAC)    |  |  - NsdManager    |  |  |
|  |  |  - HTTP / PTTH / FCUP|  |  - Timeline Jitter  |  |  - Name Conflict |  |  |
|  |  |  - Plist Parsers     |  |    Buffer           |  |    Resolution    |  |  |
|  |  +----------------------+  +---------------------+  +------------------+  |  |
|  +---------------------------------------------------------------------------+  |
|                                         |                                       |
|  +---------------------------------------------------------------------------+  |
|  |                              Rendering Layer                              |  |
|  |                                                                           |  |
|  |  +----------------------+  +---------------------+  +------------------+  |  |
|  |  | VideoPipeline (GL)   |  | MediaCodec Decoder  |  | Media3 ExoPlayer |  |  |
|  |  | - Dedicated GL Thread|  | - H.264 (AVC) HW    |  | - Web HLS Video  |  |  |
|  |  | - SurfaceTexture     |  | - H.265 (HEVC) HW   |  | - MP4 Direct URL |  |  |
|  |  | - VBO Quad Blit      |  | - Decoupled Surface |  | - Native Controls|  |  |
|  |  +----------------------+  +---------------------+  +------------------+  |  |
|  +---------------------------------------------------------------------------+  |
+---------------------------------------------------------------------------------+
```

---

## 2. Layer Breakdown

### A. Discovery Layer (`android_dnssd_shim.c` & `NsdServiceManager.kt`)
* Advertises two DNS-SD services over multicast DNS:
  1. `_airplay._tcp`: Port 7000 (AirPlay control, screen mirroring, and HLS handoff).
  2. `_raop._tcp`: Port 7000 (Remote Audio Output Protocol). Format: `<MAC_ADDRESS>@<DEVICE_NAME>`.
* **Automatic Conflict Resolution**: Implements retry logic if Android TV's built-in Google Cast or mDNS system holds a conflicting name, ensuring reliable zero-configuration discovery on Apple devices.

### B. Protocol & Session Core (C/C++ Layer)
* **UxPlay RAOP Core**: Handles RTSP stream setup, authentication (FairPlay, Ed25519 pairing), and RTP packet demuxing.
* **Socket Buffer Optimization**: Sockets are configured with a 2MB receive buffer (`SO_RCVBUF`) and `TCP_NODELAY` to minimize network packet loss and jitter during high-bitrate video bursts.
* **Zero-JNI Audio Engine**: Audio RTP packets are fed directly in native memory to FFmpeg and Google Oboe without crossing the JNI boundary on every frame.

### C. Background Service Layer (`AirPlayService.kt`)
* Runs as an Android **Foreground Service** (`connectedDevice | mediaPlayback`).
* Holds `WifiManager.MulticastLock` and `PowerManager.WakeLock` to prevent CPU throttling or network interface dormancy during background streaming.
* Integrates with Android's `MediaSessionCompat` for transport controls during casting sessions.

### D. Video & Rendering Pipeline
* Decoupled architecture: Native video frames $\rightarrow$ `MediaCodec` $\rightarrow$ `SurfaceTexture` $\rightarrow$ Dedicated OpenGL ES rendering thread $\rightarrow$ `SurfaceView`.
* Decoder surface changes do not trigger codec re-initialization, preventing video freeze or black screens on window transitions.
