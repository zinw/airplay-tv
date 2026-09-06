# HLS Video Streaming & Network Protocol

This document outlines how **AirPlay TV** handles direct Web Video (HLS/MP4), the Apple FCUP protocol, and network socket tuning.

---

## 1. Two Video Streaming Paradigms

AirPlay handles video in two fundamentally different modes:

| Mode | Screen Mirroring | HLS / Web Video Playback |
| :--- | :--- | :--- |
| **Protocol** | RTSP / RTP (H.264 / HEVC) | Direct URL / HLS Playlist (`.m3u8`) |
| **Decoder** | `MediaCodec` + `VideoPipeline` | `androidx.media3.exoplayer` (ExoPlayer) |
| **Source** | iPhone screen capture encoder | Public CDN or Reverse HTTP local proxy |
| **Playback Control** | Passive stream display (DACP) | Full timeline scrub, seek, rate, duration |

---

## 2. Direct Web URLs vs. YouTube FCUP Reverse Proxy

### Direct URLs (Safari, Bilibili, Web MP4s)
1. iOS client sends `POST /play` containing `Content-Location: https://.../video.mp4` and `Start-Position: X`.
2. Native layer signals `onVideoPlay` to Kotlin.
3. Media3 ExoPlayer prepares the URL and begins streaming immediately.
4. Player reports timeline status (`readyToPlay = true`, `rate = 1.0f`) back to iOS so the sender's Control Center updates immediately.

### Protected Streams & YouTube (FCUP Protocol)
YouTube does not expose direct public video URLs. Instead, the AirPlay server uses **Reverse HTTP (`PTTH/1.0`)** and **FCUP (Forward Content URL Provider)** requests:
1. iPhone sends `POST /play` with `http://localhost:7000/master.m3u8`.
2. Native server sends a Reverse HTTP `POST /event` to the iPhone requesting the master playlist.
3. iPhone fetches playlist segments from YouTube and responds via `POST /action` (`FCUP_Response_Data`).
4. Native server rewrites URIs and serves the master playlist locally to Android's ExoPlayer via `http://localhost:7000`.
5. **Deadlock Prevention**: The `_video_play` callback immediately starts ExoPlayer and yields the `httpd` thread so it can immediately respond to ExoPlayer's `GET /master.m3u8` request without blocking.

---

## 3. Network Socket Optimization

AirPlay video streaming can exceed 15–30 Mbps during high-motion screen updates. To eliminate packet drops and latency spikes:
* **Socket Receive Buffer**: Configured with `SO_RCVBUF = 2 * 1024 * 1024` (2MB buffer) on all media listening sockets.
* **TCP Low-Latency**: `TCP_NODELAY` is enabled on TCP sockets to disable Nagle's algorithm and eliminate micro-stutters.
* **MulticastLock & WakeLock**: `WifiManager.MulticastLock` and `PowerManager.PARTIAL_WAKE_LOCK` are held by `AirPlayService` to ensure uninterrupted network packet reception when the device is under load.
