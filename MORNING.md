# Overnight build: where things stand (20 Nov 2026)

TG Player is installed on your phone. Open it and log in. Everything below the login works with
sample data, but **nothing has run against a real Telegram account yet**, because that needs your
phone number and login code. Your first login is the real test.

## Try it (5 minutes)

1. Open **TG Player** and enter your phone number in international format (`+91…`).
2. Enter the code Telegram sends (it arrives as a message in your Telegram app), then your
   two-step password if you have one. A new device "TG Player" appears under Telegram → Devices.
3. Check:
   - **Home**: "Fresh from your chats" should fill with the newest videos from all your chats.
   - **Chats**: pick a channel → Videos / Files (MKV, MP4… sent as files) / Music tabs, and search inside it.
   - Tap a video: it should **start within seconds while it downloads**. Seek forward and the download follows.
   - Long-press a video → Favourites / Watch later. Leave a video halfway: it shows under Continue watching.
   - **Search**: type a movie or song name; it searches every chat at once.

If something breaks, run this with the phone plugged in and send me the output:
```powershell
D:\AndroidStudio\Sdk\platform-tools\adb.exe logcat -d -b crash
```

## What was built

| Milestone | State |
|---|---|
| 0 Toolchain | Done: Android Studio + SDK on D:, Gradle 9.9.2, AGP 9.5.1, Kotlin 2.4.21 |
| 1 TDLib + login | Done: TDLib 1.8.70 built from Telegram's official source (commit `03220f6`, 64-bit ARM, OpenSSL 3.5.9, 16 KB aligned). Phone → code → 2FA login. Verified on the phone: TDLib loads, accepts your api_id/hash and creates its database without crashing. |
| 2 Chats + media grid | Done: chat list with filters, per-chat Videos/Files/Music, in-chat search, thumbnails |
| 3 Streaming player | Done, **untested on real files**: `TdlibDataSource` feeds ExoPlayer from TDLib's partial download; seeking re-aims the download |
| 4 Lists | Done: Favourites, Watch later, resume position (saved every 15 s and on exit) |
| 5 Search + audio | Done: search across all chats with Videos/Files/Music filters; music plays with cover art |
| 6 Polish | Built 21 Nov, **not yet tested by you**: offline downloads (long-press → Download; Library → Downloads tab with live %), streaming-cache limit + "Clear streaming cache" (Library → storage icon), music keeps playing in the background with notification controls. Android TV layout not started. |

UI: a dark "Cinema" theme (violet→cyan accent), floating pill tab bar, poster cards with duration and
resume bar, and a long-press action sheet. A debug-only demo screen shows the signed-in UI with sample
data, so the UI can be checked without logging in:
```powershell
D:\AndroidStudio\Sdk\platform-tools\adb.exe shell am start -n app.tgplayer/.DemoActivity
```

## Decisions I made without you

- **TDLib built from source, not downloaded.** Community prebuilt TDLib binaries exist, but that
  library holds your whole Telegram login, so I built it from Telegram's own code in WSL (≈2 h).
  The build lives in `D:\Savad\tdlib-build` (5.4 GB). Rebuild later with the same script to update TDLib.
- **SQLite directly instead of Room** for the lists (no extra build plugin needed). Two tables.
- **No QR login**: scanning needs a second phone; phone + code works.
- **Files tab**: movies are often posted as documents (MKV); those show under *Files*, filtered to
  video/audio types only.
- `allowBackup` is off so your Telegram login never goes into a cloud backup.
- `GRADLE_USER_HOME` and `ANDROID_HOME` point to `D:\AndroidStudio\…` (C: had only 13 GB free).

## Known gaps / things to watch on first real use

- **Home "Fresh" feed** uses Telegram's global search with an empty query. If Telegram rejects
  that, Home shows an error under the shelves; tell me and I'll switch it to per-chat loading.
- **Formats**: ExoPlayer plays H.264/H.265/VP9 in MP4/MKV/WebM; some audio codecs (AC3/DTS)
  depend on the phone. An FFmpeg extension would fix those (milestone 6).
- **Cache**: streamed files now live in app storage (not Android's clearable cache, which had wiped them) and are trimmed to your limit (default 5 GB), oldest-played first. Offline downloads are never trimmed.
- **Music** keeps playing after you leave the player; the notification reopens the app home, not the exact track.
- Thumbnails/lists are fetched when a screen opens; they don't live-update while you're on it.
- The app isn't in git yet. If you add it: `local.properties` (holds your api_id/hash) is already
  git-ignored. The 20 MB `libtdjni.so` may want Git LFS.

## Where things are

- Code: `app/src/main/java/app/tgplayer/` (`Telegram.kt` TDLib client, `data/` Telegram + SQLite,
  `ui/` screens and theme, `player/` streaming player)
- TDLib Java API + native lib: `app/src/main/java/org/drinkless/tdlib/`, `app/src/main/jniLibs/arm64-v8a/`
- Build from a terminal: `.\gradlew.bat assembleDebug` in this folder (or open the folder in Android Studio)

## 21 Nov: VLC/MX-style player

TG Player is now a general media player; Telegram is one source inside it.

- **Tabs:** Video (every video by folder, Continue watching), Music (all songs, search, shuffle),
  Browse (Telegram, Network stream, and a file manager over internal storage / SD cards), More (settings).
- **Telegram** opens from Browse and asks to log in only then; TDLib isn't started before that.
- **File access:** Android's "All files access" switch (asked on first use of Video/Music/Browse).
- **Player:** swipe to seek, left/right edge swipes for brightness/volume, double-tap ±10 s, pinch zoom,
  lock, aspect (Fit/Zoom/Stretch), rotation, PiP (auto on Home), audio/subtitle tracks, external
  subtitle files and auto-loaded `movie.srt` next to `movie.mkv`, subtitle size, speed 0.5–3×,
  sleep timer, A-B repeat, equalizer presets, volume boost, media info, background play,
  next/previous through the folder, "Open with TG Player" from other apps.
- **Network streams:** the playing link downloads to disk as fast as the connection allows and keeps
  going while paused; seeking past the downloaded part moves the download (`StreamCache.kt`,
  `HttpStreamDataSource.kt`). HLS/DASH use a large memory buffer (15 min, heap-capped).
  Verified on the phone with a public test MP4 on a slow connection.
- **FFmpeg:** audio decoders (AC3, E-AC3, DTS, TrueHD, AAC, MP3, FLAC, Opus, Vorbis, ALAC, PCM)
  built from FFmpeg 6.0.1 (GPG-verified) + Media3 1.11.1's extension; used when the phone can't decode.
  Loads on the phone; not yet tried on an actual AC3/DTS file.
- **AV1:** dav1d 1.5.5 + Media3 1.11.1's `decoder_av1` (`androidx/media3/decoder/av1/`, `libdav1dJNI.so`), built in
  WSL by `D:\Savad\av1-build\build.sh` with NDK 28.2. Used only when the phone has no AV1 decoder ("Prefer FFmpeg"
  stays audio-only, `PlaybackService.kt`). Verified on the phone with a 1080p AV1 clip, also with dav1d forced.
- Not done: subtitle/audio delay, playlists you build yourself, Android TV layout.
