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
