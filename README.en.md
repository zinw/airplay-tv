# AirPlay TV

Open-source high-performance **AirPlay receiver** for Android TV & Google TV.

This is the English-only mirror of the bilingual [README.md](README.md). Prefer that file on GitHub for badges, screenshots, and Mermaid diagrams.

## Features

- Screen mirroring (H.264 / H.265, synced audio via Oboe)
- HLS / web video casting (Safari, Bilibili, YouTube via FCUP)
- Android TV Leanback UI, PIN pairing, in-app OTA from GitHub Releases
- No dedicated audio-only / music Now Playing UI (`_raop._tcp` still advertised for mirror audio)

## Devices

Tested on **Honor Smart Screen X1 (LOK-350)**. Prefer **arm64-v8a**. Also: `armeabi-v7a`, `x86_64`, universal. Min API 26.

## Install

Download from [Releases](https://github.com/zinw/airplay-tv/releases):

```bash
adb install -r AirPlayTV-<version>-arm64-v8a.apk
```

Overlay works from **1.0.4+** (same upload keystore). Uninstall once if upgrading from **1.0.1–1.0.3**.

## Build

```bash
git clone --recursive https://github.com/zinw/airplay-tv.git
cd airplay-tv
./gradlew assembleRelease
```

Do not change `applicationId` (`com.flymop.airplaytv`) or the app name. See [docs/building_and_debugging.md](docs/building_and_debugging.md).

## Release (tag → Actions → GitHub Release)

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Commit and push to `main`.
3. Tag and push — tag must equal `versionName`:

```bash
git tag v1.0.24
git push origin v1.0.24
```

CI (`.github/workflows/release.yml`) verifies the tag, builds signed APKs, and publishes assets such as `AirPlayTV-1.0.24-arm64-v8a.apk` (required by in-app OTA). Dry-run: `gh workflow run release.yml --ref <branch>`.

## Credits & license

Based on [flymop/airplay-tv](https://github.com/flymop/airplay-tv); [UxPlay](https://github.com/FDH2/UxPlay), [android-airplay-server](https://github.com/jqssun/android-airplay-server), Oboe, FFmpeg, libplist. **GPL-3.0**.
