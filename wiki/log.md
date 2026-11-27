# Wiki log

Append-only history of wiki updates. Past entries are never edited; correct with
a new entry.

## [2026-11-27] init | Wiki scaffolded
Created wiki from a full codebase scan of the Development branch (via three
parallel subsystem maps: player engine, UI/Compose, data+integrations). Pages: 9
(overview, architecture, conventions, dependencies; entities player-engine,
ui-compose, data-integrations; synthesis/optimization-targets; index).

Key findings:
- Focused single-module app: ~10k lines of real Kotlin (Compose + Media3);
  `org/drinkless/tdlib/TdApi.java` (~164k lines) is generated, not hand-edited.
- Deliberate minimalism: no ViewModels, no Navigation component, no Coil, no
  Room, no DI. State lives in global singletons + a `Store.version` StateFlow.
- One owned `ExoPlayer` in `PlaybackService`; `RoutingDataSource` picks a delegate
  per URI scheme; disk read-ahead for progressive http.
- Threading is clean: no `GlobalScope`, no `runBlocking` on Main.
- Build: no R8/minify/shrink; ABI already lean (two ARM ABIs); minimal
  `gradle.properties`.
- Ranked optimization targets captured in synthesis/optimization-targets (drives
  the CodeOptimization branch): grid recomposition, read-ahead range store,
  per-item config JSON re-parse, library-scan TMDb, equalizer inner loop; APK
  size via R8 (deferred, needs device test) and icon trim.

## [2026-11-27] opt | CodeOptimization branch — safe optimizations landed
Implemented on branch `CodeOptimization` (off Development); `./gradlew :app:testDebugUnitTest
:app:assembleDebug` green, all unit tests pass.
- OPT-2: extracted the [[player-engine]] read-ahead range store into a pure `RangeSet`
  (player/RangeSet.kt) — O(1) contiguous-tail append + running `downloaded` total; added
  `RangeSetTest` (6 tests). StreamCache.kt now delegates.
- OPT-3: `Jellyfin.server` and `NetworkDrives.all()` cache their parsed value keyed on the raw
  JSON string (re-parse only when it changes).
- OPT-4: `Tmdb.genres()` memoized; `MediaLibrary.build()` is now `suspend` with bounded-parallel
  TMDb (Semaphore, 6); `Tmdb.get` uses a unique temp file so parallel writers can't collide.
- OPT-5: `EqualizerProcessor.queueInput` bulk-copies via `ShortArray`/`FloatArray` (identical
  output; `EqualizerProcessorTest` still passes).
- OPT-1 (part): `MediaItem`/`ChatItem` `@Immutable`, `MediaActions` `@Stable`; `TdImage`
  mini-thumb decode moved off the main thread. Per-item download-progress scoping deferred.
- OPT-10: gradle.properties parallel/caching/configuration-cache.
Deferred: OPT-6, OPT-7, OPT-8 (R8). OPT-9 not feasible (see [[synthesis/optimization-targets]]).

## [2026-11-27] audit | Full Ponytail audit + must-fixes landed
Ran a whole-repo four-pillar audit (three parallel passes). Repo healthy; 18 findings. Verified
sound (kept as-is): Secrets canary, TelegramLock (constant-time, 150k PBKDF2), SFTP TOFU, CastServer
(token-gated, LAN-only, no traversal), all deps used (media3-hls/dash load reflectively). The OPT
changes were re-verified clean.

Fixed the 5 must-fixes (+ the clear() race) — build + unit tests green:
- Downloads crash: `Pager.refresh()` now `distinctBy { it.key }` (file-based downloads vs message-based
  key gave duplicate LazyGrid keys). MediaGrid.kt.
- fd leak: `HttpDownload.close()` added, called from `StreamCache.trim()`/`clear()` before deleting the
  file; `clear()` is now `@Synchronized`. StreamCache.kt.
- Jellyfin cleartext: `signIn` tries https first, falls back to http only on a connection failure (never
  on a 401). Jellyfin.kt.
- Data loss: `matches.json`/`files.txt`/`telegram.json` now written via temp+rename
  (`File.writeTextAtomically`), matching Tmdb.get. MediaLibrary.kt.
- OOM: TdImage/NetThumb/LocalThumb caches byte-sized (maxMemory/8 + sizeOf) like WebImage.

## [2026-11-27] audit | Should-fixes landed
Build + unit tests green.
- #6 catalog clobber: `load()`'s standalone offline build re-checks `_catalog` after building and only
  publishes if still null, so a concurrent `scan()` catalog isn't overwritten with stale. MediaLibrary.kt.
- #8 DropIt progress: `_receiving` updated via `MutableStateFlow.update {}` (atomic) at all three sites.
- #9 DropIt `/ask`: untrusted body parsed with runCatching → 400, `files` capped at 1000 and parsed
  with opt* (no per-field throw). DropIt.kt.
- miss-set leak (part of #10): `noThumb`/`noNetThumb` are now bounded `LruCache(512)`, not growing HashSets.

Open: nice-to-haves (#13 sha1 util, #14 http error typing, #15 bare close, #16 LIMIT bind, #17 Settings
JSON, #18 wiki security page) and #12 queue-key (needs a composite key or duplicate mediaId re-crashes
like #1).

## [2026-11-27] refactor | Consolidation refactors (#10, #11) landed
Build + unit tests green; behaviour preserved (wants on-device confirmation of scroll/player redraw).
- #10 shared `ThumbBox` (ui/ThumbBox.kt): the state-seed + LaunchedEffect + Box/Image/placeholder
  skeleton the single-bitmap loaders shared now lives once; WebImage/LocalThumb/NetThumb became a
  `seed` + `load` lambda (each keeping its own cache, disk/net fetch, Semaphore, miss-set). TdImage
  stays bespoke (its blurred mini-thumb underlay is a two-layer render only it needs — not forced in).
- #11 `rememberPlayerEvents(player)` (in MusicPlayerScreen.kt): the identical `version++` Player.Listener
  DisposableEffect that MusicPlayerScreen and MiniBar both had is now one helper. PlayerOverlay is left
  as-is (poll-only, no listener to dedup).
