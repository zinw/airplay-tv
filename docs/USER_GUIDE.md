# AirPlay TV — user guide

Short guide for installing and using **AirPlay TV** on Android TV / Google TV.

## Install

1. Download a release APK from [GitHub Releases](https://github.com/zinw/airplay-tv/releases) (prefer the **universal** build if unsure of CPU ABI).
2. Sideload with ADB (`adb install -r …`) or a TV file manager / Downloader app.
3. From **1.0.4** onward, updates share one signing key and can overlay. If you are on **1.0.1–1.0.3**, uninstall once before installing a newer build.

In-app updates (Settings / home status chip) check GitHub Releases and, on restricted networks, retry via China-friendly mirrors.

## Connect

1. Keep the TV and Apple device on the **same Wi-Fi** (AP isolation / client isolation must be off; multicast/mDNS allowed).
2. Launch **AirPlay TV** and leave the Ready screen open (or keep the foreground service running).
3. On iPhone/iPad/Mac: Control Center → **Screen Mirroring** → select this TV’s device name (video + synced audio).
4. Optional: turn on **PIN Pairing** from the home chip or Settings if the network is shared.

Standalone / audio-only AirPlay (music speaker target) is **not** advertised and is rejected if a client tries that path.

## Settings that matter

| Setting | When to change it |
|---|---|
| Device name | Rename how the TV appears in AirPlay lists |
| Performance HUD | Top-right Wi‑Fi bars + bottom-right `decoder | rec/dec | WxH | band` (Info / Blue also toggles) |
| Audio stability | Cycles toward a larger adaptive cushion (more stable, slightly higher latency); hot-applied |
| H.265 / HEVC | Off on chipsets that glitch on HEVC mirror |
| Resolution / Max FPS | Cap for weak SoCs or busy 2.4 GHz Wi-Fi |
| Overscan correction | If edges of the mirrored image are clipped by the TV panel |
| Allow new connections | Let another Apple device take over while one is casting |
| PIN pairing | Require a 4-digit code shown on the TV |
| Start on boot | Auto-start the receiver service after power-on |
| Language | System / English / 简体中文 |

Changing name, codecs, resolution, FPS, PIN, overscan, or allow-new-connection **restarts the AirPlay server**. Active sessions are disconnected; cast again from the Apple device.

## Troubleshooting

- **TV not listed**: same SSID; disable AP isolation; try Ethernet or 5 GHz; reboot router/TV.
- **Black screen after rename/settings**: remirror once; from 1.0.5 the codec path resets without force-stop — if still black, force-stop the app and reopen.
- **Mosaic / corrupted frames**: wait for a keyframe or remirror; mid-GOP recovery is improved in 1.0.5+.
- **Audio crackle on Mac**: raise **Audio stability** and remirror.
- **Netflix / Apple TV+ / Disney+**: FairPlay DRM — not decryptable by open-source receivers (same limitation as iMirror / PhairPlay / UxPlay).
- **OTA fails in China**: use in-app update (mirrors) or download the APK on a phone and sideload.

## Remote cheatsheet

During mirroring: **Back** disconnects; **OK** play/pause on the phone; **Info/Blue** toggles the HUD.  
HLS video: Left/Right seek ±10s; Up/Down show player controls.

More detail: [tv_ui_and_remote.md](tv_ui_and_remote.md), [building_and_debugging.md](building_and_debugging.md), [COMPARISON.md](COMPARISON.md).
