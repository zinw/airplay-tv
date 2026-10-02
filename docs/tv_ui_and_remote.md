# Android TV UI & Remote Control UX

**AirPlay TV** is designed following Google's Android TV (Leanback) 10-foot Human Interface Guidelines, tailored specifically for D-Pad remote control interaction.

---

## 1. 10-Foot UI Architecture

* **Ambient "Ready to Connect" Screen**:
  * High-contrast, dark-mode gradient background (`#0B0E17` to `#161B22`) designed for OLED and LED TVs.
  * Live status capsule displaying Bonjour/mDNS broadcast status and dynamically bound port.
  * 3-step visual connection guide with large, readable typography and modern iconography.
  * "Settings" action button with high-contrast text color selector (`btn_text_focusable.xml`).

* **Now Playing Music Screen**:
  * High-resolution album artwork display with rounded corners and subtle shadow borders.
  * Real-time audio amplitude visualizer (`VisualizerView.kt`).
  * Dynamic track title, artist, and album metadata parsed from AirPlay DMAP packets.

* **Modal TV Settings Dialog**:
  * Clean, D-Pad navigable overlay for instant configuration.
  * Changing any setting (Device Name, Resolution, FPS limit, H.265, PIN, Low Latency, Overscan, Advertise Audio, Allow New Connections) immediately re-announces the server over mDNS after disconnecting active sessions — without requiring an app restart.
  * Performance HUD preference is persisted across launches; Info / Blue still toggles it during mirror.
  * Mirror HUD layout: top-right green signal bars; bottom-right `MediaCodec | rec=/dec= | WxH | Wi‑Fi band`.

---

## 2. Remote Control Architecture & Apple TV UX Rationale

### Hybrid Remote UX Model
Standard Android TV applications rely on linear focus traversal between on-screen views. However, during active AirPlay streaming, users expect the behavior of an **Apple TV**:

1. **Ambient & Settings Menus**: Follows standard Android TV Leanback focus mechanics (D-Pad navigates between buttons/cards; Center/OK activates).
2. **Active Screen Mirroring**: Direct interaction model. Pressing `BACK` triggers immediate session teardown rather than navigating UI focus.
3. **HLS Web Video**: Two-tier navigation. Directional keys seek directly when controls are hidden; Up/Down reveals full on-screen controls (OSD); `BACK` dismisses OSD first before interrupting playback.

### Immediate Session Teardown (`nativeDisconnectSessions`)
In standard receivers, pressing `BACK` on Android TV merely minimizes the Activity while the native RTSP/RTP server continues receiving and decoding 20+ Mbps video in the background until the iOS sender disconnects.

In **AirPlay TV**:
* Pressing `BACK` during active mirroring invokes `NativeBridge.nativeDisconnectSessions(nativeHandle)`.
* The native C core immediately terminates the RTSP socket connection and sends a teardown notification to the iOS client.
* Frees all GPU surfaces, decoder buffers, and network resources instantly.

---

## 3. Remote Control Key Mapping Matrix

```
+---------------------------------------------------------------------------------------------------------+
| Mode                 | Remote Key            | Action Taken                                             |
+----------------------+-----------------------+----------------------------------------------------------+
| Idle / Ambient Screen| D-Pad Up/Down/Left/Rt | Navigates focus between UI elements                      |
|                      | Center / Enter / OK   | Opens Settings or clicks focused item                    |
|                      | Back / Return         | Exits app to Android TV Home Launcher                    |
+----------------------+-----------------------+----------------------------------------------------------+
| Settings Dialog Open | D-Pad Up/Down         | Moves between setting rows                               |
|                      | Center / Enter / OK   | Toggles switch or opens edit dialog                      |
|                      | Back / Return         | Closes Settings overlay, returns focus to main screen    |
+----------------------+-----------------------+----------------------------------------------------------+
| Screen Mirroring     | Back / Return         | Invokes nativeDisconnectSessions; instantly disconnects  |
|                      | Center / Play-Pause   | Sends DACP Play/Pause to iOS device                      |
|                      | Menu / Info / Blue    | Toggles Performance HUD                                  |
+----------------------+-----------------------+----------------------------------------------------------+
| HLS Web Video        | Left / Right          | Seeks backward 10s / forward 10s                         |
| (Controls Hidden)    | Up / Down             | Reveals full On-Screen Player Controller (OSD)           |
|                      | Center / Play-Pause   | Toggles Video Play / Pause                               |
|                      | Back / Return         | Stops video playback and returns to Ambient Screen       |
+----------------------+-----------------------+----------------------------------------------------------+
| HLS Web Video        | D-Pad Navigation      | Navigates between on-screen player buttons               |
| (Controls Visible)   | Center / Enter / OK   | Clicks focused player button (Rewind/FF/Next/Prev)        |
|                      | Back / Return         | Dismisses on-screen controls without stopping video      |
+----------------------+-----------------------+----------------------------------------------------------+
| Music Streaming      | Center / Play-Pause   | Toggles DACP Play / Pause                                |
|                      | Left / Right          | Skips to Previous Track / Next Track                     |
|                      | Back / Return         | Stops audio playback and returns to Ambient Screen       |
+----------------------+-----------------------+----------------------------------------------------------+
```
