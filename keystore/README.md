# Sideload upload keystore (AirPlay TV)

Stable signing key for `com.flymop.airplaytv` used by **debug and release** builds
(`assembleDebug` / `assembleRelease`) and CI.

## Why this exists

Releases **v1.0.1**, **v1.0.2**, and **v1.0.3** each set
`signingConfig = signingConfigs.debug`, which signs with the builder’s
ephemeral `~/.android/debug.keystore`. Those three APKs have **three different**
Android Debug certificates, so overlays (`adb install -r` / in-app OTA) fail with
signature mismatch until the old app is uninstalled.

This keystore is committed so every future build (1.0.4+) shares one cert.

## Credentials

| Field | Value |
| --- | --- |
| File | `airplaytv-upload.keystore` |
| storePassword | `airplaytv` |
| keyPassword | `airplaytv` |
| keyAlias | `airplaytv` |

Cert SHA-256:

```
28:AF:10:E2:DB:3F:AA:61:F3:4B:E8:57:72:54:80:4D:48:6E:55:E7:D9:B5:E7:94:56:95:90:6D:F0:15:6E:CE
```

Verify:

```bash
keytool -list -v -keystore keystore/airplaytv-upload.keystore -storepass airplaytv
```

## Upgrade notes

- **1.0.4 → later**: overlay OK (same key).
- **1.0.1 / 1.0.2 / 1.0.3 → 1.0.4**: uninstall once, then install 1.0.4+ (cannot reuse any prior Release key — private keys were never saved).
