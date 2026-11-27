---
tags: [distribution, fdroid, build]
sources: [app/src/main/jniLibs, app/build.gradle.kts, gradle/libs.versions.toml, app/src/main/java/app/tgplayer/player/PlaybackService.kt, app/src/main/AndroidManifest.xml]
updated: 2026-11-27
---

# F-Droid readiness

**Status: not F-Droid-ready.** Two hard blockers, both verified against the code and
F-Droid's policy (2026-11-27). This page records what each needs so it can be picked
up later. It is analysis only — no code has been changed for F-Droid.

## What F-Droid requires

F-Droid compiles every app **from source** on its own infrastructure and ships only
what it built. Per the [Inclusion Policy](https://www.f-droid.org/en/docs/Inclusion_Policy/):

- **No prebuilt binaries** except from *trusted* sources — Debian `main`, or trusted
  Maven repos (Maven Central, Google Maven, Sonatype, JitPack, Clojars). A binary
  checked into the app's own repo is none of those.
- **No proprietary dependencies.** Every dependency must be free software and buildable
  from source (or a trusted FLOSS Maven artifact).

Neither is about TDLib being "banned" — TDLib is free software (Boost license).
It is *how* we ship it, plus the Cast dependency.

## Blockers

| # | Blocker | Evidence | Why it fails | Severity |
|---|---------|----------|--------------|----------|
| B1 | Prebuilt native `.so` checked into the repo | `app/src/main/jniLibs/{arm64-v8a,armeabi-v7a}/` → `libtdjni.so`, `libdav1dJNI.so`, `libffmpegJNI.so` | Binaries not from Debian/trusted Maven → must be built from source | **Hard** |
| B2 | Google Cast = proprietary dependency | `media3-cast` (gradle/libs.versions.toml, app/build.gradle.kts:105); `CastPlayer`/`RemoteCastPlayer` (PlaybackService.kt:120,126) | Cast SDK is part of Google Play Services, closed-source. Jellyfin had to **remove casting** for its F-Droid build | **Hard** |
| B3 | Telegram `api_id`/`api_hash` injected at build | build.gradle.kts reads `local.properties`, XOR-masks into BuildConfig | F-Droid's build has no `local.properties`; the build `error()`s without the keys. Obfuscation also runs against reproducible-build expectations | Medium |

Not a blocker: the generated `TdApi.java` / `Client.java` are source (fine); the
in-app LAN **CastServer** (our own HTTP server, `player/CastServer.kt`) is our code,
not the Google SDK — but it only exists to feed the Cast receiver, so it is dead once
B2's Cast path is removed. `usesCleartextTraffic=true` (manifest) is allowed, at most
an anti-feature note.

## Workstream A — build the native libs from source

Each `.so` needs an F-Droid build recipe that compiles it from upstream source instead
of shipping the prebuilt blob (remove `jniLibs/*.so` from the F-Droid build path).

- **TDLib** (`libtdjni.so`) — the heavy one: a large C++ build (OpenSSL, zlib). This is
  the step F-Droid maintainers have historically found painful for TDLib apps. [likely]
- **dav1d** (`libdav1dJNI.so`) — dav1d is BSD-licensed; buildable, our JNI wrapper too. [likely]
- **ffmpeg** (`libffmpegJNI.so`) — must be an **LGPL** FFmpeg build (no GPL/nonfree
  components) to stay FLOSS-clean. [likely — confirm the build flags we used]

Confirm each upstream version/commit and license, then write the `.fdroid.yml` / metadata
build steps. See [[dependencies]] for the current versions.

## Workstream B — a Cast-free build flavor

Remove the proprietary Cast dependency for F-Droid without losing it on the Play/APK build:

- Add a product flavor (e.g. `foss` vs `full`); put `media3-cast` and the
  `CastPlayer`/`RemoteCastPlayer` wiring behind the non-FOSS flavor.
- The player already owns a plain `ExoPlayer`; the Cast path wraps it
  (PlaybackService.kt:120-132), so the FOSS flavor just uses the local player.
- `CastServer`/`CastItemConverter`/`CastTracks` move behind the same flavor (dead without Cast).
- Precedent: [Jellyfin's F-Droid build removed casting](https://jellyfin.org/posts/android-on-fdroid) for exactly this reason.

## Secondary

- **B3 keys:** let the FOSS build read `api_id`/`api_hash` from F-Droid build config, or
  ship with empty keys and surface a "bring your own API key" path. Drop the XOR masking
  for that build (it hides nothing in a reproducible build and the scanner may flag it).

## Checklist

- [ ] Remove `jniLibs/*.so` from the F-Droid build; add source-build recipes for TDLib, dav1d, ffmpeg (A)
- [ ] Confirm each native lib's upstream commit + FLOSS license (esp. FFmpeg = LGPL build) (A)
- [ ] Add a FOSS product flavor; gate `media3-cast` + Cast classes behind the non-FOSS flavor (B)
- [ ] Move `CastServer`/`CastItemConverter`/`CastTracks` behind the same flavor (B)
- [ ] Provide/zero the Telegram API keys for the F-Droid build; drop masking there (B3)
- [ ] Write `.fdroid.yml`/metadata; verify a reproducible build locally with `fdroid build`

## Not verified / open

- `media3-cast`'s POM wasn't inspected directly; "it pulls `play-services-cast-framework`
  transitively" is from the Cast SDK being Play Services + the Jellyfin precedent, not the
  dependency tree. Conclusion (Cast = proprietary → exclude) holds either way. [verified by precedent]
- Exact FFmpeg build flags / licence of our prebuilt `libffmpegJNI.so` — confirm GPL-free.
- F-Droid policy read on 2026-11-27; stable but confirm at submission time.

Sources: [F-Droid Inclusion Policy](https://www.f-droid.org/en/docs/Inclusion_Policy/) ·
[Jellyfin on F-Droid](https://jellyfin.org/posts/android-on-fdroid) ·
[Google Cast SDK setup](https://developers.google.com/cast/docs/android_sender_setup).

Related: [[dependencies]], [[architecture]], [[player-engine]].
