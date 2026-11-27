---
tags: [conventions]
sources: [app/build.gradle.kts, app/src/main/java/app/tgplayer, README.md]
updated: 2026-11-27
---

# Conventions

## Package layout (`app/src/main/java/`)

- `app.tgplayer` — app entry (`MainActivity`), `Telegram.kt`, `LoginScreen.kt`,
  `Secrets.kt`, `TdException`.
- `app.tgplayer.player` — Media3 playback: activity, service, DataSources,
  routing, caching, equalizer, cast, extractor, subtitles.
- `app.tgplayer.ui` — Compose screens, navigation shell, theme, image widgets.
- `app.tgplayer.data` — persistence (`Store`), `MediaLibrary`/`Library`,
  `Settings`, `Jellyfin`, `Tmdb`, `Naming`, `TelegramLock`.
- `app.tgplayer.local` — on-device media + LAN: `DropIt`, `NetworkDrives`,
  `ServerDiscovery`, `LocalMedia`.
- `org.drinkless.tdlib` — **generated** TDLib bindings (`TdApi.java` ~164k
  lines). Do not hand-edit. `Client.java` is the TDLib client.
- `androidx.media3.decoder.{av1,ffmpeg}` — custom Media3 renderers wrapping the
  bundled native decoders.
- `app.tgplayer` under `src/debug/` — `DemoActivity` (debug-only).

## Style

- **Kotlin**, Jetpack Compose, Material3. Java only for generated/decoder code.
- **Comments carry the "why"** — the codebase documents non-obvious decisions
  inline (see build.gradle.kts, AndroidManifest.xml). Match that density.
- **`tails:` comments** mark deliberate simplifications with their ceiling and
  upgrade path (e.g. build.gradle.kts:26 key obfuscation, :70 debug signing).
  Keep this convention when simplifying.
- Secrets never hard-coded: read from `local.properties`, masked into
  `BuildConfig`, unmasked at runtime.
- Files are small and single-purpose; prefer adding to the right package over
  new top-level files.

## Git / release

- Branch flow (project): feature branch → fast-forward into `Development` →
  push; releases cut from `Development` (debug-signed APK). `main` is the
  default/PR branch and is checked out in a separate worktree.

## Not negotiable (LLM_Tails)

Input validation at trust boundaries (network sources, subtitle/URL parsing),
data-loss handling, security (the secrets masking, `allowBackup=false`),
accessibility. Never simplify these away.
