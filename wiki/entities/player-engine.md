---
tags: [entity, player, media3]
sources: [app/src/main/java/app/tgplayer/player]
updated: 2026-11-27
---

# Player engine (`app.tgplayer.player`)

Media3/ExoPlayer core. One player, owned by `PlaybackService`; `PlayerActivity`
is a remote via `MediaController`. See [[architecture]].

## Pieces

- **PlaybackService** — builds/owns `ExoPlayer` (+`CastPlayer`), `MediaSession`,
  the renderers factory (injects `EqualizerProcessor`, forces dav1d/ffmpeg),
  `PlayableFirstLoadControl`, and drives read-ahead on item transitions
  (PlaybackService.kt:37-139).
- **PlayerActivity / PlayerHost** — intents → `buildItem` (tgmsg:// / jellyfin://
  rewrite, sidecar subtitle scan, `MediaId`=URI history key,
  PlayerActivity.kt:229-342); pre-registers read-ahead + warms TDLib head.
- **RoutingDataSource** — per-`open()` scheme switch to the right delegate
  (RoutingDataSource.kt:25-40). The `Factory` decides nothing.
- **DataSources** — `TdlibDataSource` (GetFile + DownloadFile + poll
  prefix-size), `HttpStreamDataSource` (plays from the filling read-ahead file),
  `SmbDataSource` (1 MiB chunk cache), `FtpDataSource`, `SftpDataSource`
  (16-deep read-ahead stream).
- **StreamCache / HttpDownload** — disk read-ahead for progressive http: one
  daemon thread per URI, `Range` GETs, sparse `RandomAccessFile`, downloaded
  ranges persisted to a `.ranges` sidecar, oldest-first trim to `cacheLimitGb`.
- **PlayableFirstLoadControl** — `LoadControl` delegate that keeps loading past
  the byte target until enough *duration* is buffered, so video-only byte fills
  don't stall playback.
- **EqualizerProcessor / AudioFx / EqualizerSheet** — 10-band RBJ peaking EQ as a
  `BaseAudioProcessor` in the sink; `AudioFx` holds the volatile `Eq` snapshot
  (swapped whole) + `LoudnessEnhancer` boost. Unit test:
  `app/src/test/java/app/tgplayer/player/EqualizerProcessorTest.kt`.
- **CuelessMatroskaExtractor** — gives Cue-less MKVs a guessing seek map.
- **CastServer / CastItemConverter / CastTracks** — per-session LAN HTTP server
  streaming phone-only sources to Chromecast; SRT→WebVTT on the fly.
- **OnlineSubtitles** — OpenSubtitles.com lookup by movie-hash + name, disk-cached.

## Hotspots (see [[synthesis/optimization-targets]])

Read-ahead range store is O(n log n)+alloc per 128 KB write and O(n) per 40 ms
poll (StreamCache.kt:264-293, HttpStreamDataSource.kt:55-80); `.ranges` re-read
and rewritten per file × fragmentation; blocking poll loops on loader threads
(TdlibDataSource.kt:111-139, self-flagged); per-sample `ByteBuffer` calls on the
audio thread (EqualizerProcessor.kt:60-75); CastServer per-request DataSource +
in-memory VTT + unbounded thread pool.
