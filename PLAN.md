# Telegram Player for Android — plan

> Status 21 Nov 2026: milestones 0–6 done; then reworked into a VLC/MX-style player with Telegram as one source (see [MORNING.md](MORNING.md)). Two changes from this
> plan: lists use plain SQLite instead of Room, and there's no QR login.

A standalone Android app that logs in to your Telegram account as a client, lists the playable
media in your chats, channels and groups, and streams it while it downloads. It adds your own
Favourites, Watch Later and Continue Watching lists, plus search across all chats.

## Why not Telethon

Telethon is a Python library and doesn't fit a standalone Android app. On Android the standard
choice is **TDLib**, Telegram's own client library, written in C++ with a Java (JNI) binding. It
handles login, chats, search and *partial* file downloads, which is what lets a video play while it
downloads. TMPlayer and most third-party clients use it.

## Reusing what you have

| Item in `TelegramClient&PriceTrackerBot/credentials.json` | Reused? |
|---|---|
| `api_id`, `api_hash` | **Yes.** They identify the developer app, not a login. Put them in `local.properties` (never committed), and they reach the code through `BuildConfig`. |
| `phone_number` | No. You type the phone (or scan a QR code) inside the app. |
| `userbot.session` | No. That is a Telethon session for the price bot. The app makes its own TDLib login, which shows up as a new device under Telegram → Devices. |

Note: anyone can pull the `api_hash` out of an APK. That's fine for personal use. For public
releases it's normal practice, but it means you shouldn't reuse that api_id for anything sensitive.

## Stack

- Kotlin, Jetpack Compose (Material 3), single `app` module, min SDK 26
- TDLib for Android (native `.so` files + `org.drinkless.tdlib` Java classes)
- Media3 ExoPlayer for playback, plus a custom `DataSource` that streams from TDLib
- Room (SQLite) for Favourites, Watch Later, playback positions and search history
- Coroutines/Flow; no DI framework (YAGNI for one module)

## How streaming works (the hard part)

```
ExoPlayer ──read(offset,len)──▶ TdlibDataSource
                                   │ downloadFile(fileId, priority=32, offset, limit=0, synchronous=false)
                                   │ wait for updateFile until the needed bytes are downloaded
                                   └ readFilePart(fileId, offset, count) ──▶ bytes back to ExoPlayer
```
When you seek, ExoPlayer opens a new read at a later offset. The DataSource restarts
`downloadFile` from that offset, so the download follows you. MP4 files whose index (moov atom) sits
at the end need one extra small read at the end of the file first. ExoPlayer requests it on its own.

## Screens

1. **Login:** phone → code → 2FA password (TDLib auth states), or QR login from another device
2. **Home:** Continue Watching row, Favourites row, Watch Later row
3. **Chats:** your chats/channels/groups (starred ones first) → tap → grid of that chat's media, with tabs for Video / Audio / Files
4. **Search:** across all chats (`searchMessages` with a video/audio filter) or inside one chat (`searchChatMessages`)
5. **Player:** ExoPlayer with seek bar showing the downloaded range, audio/subtitle tracks, speed, next item. Audio keeps playing in the background through a Media3 `MediaSessionService`.
6. **Item menu (long-press):** Favourite, Watch Later, Download for offline, Open in Telegram

A saved item is stored as `(chat_id, message_id)` plus cached title, duration and thumbnail. File IDs
change between sessions, so the app re-fetches the message when it plays.

## Milestones

| # | Deliverable | Done when |
|---|---|---|
| 0 | Toolchain: Android Studio 2026.2.2 in `D:\AndroidStudio` (bundled JBR 25; SDK via setup wizard), phone with USB debugging | An empty Compose app runs on the phone |
| 1 | TDLib wired in + login screen | You log in and see "Logged in as …" |
| 2 | Chat list + per-chat media grid with thumbnails | Your channels list their videos |
| 3 | Streaming player (TdlibDataSource) | A 1 GB video starts in a few seconds and seeks forward |
| 4 | Room lists: Favourites, Watch Later, resume position, Home rows | Lists survive an app restart |
| 5 | Global + in-chat search, audio tab, background audio | Search finds a video by name across chats |
| 6 | Polish: offline downloads, cache-size limit, Android TV layout (optional) | Cache stays under the limit you set |

## Risks / unknowns

- **Getting TDLib for Android** is the biggest setup cost. Building it from source on Windows is slow
  (needs WSL or Linux). Options: use prebuilt binaries from a maintained project, or build once in
  WSL. *[uncertain: I haven't yet checked which prebuilt TDLib Android builds are current; that's
  the first task in milestone 1.]*
- **This PC:** JDK 25 and `adb` are installed, but **no Android SDK / Android Studio**, so
  milestone 0 has to come first.
- **Formats:** ExoPlayer plays MP4/MKV/WebM with H.264/H.265/VP9, but HEVC/AC3/DTS depend on the
  phone's hardware decoders. Some files may need the FFmpeg extension (milestone 6).
- **Telegram flood limits:** list media in pages (50–100 messages per call), never a whole channel at once.

## Alternative: fork TMPlayer

[TMPlayer](https://github.com/dracu-lah/TMPlayer) (GPL-3.0, Kotlin + TDLib + ExoPlayer) already
covers milestones 1–3 and part of 5. A fork would only need Favourites/Watch Later, global search
and audio, so it gets you there much sooner. The catch: it's a multiplatform codebase you'd have to
learn, and if you share the APK you must also publish your source under GPL-3.0.
