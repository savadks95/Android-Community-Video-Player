---
tags: [entity, ui, compose]
sources: [app/src/main/java/app/tgplayer/ui, app/src/main/java/app/tgplayer/player/PlayerControls.kt, app/src/main/java/app/tgplayer/player/MusicPlayerScreen.kt]
updated: 2026-11-27
---

# UI / Compose (`app.tgplayer.ui` + player screens)

Single-activity Jetpack Compose + Material3. **No Navigation component, no
ViewModels, no Coil.** Plain Compose state + `produceState`/`collectAsState` over
global singletons (`Store`, `Telegram`, `MediaLibrary`, `Settings`, `AudioFx`).

## Navigation

- `AppShell` (AppShell.kt:91) — `MainTab` enum + `when(tab)` dispatch;
  `rememberSaveable selected`; `telegram` flag gates `TelegramSection`.
- `TelegramRoot` (TelegramRoot.kt:94) — nested shell, HOME/CHATS/SEARCH/LIBRARY;
  nullable `openChat`/`sheetItem` select screens; `PillNavBar` animates in the
  draw phase (no recomposition).
- `Pager<C>` (MediaGrid.kt:65) — `@Stable` cursor-paging holder; `LiveMessages`
  prepends/removes from the TDLib update flow.
- Screens are one file per tab: `LocalScreens` (Video/Browse), `MusicScreens`,
  `LibraryScreens`, `JellyfinScreens`, `MoreScreen`, `DropItScreen`; Telegram
  screens in `Screens.kt`; shared tiles in `Components.kt`.

## Image loading (hand-rolled, four loaders)

Each is a `@Composable` with a `remember`-seeded state + `LaunchedEffect` decode:
`TdImage` (TDLib), `NetThumb` (SMB/FTP/SFTP frames via `MediaMetadataRetriever`,
`Semaphore(2)`), `WebImage` (HTTP via `HttpURLConnection`), `LocalThumb`
(on-device). Each keeps an in-memory `LruCache<ImageBitmap>`; NetThumb/WebImage
add a SHA-1 disk cache; negative-result `HashSet`s avoid retrying misses.
`SponsoredAds` renders **text only** (no image).

## State

Global singletons + `Store.version: StateFlow<Int>` (bumped per write) drive
re-query via `produceState(..., version)`. Theme = `object Cinema`, dark-only.

## Hotspots (see [[synthesis/optimization-targets]])

Dominant compounding issue on the Telegram grid: `MediaItem`/`ChatItem` are
Compose-**unstable** (carry `TdApi.File?` + `ByteArray?`, no `@Immutable`), so
cards never skip (Library.kt:13-57); `MediaActions` is rebuilt on a 2 s download
poll (TelegramRoot.kt:103-118) forcing the whole grid to recompose; `TdImage`
decodes the mini-thumb JPEG in a `remember` on the **main thread**
(TdImage.kt:38). Also: uncached `filter`/map-merge in `VideoTab`
(LocalScreens.kt:166-173), whole-tree `version++` + 250 ms polls in the player
screens (MusicPlayerScreen.kt:110-127, PlayerControls.kt:146-158), unkeyed
`QueueSheet` reading the player per item.

Already good: most lists are keyed, `derivedStateOf` paging, off-main decodes in
the non-Td loaders, draw-phase nav animation.
