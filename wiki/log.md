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
