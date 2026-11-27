---
tags: [architecture]
sources: [app/src/main/java/app/tgplayer/player, app/src/main/java/app/tgplayer/ui, app/src/main/java/app/tgplayer/data, app/src/main/java/app/tgplayer/local, app/src/main/AndroidManifest.xml]
updated: 2026-11-27
---

# Architecture

Three OS components, a single owned player, and a hand-rolled Compose UI over
global singletons. No ViewModels, no Navigation component, no DI, no Coil, no
Room — deliberate minimalism.

## Components (manifest)

- **`MainActivity`** → Compose shell ([[ui-compose]]).
- **`PlayerActivity`** → the video player; `singleTask`, handles rotation/PiP via
  `configChanges` (no rebuild on rotate). Exported for "Open with".
- **`PlaybackService`** (`MediaSessionService`, `foregroundServiceType=mediaPlayback`)
  → owns the single `ExoPlayer` + `CastPlayer` behind a `MediaSession`
  ([[player-engine]]).

## Playback data flow ([[player-engine]])

`PlayerActivity` is a **remote**: it drives a `MediaController` bound to
`PlaybackService`'s `SessionToken` (PlayerActivity.kt:166-176). The service owns
`ExoPlayer` (PlaybackService.kt:93-101), built with:

- `DefaultMediaSourceFactory(RoutingDataSource.Factory, CuelessMatroskaExtractor.Factory)`
- a renderers factory that injects `EqualizerProcessor` into the audio sink and
  forces dav1d (AV1) / ffmpeg (Dolby/DTS/TrueHD) extension renderers,
- `PlayableFirstLoadControl` (buffer sized from heap, gates playback on buffered
  *duration* not byte target).

`RoutingDataSource.open()` picks a delegate by URI scheme each open: `tdlib://`,
`smb://`, `ftp(s)://`, `sftp://`, `http(s)://` (read-ahead vs plain), else
`file://`/`content://`. Progressive http uses the disk read-ahead path
(`StreamCache` + `HttpDownload` + `HttpStreamDataSource`); HLS/DASH use plain
`DefaultHttpDataSource`.

## UI structure ([[ui-compose]])

`AppShell` → `MainTab` enum (VIDEO/MUSIC/BROWSE/LIBRARY/MORE) with `when(tab)`
dispatch; a `telegram` flag gates a full-screen `TelegramSection` →
`TelegramRoot` (its own HOME/CHATS/SEARCH/LIBRARY tabs). Navigation is nullable
state-swapping + `BackHandler`, not a nav graph. Screens are one-file-per-tab.
Image loading is four hand-rolled `@Composable` loaders (`TdImage`, `NetThumb`,
`WebImage`, `LocalThumb`), each with an in-memory `LruCache` + (some) SHA-1 disk
cache. Theme is dark-only "Cinema".

## Data & integrations ([[data-integrations]])

- **Persistence** — three mechanisms by data type: SQLite via plain
  `SQLiteOpenHelper` (`Store`, app lists/progress/playlists), SharedPreferences
  (`Settings`, scalars + hand-serialized JSON/newline blobs), and flat files +
  JSON on disk (`MediaLibrary`, the scanned Library catalog).
- **TDLib** — one global client on one shared daemon thread; updates arrive on
  that thread and are `tryEmit`ted into a `SharedFlow` (never blocks native).
  Coroutine bridge via `suspendCancellableCoroutine`.
- **HTTP** — raw `HttpURLConnection` + `org.json` everywhere (Jellyfin, TMDb,
  image loaders, DropIt). No OkHttp. TMDb has a 14-day disk cache.
- **LAN** — `NsdManager` discovery; smbj/commons-net/sshj for drives (pooled for
  SMB/SFTP, per-op for FTP); DropIt uses a `ServerSocket` HTTP server + a UDP
  /24 unicast beacon (routers drop client-to-client multicast — see memory).

## State & threading invariants

- Disk/SQLite → `Dispatchers.IO`; network → `Dispatchers.IO`; PBKDF2 →
  `Dispatchers.Default`. **No `GlobalScope`, no `runBlocking` on Main.**
- The only Main hop is `Telegram.ready` (TDLib `Client.create` must init on Main).
- Compose re-queries SQLite via a coarse `Store.version: StateFlow<Int>` bumped on
  every write.

For concrete performance hotspots across all layers see
[[synthesis/optimization-targets]].
