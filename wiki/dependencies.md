---
tags: [dependencies, build]
sources: [gradle/libs.versions.toml, app/build.gradle.kts, gradle.properties]
updated: 2026-11-27
---

# Dependencies & build

Version catalog: `gradle/libs.versions.toml`. Build: `app/build.gradle.kts`.

## Toolchain

| Thing | Version | Note |
|---|---|---|
| AGP | 9.5.1 | AGP 9 compiles Kotlin itself (built-in Kotlin) |
| Kotlin / Compose compiler plugin | 2.4.21 | only the Compose compiler plugin is added |
| Compose BOM | 2026.11.01 | Compose 1.13; needs compileSdk 37.2 |
| Media3 | 1.11.1 | exoplayer, ui, session, hls, dash, cast |
| Java | 17 | source/target |

## Libraries

- **Media3** (`exoplayer`, `ui`, `session`, `hls`, `dash`, `cast`) — playback,
  media session, adaptive streaming, Chromecast.
- **Compose** — `material3`, `material-icons-extended` (⚠ large: thousands of
  vector icons; see [[opt-apk-size]]), `activity-compose`.
- **androidx.annotation** — TDLib's generated `TdApi.java` uses `@IntDef`.
- **smbj** 0.15.0 (`com.hierynomus`) — SMB2/3 for network drives.
- **commons-net** 3.13.0 — FTP/FTPS.
- **sshj** 0.41.2 (`com.hierynomus`) — SFTP.
- **BouncyCastle** — pinned to 1.86 for all modules via `resolutionStrategy`
  (smbj and sshj pull different releases; classes moved between jars in 1.85).
- **junit** 4.13.2 — test only.

## Native (`app/src/main/jniLibs/`)

`arm64-v8a` + `armeabi-v7a` only (no x86). Each ABI ships:
`libtdjni.so` (TDLib), `libdav1dJNI.so` (AV1 video), `libffmpegJNI.so`
(Dolby/DTS/TrueHD audio). Custom Media3 renderers for these live in
`androidx/media3/decoder/{av1,ffmpeg}/`.

## Build facts relevant to optimization

- **No R8/minify, no resource shrinking** — `buildTypes.release` sets only
  `signingConfig`. No `proguard-rules.pro` in the repo. See [[opt-apk-size]].
- **No ABI splits** — but ABI set is already minimal (both ARM ABIs needed:
  Redmi-class 32-bit devices need `armeabi-v7a`).
- `gradle.properties` is minimal (`-Xmx4g`, `useAndroidX=true`) — missing common
  build-speed flags (parallel, caching, configuration-cache). See [[opt-build]].
- `packaging.resources.excludes` strips duplicate BouncyCastle licence files.

See [[opt-apk-size]], [[opt-build]].
