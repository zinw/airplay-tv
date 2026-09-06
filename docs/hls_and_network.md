# HLS Video Streaming & Network Protocol

This document outlines how **AirPlay TV** handles direct Web Video (HLS/MP4), the Apple FCUP protocol, mDNS service discovery, dynamic port allocation, and network socket tuning.

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
YouTube and protected media do not expose direct public video URLs. Instead, the AirPlay server uses **Reverse HTTP (`PTTH/1.0`)** and **FCUP (Forward Content URL Provider)** requests:

```
[iOS / YouTube Client]                   [AirPlay TV (Native C Core)]            [ExoPlayer (Kotlin)]
         │                                            │                                   │
         │──── 1. POST /play (localhost/master.m3u8) ─▶│                                   │
         │                                            │──── 2. onVideoPlay(location) ────▶│
         │◀─── 3. Reverse HTTP POST /event ──────────│                                   │
         │        (Request master playlist)           │                                   │
         │                                            │                                   │
         │──── 4. POST /action (FCUP Response Data) ─▶│                                   │
         │        (Sends raw M3U8 segments)           │                                   │
         │                                            │                                   │
         │                                            │◀─── 5. GET /master.m3u8 ──────────│
         │                                            │─── 6. Serves rewritten M3U8 ────▶│
         │                                            │                                   │
         │◀─── 7. Reverse HTTP (Fetch .ts/.m4s) ──────│◀─── 8. GET /segment_0.ts ─────────│
         │──── 9. POST /action (Segment Data) ───────▶│─── 10. Forward stream chunk ─────▶│
```

### Deadlock Resolution in HLS Streaming
* **The Problem**: In original UxPlay derivatives, the `_video_play` callback executed a 10-second blocking `pthread_cond_timedwait` waiting for ExoPlayer to signal `play_ready`. Because the `httpd` thread was blocked in this wait, it could **not** service incoming HTTP requests from ExoPlayer (`GET /master.m3u8`), causing a circular deadlock until timeout.
* **The Fix**: The blocking wait on `httpd` was completely eliminated. The native callback sets `rate = 1.0f` immediately and returns. ExoPlayer connects to `http://localhost:7000/master.m3u8` immediately, resolving the stream in under 1 second.

---

## 3. mDNS Service Discovery & Name Conflict Resolution

AirPlay TV registers two DNS-SD services using Android's native `NsdManager` via `android_dnssd_shim.c` and `NsdServiceManager.kt`:
1. `_airplay._tcp`: Port 7000 (AirPlay video, HLS handoff, and mirroring).
2. `_raop._tcp`: Port 7000 (Remote Audio Output Protocol). Format: `<MAC_ADDRESS>@<DEVICE_NAME>`.

### Dynamic Name Conflict Resolution
On Android TV and Google TV devices, system components (such as Google Cast or built-in media services) frequently register mDNS records with names identical to the system device name, causing Android's `NsdManager` to throw a `NameConflictException`.

AirPlay TV implements an **adaptive 3-tier conflict retry strategy**:
1. **Tier 1**: Attempts registration with the configured name (or system name appended with `" AirPlay"` if not present).
2. **Tier 2**: If conflict occurs, automatically retries with `" <Name> 2"`.
3. **Tier 3**: If conflict persists, attempts `" <Name> (3)"` before falling back to the guaranteed unique identifier `"AirPlay TV"`.

### Dynamic Port Fallback
If port 7000 is occupied by another system daemon (e.g. `EADDRINUSE`):
1. Native `httpd` retries binding with `port = 0` to request an ephemeral available port from the Linux kernel.
2. The allocated port is passed via JNI to Kotlin `AirPlayService`.
3. `NsdServiceManager` automatically registers the Bonjour service with the dynamically assigned port and updates the TXT records.

---

## 4. Network Socket Tuning & Buffer Optimization

AirPlay video streaming can exceed 15–30 Mbps during high-motion screen updates. To eliminate packet drops and latency spikes:
* **Socket Receive Buffer**: Configured with `SO_RCVBUF = 2 * 1024 * 1024` (2MB buffer) on all media listening sockets in `netutils.c`.
* **TCP Low-Latency**: `TCP_NODELAY` is enabled on TCP sockets to disable Nagle's algorithm and eliminate micro-stutters.
* **MulticastLock & WakeLock**: `WifiManager.MulticastLock` and `PowerManager.PARTIAL_WAKE_LOCK` are held by `AirPlayService` to ensure uninterrupted network packet reception when the device is under load.
