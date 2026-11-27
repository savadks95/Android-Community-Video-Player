---
tags: [overview]
sources: [README.md, app/src/main/AndroidManifest.xml, app/build.gradle.kts]
updated: 2026-11-27
---

# Overview

TG Player ("A Video Player by Android Community") is a video and music player
for Android phones and Android TV. One player, many sources:

- **Phone storage** — every local video and song, Continue-watching + resume.
- **Telegram** — stream video/audio from chats and channels via TDLib; search,
  favourites, watch-later, offline downloads.
- **Home network** — SMB (Windows/NAS), FTP/FTPS, SFTP; servers auto-discovered.
- **Jellyfin** — browse server, synced progress, Skip-intro.
- **Web** — MP4/MKV links, HLS, DASH; downloaded ahead so they don't stall.
- **Library** — movie/show folders become a Jellyfin-style library with TMDB
  posters, ratings, genres, *Next up*, *Fix match*.

Player features: seek/brightness/volume/zoom gestures, PiP, 10-band equalizer,
+10 dB boost, bundled Dolby/DTS/TrueHD decoders (ffmpeg) and AV1 (dav1d),
auto + OpenSubtitles subtitles, Chromecast/Google TV cast, sleep timer, A-B
repeat, 0.5×–3× speed.

## Shape of the app

- **Single-module** Android app, `app/`, applicationId `app.tgplayer`.
- **Kotlin + Jetpack Compose** (Material3) UI; **Media3/ExoPlayer** playback.
- minSdk 26, targetSdk 37, ARM-only (`arm64-v8a` + `armeabi-v7a`).
- Three OS components (see [[architecture]]): `MainActivity` (Compose shell),
  `PlayerActivity` (the player), `PlaybackService` (Media3 session / background
  audio foreground service).
- Privacy stance: history/favourites/settings stay on device; `allowBackup=false`
  (TDLib DB holds the Telegram login); no account needed for local playback.

## Key constraints (from manifest / build)

- `largeHeap=true` — room for the player's ~15-minute buffer.
- `usesCleartextTraffic=true` — home media servers often speak plain `http://`.
- Release build is signed with the **debug** key by owner's choice (installs over
  existing debug builds). `tails:` noted in build.gradle.kts:70.
- Telegram api id/hash, TMDB and OpenSubtitles keys come from git-ignored
  `local.properties`, XOR-masked into `BuildConfig` (Secrets.kt unmasks).

See [[architecture]], [[dependencies]], [[conventions]].
