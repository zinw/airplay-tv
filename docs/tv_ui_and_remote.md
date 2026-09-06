# Android TV UI & Remote Control UX

**AirPlay TV** is designed following Google's Android TV (Leanback) 10-foot Human Interface Guidelines, tailored specifically for D-Pad remote control interaction.

---

## 1. 10-Foot UI Architecture

* **Ambient "Ready to Connect" Screen**:
  * High-contrast, dark-mode gradient background (`#0D1117` to `#161B22`) designed for OLED and LED TVs.
  * Live status capsule displaying Bonjour/mDNS broadcast status and dynamically bound port.
  * 3-step visual connection guide with large, readable typography and modern iconography.
  * "Settings" action button with prominent focused border states (`bg_button_focusable.xml`).

* **Now Playing Music Screen**:
  * High-resolution album artwork display with rounded corners and subtle shadow borders.
  * Real-time audio amplitude visualizer (`VisualizerView.kt`).
  * Dynamic track title, artist, and album metadata parsed from AirPlay DMAP packets.

* **Modal TV Settings Dialog**:
  * Clean, D-Pad navigable overlay for instant configuration.
  * Changing any setting (Device Name, Resolution, 60fps limit, H.265, PIN, Low Latency) immediately re-announces the server over mDNS without crashing or requiring an app restart.

---

## 2. Remote Control Key Mapping Logic

The remote controller operates in a **hybrid navigation model**: Android TV standard for menus, and Apple TV standard during active streaming.

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
| Screen Mirroring     | Back / Return         | Instantly disconnects AirPlay mirroring session & returns|
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
