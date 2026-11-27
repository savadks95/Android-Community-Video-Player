---
tags: [synthesis, optimization, performance]
sources: [wiki/entities/player-engine.md, wiki/entities/ui-compose.md, wiki/entities/data-integrations.md]
updated: 2026-11-27
---

# Optimization targets (ranked)

Hotspots found by reading the three subsystems (see entity pages). Each: impact ×
risk × ladder rung. **Verify against code before editing** (LLM_Tails). This is a
snapshot — update `status` as items land.

Legend — rung: the lowest ladder step that fixes it. status: `open` /
`proposed` / `done` / `deferred`.

## Tier 1 — runtime, high impact, contained, verifiable

### OPT-1 · Telegram grid recomposition storm · impact HIGH · risk MED
Unstable data + poll-driven object churn + main-thread decode compound on scroll.
- `MediaItem`/`ChatItem` carry `TdApi.File?`+`ByteArray?`, no `@Immutable` → cards
  never skip (Library.kt:13-57). `MediaActions` rebuilt on a 2 s download poll
  (TelegramRoot.kt:103-118). `TdImage` decodes mini-thumb JPEG in `remember` on
  **main thread** (TdImage.kt:38). `VideoTab` filter/map-merge uncached
  (LocalScreens.kt:166-173).
- Fix: mark stable holders `@Immutable`; stabilize/avoid per-poll `MediaActions`
  identity churn; move mini-thumb decode off-main (`produceState`/IO); `remember`
  the `VideoTab` derivations. Rung: reuse Compose idioms already in the file.
- Verify: Compose compiler skippability (layout-inspector/recomposition counts) +
  manual scroll smoothness on device.

### OPT-2 · Read-ahead range store is O(n log n)/O(n) on the hot path · HIGH · LOW-MED
- `addRange` sorts+rebuilds the whole list per 128 KB write (StreamCache.kt:264-274);
  `downloadedBytes`/`available`/`nextMissing` linear-scan under lock on every 40 ms
  poll (StreamCache.kt:133-287, HttpStreamDataSource.kt:62); `.ranges` re-read and
  rewritten per file × fragmentation (StreamCache.kt:78-83,289-293).
- Fix: append-and-coalesce-at-tail (O(1) common case), keep a running
  downloaded-bytes counter, cache per-file size for `trim`. Rung: one function +
  a field, pure logic.
- Verify: **new unit test** for the range store (merge/contiguous/out-of-order) —
  pure Kotlin, no Android. Leaves a runnable check.

### OPT-3 · Config JSON re-parsed per item · impact MED · risk LOW
- `NetworkDrives.all()` parses `networkDrivesJson` on every file op
  (NetworkDrives.kt:82-89); `Jellyfin.server` parses `jellyfinJson` on every
  poster (Jellyfin.kt:56-59).
- Fix: parse once, cache, invalidate on `save()`/sign-in. Rung: a cached field.
- Verify: unit/read-through test; unchanged behaviour.

## Tier 2 — runtime, medium

### OPT-4 · Library scan: TMDb sequential + genre re-parse + double build · MED · LOW→MED
- `Tmdb.genres()` re-reads+parses the genre file per title (Tmdb.kt:146-147);
  `metaFor`/`build` run TMDb lookups serially per item
  (MediaLibrary.kt:187-202,306-322); `scan()` calls `build()` twice re-parsing
  everything (MediaLibrary.kt:173-174).
- Fix: memoize genre map (trivial, safe) first; then bounded-parallel item
  lookups; avoid redundant re-parse across the two builds. Rung: cache → small
  concurrency.
- Verify: scan timing before/after; cache hit assertions.

### OPT-5 · EqualizerProcessor per-sample ByteBuffer on the audio thread · MED · MED
- Per-sample `inputBuffer.short`/`.float` virtual calls, ~96k/s
  (EqualizerProcessor.kt:60-75); `flushDenormals` walks all state each buffer.
- Fix: bulk `asShortBuffer()`/array processing. Rung: rewrite one loop.
- Verify: existing `EqualizerProcessorTest.kt` must still pass (extend it).

### OPT-6 · Player screens recompose whole tree · MED · MED
- `version++` on every `Player.onEvents` + 250 ms polls + non-snapshot player
  reads in composition (MusicPlayerScreen.kt:110-147, PlayerControls.kt:146-158);
  unkeyed `QueueSheet` reads player per item (MusicPlayerScreen.kt:285-286).
- Fix: scope state, key the queue. Rung: Compose idioms.

### OPT-7 · CastServer churn · impact MED · risk LOW-MED
- New `RoutingDataSource` per request, full VTT in memory per GET, unbounded
  thread pool (CastServer.kt:79-162).
- Fix: cache converted VTT, bound the pool. Rung: cache + fixed pool.

## Tier 3 — APK size / build

### OPT-8 · Enable R8 minify + resource shrinking · impact HIGH (size) · risk HIGH · status deferred
- `buildTypes.release` sets only signing; no `proguard-rules.pro`
  (app/build.gradle.kts:69-75). Biggest size win, BUT TDLib JNI, reflection in
  sshj/smbj/BouncyCastle, Media3 extension renderers and `org.json` all need keep
  rules; a wrong rule crashes only at runtime on a real build.
- **Deferred**: do in a dedicated change with a full release build + on-device
  smoke test (user tests on device — see memory). Not bundled with runtime opts.

### OPT-9 · Trim `material-icons-extended` · impact MED (size) · risk LOW-MED
- Thousands of vector icons shipped (gradle/libs.versions.toml:18). Without R8
  they all ship.
- Fix: enumerate used icons, import from `material-icons-core`, drop extended —
  OR let OPT-8 strip them. Rung: dependency swap after an icon audit.

### OPT-10 · `gradle.properties` build-speed flags · impact LOW (build time) · risk LOW
- Add `org.gradle.parallel`, `caching`, `configuration-cache`, `kotlin` incr.
  Build-time only, no APK/behaviour change.

## Tier 4 — minor cleanups
`WebImage` recomputes `File(cacheDir,"images")` in body (WebImage.kt:39); unkeyed
`Shelf` LazyRow (LibraryScreens.kt:250); SQL `LIMIT` string-interpolated
(Store.kt:93,147); 1 Hz `PlayerActivity` poll (PlayerActivity.kt:178-185).

## Explicitly NOT targets (YAGNI)
- **ABI splits** — already lean (`arm64-v8a`+`armeabi-v7a`, both needed).
- **DropIt /24 sweep cadence** — by design to beat multicast-blocking routers
  (see memory); changing it risks discovery. Leave unless profiled.
- **Rewriting working subsystems** (TDLib bridge, DataSource routing) — correct
  and minimal already; no change without a measured reason.
