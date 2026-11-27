---
tags: [entity, data, integrations, tdlib]
sources: [app/src/main/java/app/tgplayer/data, app/src/main/java/app/tgplayer/local, app/src/main/java/app/tgplayer/Telegram.kt, app/src/main/java/org/drinkless/tdlib/Client.java]
updated: 2026-11-27
---

# Data & integrations (`app.tgplayer.data` + `.local` + TDLib)

## Persistence (three mechanisms by data type)

- **SQLite** — `Store` (plain `SQLiteOpenHelper`, not Room; Store.kt:24). DB
  `library.db` v3; tables `saved`, `progress`, `history`, `playlist(_song)`. All
  access `suspend` on `Dispatchers.IO`; every write bumps
  `version: StateFlow<Int>` (Store.kt:28,247) so Compose re-queries.
- **SharedPreferences** — `Settings` (one `"settings"` file): scalar delegates
  + hand-serialized blobs (`recentUrls`/`libraryFolders` newline-joined;
  `networkDrivesJson`/`jellyfinJson` raw JSON). `TelegramLock` uses a separate
  file; stores only a PBKDF2-HMAC-SHA256 hash (150k iters) + lockout backoff.
- **Flat files + JSON** — `MediaLibrary` under `filesDir/library/`: `files.txt`
  (TAB `uri\tms\tname`), `matches.json`, `telegram.json` cursors, `tmdb/` cache.
  Catalog held as `MutableStateFlow<Catalog?>`; `Catalog` derives
  genres/metaByUri/recentlyAdded `by lazy` (Library.kt:56-72). `Library` is a
  thin read-model over a swappable `LibrarySource` (Telegram in prod, sample in
  debug).

## Integrations

- **TDLib** (`Telegram.kt`, `Client.java`) — one global client; one shared daemon
  thread for all clients; `onUpdate` on that thread only `tryEmit`s into a
  `SharedFlow` (buffer 64, DROP_OLDEST) — never blocks native. `send()` bridges
  callback→coroutine via `suspendCancellableCoroutine`; `TdlibDataSource` uses a
  blocking `runBlocking { send }` on its Media3 thread. DB unencrypted in
  `noBackupFilesDir/tdlib` (`tails:` noted).
- **Jellyfin / TMDb** — raw `HttpURLConnection` + `org.json`. TMDb has a 14-day
  SHA-1 disk cache (`cacheDir`), serves cache offline. TMDb funcs are **plain
  blocking** (callers must dispatch).
- **Network drives** — smbj (pooled `DiskShare`), commons-net FTP (per-op),
  sshj SFTP (pooled, host-key TOFU). All on `Dispatchers.IO`.
- **ServerDiscovery** — `NsdManager` Bonjour; pre-14 single-flight resolver
  serialized via a `pending` deque.
- **DropIt** — `ServerSocket` HTTP server on a cached thread pool + UDP /24
  unicast beacon on port 48662 every 2 s (multicast dropped by routers — see
  [[lan-mdns]] memory). MediaStore writes to `Download/Drop It`.

## Threading invariants

No `GlobalScope`; no `runBlocking` on Main; disk/net off Main; PBKDF2 on Default.

## Hotspots (see [[synthesis/optimization-targets]])

`NetworkDrives.all()` and `Jellyfin.server` re-parse their JSON on **every** file
op / poster (NetworkDrives.kt:82-89, Jellyfin.kt:56-59); TMDb lookups are
sequential blocking per item with per-title genre-file re-reads
(MediaLibrary.kt:187-202,306-322, Tmdb.kt:146-147); `MediaLibrary.build()` runs
twice per scan re-parsing everything (MediaLibrary.kt:173-174); the DropIt /24
sweep is a constant 2 s poll of ~254 hosts.
