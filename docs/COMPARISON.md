# Competitor comparison (AirPlay TV)

Feature-surface study of open-source AirPlay receivers and commercial Play Store claims.
Sources: public README/issues/marketing pages only. No binary reverse-engineering.

**Baseline:** zinw/airplay-tv (fork of flymop/airplay-tv) @ 1.0.6 — Kotlin + UxPlay-derived native core, Android TV leanback UI, Oboe audio, MediaCodec mirror + ExoPlayer HLS.

Legend: ✅ present · ◐ partial / claimed · ❌ absent · — N/A or unknown from public docs

| Capability | **AirPlay TV (us)** | jqssun/android-airplay-server | prat3ik/iMirror | mazer666/PhairPlay | unaivv/ariplay-receiver | FDH2/UxPlay (desktop) | AirScreen (claims) |
|---|---|---|---|---|---|---|---|
| Screen mirroring (H.264) | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| HEVC / H.265 mirror | ✅ | ✅ | ❌ (documented) | ❌ (H.264 focus) | — | ✅ (opt) | ◐ HW accel / UHD |
| Audio-only / music cast | ✅ ALAC/AAC + DMAP art | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| HLS / URL video cast | ✅ ExoPlayer + OSD | ✅ | ✅ stream URL | ✅ `/play` | ◐ “video streaming” | ✅ | ✅ |
| AirPlay photo receiver | ❌ deferred | — | ✅ | ✅ | ✅ claimed | ✅ | — |
| Optional PIN pairing | ✅ (off by default) | ✅ | ✅ | ✅ (+ lockout) | — | ✅ | ◐ access control |
| Resolution / FPS limits | ✅ TV settings | ✅ rich settings | — | — | — | ✅ CLI | ✅ custom resolution |
| Overscan correction | ✅ (now in TV UI) | ✅ | — | — | — | ✅ | — |
| Low-latency audio path | ◐ shared media Oboe (Exclusive/Game toggle removed 1.0.11) | ✅ Oboe (low-lat default off) | ◐ HW decode focus | ◐ AAC/ALAC | — | GStreamer | — |
| Debug FPS/bitrate HUD | ✅ persisted toggle | ✅ | ❌ | ❌ | ❌ | verbose logs | — |
| Android TV leanback UX | ✅ 10-ft D-pad | ✅ TV support | ✅ TV-first | ✅ TV/Fire TV | ✅ Google TV | Desktop | Phone/TV |
| Picture-in-Picture | ❌ deferred | ✅ Manifest | ❌ | ❌ | — | — | — |
| MediaSession / DACP remote | ✅ | ✅ | ✅ | ✅ | ◐ | ✅ | — |
| Boot / background service | ✅ | ✅ | foreground app | ✅ | — | daemon | ✅ claimed |
| Advertise audio/video split | ✅ (now in TV UI) | ✅ | ◐ mirror-audio toggle | ◐ mirror-audio toggle | — | CLI flags | — |
| Allow new conn while casting | ✅ (now in TV UI) | ✅ | — | — | — | `-nohold` | — |
| In-app OTA updates | ✅ + China mirrors | F-Droid / Play | committed APKs | GitHub Releases | — | package mgr | Play Store |
| Stable signed APKs | ✅ committed keystore | ✅ | ✅ | ✅ | debug | — | Play signing |
| i18n | ✅ en + zh-CN | en (+ JP requested) | en | en | en | en | many |
| Miracast / Google Cast | ❌ out of scope | ❌ | ❌ | ◐ control plane WIP | ❌ | ❌ | ✅ claimed |
| “Full AirPlay 2” stack | ◐ UxPlay AirPlay2 subset — **no full-stack claim** | ◐ “AirPlay 2” marketing | AirPlay mirror stack | ✅ claims complete AP2 | — | AirPlay2 client support | AirPlay (marketing) |
| Ads / subscriptions | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | freemium common |

## What they have that we still lack

- **Photo casting** (iMirror, PhairPlay, ariplay-receiver claims, UxPlay) — needs dedicated JPEG/PNG receive path; deferred.
- **Picture-in-Picture** (jqssun) — Manifest + activity lifecycle work; deferred to avoid TV OEM regressions.
- **Miracast / Cast / DLNA** (AirScreen, PhairPlay WIP) — different protocols; out of architecture scope.
- **Access-control lockout after failed PIN** (PhairPlay) — nice-to-have hardening; deferred.
- **Richer developer decode knobs** (jqssun Compose settings: operating rate, frame-drop keys, SW ALAC force) — prefs already exist in our `Prefs`/`AirPlayService`; full TV UI for every knobs would clutter the 10-ft overlay — deferred beyond latency/stability step.
- **True multi-protocol commercial surface** (AirScreen) — not a goal for this GPL AirPlay-focused fork.

## What we already do better (or uniquely well)

- **China-friendly in-app OTA** with ghproxy/ghfast fallbacks, APK ZIP validation, cancel/retry — critical for Honor / CN networks vs GitHub-only downloads.
- **Committed upload keystore** shared by debug + release so OTA overlays work across builds (post-1.0.4).
- **Honor-focused mirror recovery**: `resetForServerRestart` (rename/settings without codec-thread death), mid-GOP mosaic guards, SPS/IDR patience — documented in prior 1.0.3–1.0.5 work.
- **Oboe zero-JNI audio** with adaptive cushion + TV-visible latency vs stability control.
- **Triple-mode leanback UX** (mirror + HLS OSD + music visualizer) tuned for D-pad, not phone-first Compose lists.
- **Default PIN off** with clear home chip — matches iMirror/PhairPlay UX expectations on trusted home Wi-Fi.

## Implemented polish from this study (1.0.6)

| Polish | Competitor cue |
|---|---|
| Persist Performance HUD preference | jqssun debug overlay sticks across launches; ours did not write `DEBUG_ENABLED` |
| Expose Overscan / Allow new connections / Advertise audio / Boot start | Already wired in `Prefs` + native path; competitors surface them in UI |
| Audio stability TV control | Expose adaptive cushion step without session tear-down (`audioConfigFlow`) |
| Hot-apply low-latency toggle | **Removed in 1.0.11** — Exclusive AAudio fought FLUSH recovery on Honor; UxPlay-style shared media path only |
| Disconnect before settings restart | Cleaner session teardown vs hanging iOS sockets after rename/codec changes |
| Richer HUD (resolution + connection count) | Match competitor debug overlay usefulness |
| User guide + comparison matrix | Docs for install / troubleshooting / deferred scope |

## Explicitly deferred (and why)

| Item | Why deferred |
|---|---|
| Rewrite onto wholesale UxPlay Android port | Architecture already UxPlay-derived; rewrite risk >> polish value |
| Claim “full AirPlay 2” | Buffered audio type 103, HomeKit pairing depth, etc. not verifiable in-scope |
| PiP | OEM TV PiP quirks; not required for primary leanback casting flow |
| Photo receive | New protocol surface + UI; medium risk |
| Miracast / Cast / recording / ads | Different product; skip commercial copycats |
| Every jqssun developer toggle in TV UI | Prefs wired; clutter vs 10-ft clarity |

Commercial notes (AirScreen and similar): public claims emphasize multi-protocol (AirPlay + Cast + Miracast + DLNA), background service, custom name/resolution, hardware acceleration, UHD, access control, and often freemium/Play distribution. We match the AirPlay receiver core UX goals without mirroring ads, subscriptions, or non-AirPlay protocols.
