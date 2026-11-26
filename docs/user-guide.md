# TG Player user guide

TG Player is a video and music player for Android phones and Android TV. It plays what is on your phone, what is in your Telegram chats and channels, what is on your home network (PCs, NAS boxes, FTP and SFTP servers, Jellyfin), and links from the web. All of it is in one app with one player.

This guide covers what each screen does and how to use it.

- [1. Getting started](#1-getting-started)
- [2. Video tab](#2-video-tab)
- [3. Music tab](#3-music-tab)
- [4. Browse tab](#4-browse-tab)
- [5. Library tab: movies and shows](#5-library-tab-movies-and-shows)
- [6. Telegram](#6-telegram)
- [7. Jellyfin](#7-jellyfin)
- [8. The player](#8-the-player)
- [9. More tab: settings](#9-more-tab-settings)
- [10. Android TV](#10-android-tv)
- [11. Supported files and formats](#11-supported-files-and-formats)
- [12. Your data and privacy](#12-your-data-and-privacy)
- [13. Troubleshooting](#13-troubleshooting)
- [14. Known limits](#14-known-limits)

---

## 1. Getting started

### What you need

- Android 8.0 or newer, on a phone or Android TV with an ARM processor (most phones and TV boxes).
- An internet connection for Telegram, Jellyfin, web links and movie posters. Files on your phone play offline.

### First launch

<img src="images/launch-intro.jpg" width="300" alt="Launch intro: the amber play mark, TG PLAYER and the tagline">

Each time the app starts, the TG Player mark springs in on an amber glow. The name **TG PLAYER** and the tagline *A Video Player by Android Community* follow, and then the app opens. Tap anywhere to skip it. The intro only plays when the app starts fresh, not when you rotate the phone or come back to it.

### Allowing access to your files

The **Video**, **Music** and **Browse → Storage** sections need permission to read your files. Until you allow it, they show a **Your files** card with an **Allow file access** button.

- **Android 13 and newer:** Android asks to allow access to **Music and audio** and **Photos and videos**. Allow both.
- **Android 12 and older:** the usual storage permission prompt appears.
- If you denied it twice, Android stops asking; the button then opens TG Player's page in Settings → **Permissions**.

TG Player reads only videos and music. It never asks to manage all files, so on Android 11 and newer the folder browser shows folders, videos and music only.

The app checks again whenever you return to it. Telegram, web links, network drives and Jellyfin work without file access.

### Finding your way around

A floating bar at the bottom has the main tabs: **Video**, **Music**, **Browse**, **Library** and **More**. An amber pill slides under the tab you're on. Pressing Back on any tab except Video takes you to Video.

**Telegram** opens full screen from **Browse**, with its own tab bar (see [section 6](#6-telegram)).

---

## 2. Video tab

The home screen: every video on your phone, plus whatever you're in the middle of watching.

### Continue watching

When you have something half-watched, a large **Continue watching** panel fills the top of the tab:

- **What counts as in progress:** anything you've watched for more than 5 seconds but less than 95% of, up to 15 items. This includes files on your phone, web links, files on network drives, Jellyfin items and Telegram videos in your Library.
- **What it shows:** the title, how much you've **Watched** and how much is **Left**, how many **Videos** and **Folders** are on the phone, and a **Watch progress** bar.
- **Resume** carries on from where you stopped.
- **Artwork** comes from the best source available:
  1. Jellyfin's own artwork for Jellyfin items.
  2. The movie or show's artwork from TMDb (The Movie Database) for anything in your Library.
  3. A TMDb lookup by name for other movie files. The app only uses it when the title really matches, so a camera clip never gets a stranger's poster.
  4. Otherwise, a picture taken from the video itself.
- **Glow:** on Android 12 and newer, a soft blurred glow of the artwork shines behind the panel.

Below it, the **In progress** row works like a cover flow:

- The middle card is large and the cards beside it shrink and fade.
- **Swipe**, or **tap a side card**, to bring a card to the middle. The panel above switches to it.
- **Tap the middle card** to play it.
- With three or more items the row loops round in both directions.
- Each card shows the time left and a progress bar.
- Each step makes a soft click-wheel tick and a light vibration. The tick follows your system volume and silent mode.

### All videos

Under the panel is a grid of **every video on your phone**, newest first. Each tile shows:

- **a poster**, like the Library's: the TMDb poster if the video is in your Library, otherwise a picture from the video;
- a **blue bar** at the bottom if you've played part of it;
- the **title**, **file size** and **length**.

A white frame marks the video you opened last.

- **Search videos** narrows the grid by title. The heading shows *All videos · N* or *Matches · N*.
- **Tap a tile** to play it. The other videos in the same folder are queued after it, in title order.

> **New videos not showing?** The tab lists what Android's media index knows about. Videos you download or record appear on their own. Files copied over USB from a computer may take a moment, or a restart of the phone, before Android adds them.

---

## 3. Music tab

Every song on your phone, A to Z, with a count at the top (*N songs on this phone*).

- **Search:** the **Song or artist** box filters the list.
- **Shuffle:** the round amber button plays the songs currently shown in random order.
- **Rows** show the album art (or a coloured tile), the title, and the artist and length. Tap a song to play the list from there.
- **Background play:** music keeps playing after you leave the player, with controls in the notification and on the lock screen.

---

## 4. Browse tab

Everywhere TG Player can play from.

### Sources

- **Telegram:** videos and music from your chats and channels ([section 6](#6-telegram)).
- **Network stream:** play a link: MP4, MKV, HLS (`.m3u8`) or DASH (`.mpd`).
- **Jellyfin:** your Jellyfin media server ([section 7](#7-jellyfin)).

### Storage: folders on your phone

**Internal storage** is listed first, then **SD card** and any **External drive** (USB drives) when connected.

- **Folders:** open one to see its sub-folders first, then the files TG Player can play, with thumbnails and sizes. Other files (PDFs, APKs and so on) are hidden.
- **Playing:** tap a file to play it. The rest of the folder is queued after it.
- **Going back:** Back goes up one folder.
- **Hidden folders:** folders and files whose names start with a dot stay hidden unless you turn on **Show hidden files** in More.
- **Add to Library:** the button at the top of any folder adds it to your Library. TG Player then sorts what's inside into movies and shows ([section 5](#5-library-tab-movies-and-shows)). Tap it again to take the folder out.

### Network stream

Tap **Network stream**, paste or type a link, then tap **Play** (or Go on the keyboard).

- **Links:** only `http://` and `https://` links work.
- **Recent links:** the 8 most recent appear under **Recent**. Tap one to play it again.
- **Download ahead:** for ordinary video files (MP4, MKV and similar), TG Player saves the file to storage as fast as your connection allows. It keeps going while you're paused and carries on after the app restarts. Seeking past what's been saved moves the download to that point. You can turn this off with **Download ahead to disk** in More.
- **Live streams** (HLS and DASH) use a large memory buffer instead.
- **Flaky connections** are retried a few times before giving up.

### Network drives

Play straight from shared folders on a PC, a NAS, a router or another phone, with no copying.

**Adding a drive:** tap **Add network drive**.

1. **Choose the type** with the chips:
   - **SMB:** Windows file sharing, macOS, Linux (Samba) and most NAS boxes.
   - **FTP:** an FTP server.
   - **FTPS:** FTP with encryption. Self-signed certificates, common on NAS boxes, are accepted.
   - **SFTP:** files over SSH, on Linux, macOS or a NAS with SSH turned on.
2. **On your network:** servers that announce themselves (most NAS boxes, Macs and Linux machines) appear as chips under this heading. Tap one to fill in the type and address. Windows PCs don't announce themselves, so type their address.
3. **Fill in the form:**
   - **Address:** an IP address such as `192.168.1.20`, or a name such as `NAS-NAME`. Add `:port` for a non-standard port. You can also paste `\\host\share`, `smb://host/share` or `ftp://host/folder`.
   - **Shared folder:** required for SMB (for example `Movies`). For FTP, FTPS and SFTP it's an optional **Folder**; leave it empty to start where the server puts you.
   - **User name:** leave it empty to log in as a guest (SMB) or anonymously (FTP). SFTP always needs a user name. For a Windows domain account, type `DOMAIN\user`.
   - **Password.**
4. **Tap Connect.** TG Player connects before saving, so a wrong address or password shows up right away with a clear message, such as *Wrong user name or password*, *Access denied* or *This server doesn't allow guests; enter a user name*.

**Using a drive:**
- **Open it:** your drives are listed under **Network drives** as *type · user* (or *Guest*). Tap one to open it.
- **Browse and play:** it works like a phone folder. Video files get thumbnails, and tapping a file queues the rest of the folder. If a folder can't be opened, **Try again** retries.
- **Add to Library:** network folders can be added to the Library too.
- **Remove a drive:** long-press it, then choose **Remove**. TG Player forgets the drive and its password and takes its folders out of the Library. Nothing on the drive is deleted.

**Security:**
- **Passwords** stay in the app's private storage on your phone. They never appear in links or history.
- **SFTP server keys:** TG Player remembers the server's key the first time you connect. If the key ever changes, it refuses to connect and tells you. Remove the drive and add it again only if you trust the change.

---

## 5. Library tab: movies and shows

A Jellyfin-style library of the folders you choose, sorted into **movies** and **TV shows**, with posters, backdrops, ratings, genres, descriptions and episode names.

### Adding folders

Add a folder with the **Add to Library** button at the top of:

- a folder on your phone (**Browse → Internal storage**);
- a folder on a network drive;
- a Telegram chat or channel (from its page). The Library takes that chat's videos and video files that are 10 minutes or longer, so trailers and promos are skipped.

Until you add something, the tab shows *Your library is empty* with these steps.

### How files are recognised

TG Player follows the same naming rules as Jellyfin:

- **Movies:** `Inception (2010).mkv`, or `Inception (2010)/inception-1080p.mkv`. The year can be in the file name or the folder name.
- **Episodes:** `Show S01E02.mkv`, `Show 1x02.mkv`, `Show Season 1 Episode 2.mkv`, or numbered files inside `Show/Season 1/` (and `Specials` for season 0).
- **Skipped:** trailers, samples, featurettes and `Extras`-style folders.

### Scanning

- **Automatic:** the Library rescans on its own when the last scan is more than an hour old, both when the app opens and when you open the tab. For Telegram chats it only asks for posts newer than the last scan; once a week it reads them in full, which also drops deleted posts.
- **Scan again:** the refresh button at the top scans now, reading Telegram chats in full. A spinner and *Scanning…* show while it works.
- **Unreachable folders:** if a folder can't be reached (a drive away from home, or Telegram signed out), the Library says so and keeps showing what it found there last time.

### Posters and details

Details come from **TMDb** (The Movie Database), in your phone's language. They're kept on the phone for two weeks, so the Library opens instantly and works offline.

### The Library home

- **Header:** *N movies · N shows* and the **Scan again** button.
- **Search movies and shows:** also finds episodes by name.
- **Shelves:**
  - **Continue watching:** movies and episodes you're partway through.
  - **Next up:** for each show, the next unwatched episode after the one you finished last, like Jellyfin.
  - **Recently added:** the 20 newest titles.
  - **Movies** and **Shows**.
  - **A shelf per genre**, biggest first.
- **Badges:** movie posters show a **✓** when watched, otherwise a progress bar. Show posters show how many episodes are left, or **✓** when all are watched.
- **Folders:** listed at the bottom; the **✕** removes one from the Library.

### Movie page

Tap a movie to open its page:

- **Header:** the backdrop, with the title, genres and figures: **Released**, **Rating**, and **Status** (*Seen*, a percentage, or *New*).
- **Play** (or **Resume**) and a **Watch progress** bar.
- **Mark watched / Mark unwatched.**
- **Fix match** (see below).
- **Details:** the description and where the file is stored.

### Show page

- **Header:** the backdrop, with **Released**, **Rating**, **Seasons** and **Episodes**.
- **Progress:** a series progress bar, and a **Play S01E01** or **Continue S02E05** button for the first episode you haven't watched.
- **Seasons:** season chips (*Season 1*, *Season 2*, *Specials*…).
- **Episodes:** a list with each episode's still image, name, progress and a tick when watched.
  - **Tap an episode** to play it, with the rest of the season queued after it.
  - **Long-press an episode** to mark it watched or unwatched.

### Fix match

If TMDb picked the wrong title, tap **Fix match** on the page:

1. Edit the title or year if needed and search.
2. Tap the right result from TMDb's list.

TG Player remembers your choice for that title.

### Watched and unwatched

A video counts as **watched** once you've seen 95% of it. **Mark unwatched** forgets its position, which also takes it out of *Continue watching*.

---

## 6. Telegram

TG Player turns your Telegram chats and channels into a streaming library: every video, video file and song, searchable, with resume, favourites and offline downloads. It uses Telegram's official library, TDLib. TG Player is an **unofficial** app and isn't made or endorsed by Telegram.

Open it from **Browse → Telegram**.

### Logging in

1. **Country:** type to filter the list by name or calling code. It starts on the country you're in.
2. **Phone number:** enter it and tap **Send code**.
3. **Code:** enter the code and tap **Log in**. The screen says where Telegram sent it: a Telegram message, an SMS, a phone call, or fragment.com. Use **Use a different number** to start again.
4. **Two-step password:** if your account has one, enter it. Your hint is shown if you set one.

Errors from Telegram appear under the form. Signing up for a new Telegram account isn't supported; create the account in the official Telegram app first. Back leaves the login.

### Password lock (optional)

You can lock the Telegram section of TG Player with its own password:

- **Setting it:** open the **account** icon (top right of Telegram Home or Library), then **Set a password**. It needs at least 4 characters.
- **When it's asked:** every time you open Telegram in TG Player.
- **Wrong tries:** after 5 wrong tries you have to wait 30 seconds. The wait doubles after each further miss, up to 15 minutes, and holds even if you restart the app.
- **Forgot password?** logs you out of Telegram in TG Player, which also removes the lock. The password can't be recovered.
- **Changing it:** **Change password** and **Remove password** are on the same account sheet.

### Account sheet

The account icon shows your name, @username and phone number, the password-lock options, and **Log out of Telegram**. Logging out keeps your favourites and watch-later lists on the phone.

### Telegram tabs

Telegram has its own bar: **Home**, **Chats**, **Search** and **Library**, with a **Leave Telegram** arrow on the left. Back takes you from a chat to its tab, from any tab to Home, and from Home out of Telegram.

#### Home

- **Search videos in all chats:** results appear as you type.
- **Continue watching:** Telegram videos you're partway through.
- **Watch later** and **Favourites:** your lists.
- **Shelves per chat:** then one shelf per chat or channel, built from the newest videos across all of them. **See all** opens that chat. More channels load as you scroll down.

#### Chats

Up to 300 chats.

- **Filter chats:** a box to search by name.
- **Chips:** **All**, **Channels**, **Groups** and **People**.
- **Opening a chat:** tap it to open its page. The page has:
  - **Videos**, **Files** and **Music** tabs. *Files* means playable video or audio sent as documents, such as MKV movies.
  - **Search in [chat name].**
  - Cards showing each post's date and size.
  - **Add to Library:** puts the chat's long videos into the Library tab.

#### Search

Searches **every chat** by title, caption or file name, with **Videos / Files / Music** chips.

#### Library (Telegram)

- **Chips:** **Continue**, **Favourites**, **Watch later** and **Downloads**. Downloads shows live percentages for offline downloads in progress.
- **Storage** (the icon at the top) shows:
  - how much the streaming cache and offline downloads take up;
  - **Streaming cache limit:** 2, 5, 10 or 20 GB;
  - **Clear streaming cache:** this never deletes offline downloads.

### Cards and the item sheet

Each card shows the thumbnail, length (or size), a resume bar, and a music icon for songs. Offline items say *Downloading NN%* or *Downloaded*. Titles come from the first line of the post's caption, or else a tidied-up file name.

- **Tap** a card to play it.
- **Long-press** a card for the item sheet:
  - **Play**, or **Resume from m:ss**;
  - **Add to favourites**;
  - **Watch later**;
  - **Download for offline:** the file downloads in the background and survives restarts. Tap again to remove it.

### How Telegram playback works

- **Streams right away:** videos play while they download. Playback starts after the first few seconds of data, and seeking jumps the download to the new spot.
- **Progress display:** the seek bar shows how much is already on the phone, and the buffering label says *Buffering… NN% downloaded*.
- **Streaming cache:** played files stay in a cache (5 GB unless you change it). The cache is trimmed oldest-first when you open Telegram or close a video. The video you're watching and your offline downloads are never removed.
- **Resume:** Telegram videos always resume where you left off.

### Sponsored posts

Telegram requires apps using its API to show its sponsored messages. In TG Player:

- **Channel pages:** may show one **Sponsored** (or *Recommended*) card. Tapping it opens the sponsor's link. Links that leave Telegram ask first: *Open this link?*
- **Channel videos:** while one plays, Telegram may show a small **Ad** box at the bottom left. You can close it (✕) after a short time. It isn't shown in picture-in-picture or for audio.
- **⋮ menu:** both have **Sponsor info** and **Report ad**. Hiding ads entirely needs Telegram Premium.

---

## 7. Jellyfin

Watch your own Jellyfin media server, with your progress kept in step with every other Jellyfin app.

### Signing in

**Browse → Jellyfin** → enter the server **Address** (for example `192.168.1.20:8096`; `http://` is added for you), your **User name** and **Password**, then **Sign in**.

- **What's stored:** TG Player keeps only a sign-in token, never your password.
- **One server at a time.**

### Using it

- **Home:** the server's name and *Signed in as…*, then:
  - **Continue watching** and **Next up** shelves from the server;
  - your **Libraries**;
  - a **Sign out** button.
- **Libraries:** open as poster grids, with **✓** for played items and progress bars. Seasons open as episode lists with stills, numbers, descriptions and ticks.
- **Playing:** tapping an item plays it, with the rest of the folder queued after it. Items from *Continue watching* and *Next up* play on their own.
- **Original files:** Jellyfin items play as the original file, with no conversion on the server, so the phone needs to be able to play the format.

### Progress sync

- **Resume:** you start where you left off in any Jellyfin app.
- **Reporting back:** while you watch, TG Player tells the server your position about every 15 seconds, and again when you stop. The server marks an item played once you pass 90%.

### Skip intro and recap

If your server has intro detection set up (for example the Intro Skipper plugin), a **Skip intro** or **Skip recap** button appears at the bottom right during that part of the episode.

Jellyfin items also appear in the Video tab's **Continue watching** panel and **In progress** row, with their Jellyfin artwork.

---

## 8. The player

One player for everything: phone files, links, network drives, Telegram and Jellyfin.

### General

- **Full screen:** the player fills the screen and keeps it on while playing. Rotating or switching to picture-in-picture doesn't interrupt playback.
- **Songs:** audio files show their album art (or a placeholder), the title and the artist, and the controls stay on screen.
- **Opening from other apps:** you can choose TG Player when you open a video, audio file or HLS/DASH stream from another app or file manager.
- **Errors explain themselves:** for example *Can't reach the server…*, *The file isn't there any more*, *This file format isn't supported, or the file is damaged* or *This phone can't decode this file's video or audio*.

### Gestures

| Gesture | What it does |
|---|---|
| Tap | Show or hide the controls. They hide on their own after about 3.5 seconds of playing. |
| Double-tap left side | Back 10 seconds |
| Double-tap right side | Forward 10 seconds |
| Double-tap the middle | Play or pause |
| Swipe left or right | Seek; a full-width swipe is 90 seconds. The target time shows while you swipe, and it jumps when you let go. |
| Swipe up or down, left half | Brightness, for the player only |
| Swipe up or down, right half | Volume |
| Pinch out or in | Zoom to fill the screen, or fit the whole picture |

### Controls

- **Top:** **Back**, the title and where it's from (folder, server, chat or Jellyfin), **Cast**, **Audio and subtitles** and **More options**.
- **Middle:** **Previous** (when there's one), **Back 10 seconds**, **Play/Pause** (with a ring while loading), **Forward 10 seconds** and **Next** (when there's one).
- **Bottom:**
  - **Seek bar:** played in amber and buffered in white. For Telegram videos and links it also shows what's already saved to the phone.
  - **Time:** position and length.
  - **Lock:** turns off all touches. Tap the screen to show the padlock, then tap it to unlock.
  - **Aspect ratio:** cycles **Fit → Zoom → Stretch**.
  - **Rotation:** cycles **Auto rotate** (follows the video's shape) **→ Landscape → Portrait → Follow phone**.
  - **Picture-in-picture.**
  - **Speed** (for example *1×*): opens More options.

### Audio and subtitles

- **Audio:** each track with its language, channels (Mono, Stereo, 5.1, 7.1) and format. Tracks the phone can't play are marked *(can't decode)*.
- **Subtitles:**
  - **Off**, or any track in the video.
  - **Load subtitle file…** opens a subtitle file from your phone: SRT, VTT, ASS, SSA, TTML, DFXP or XML.
  - **Timing:** **−0.25 s / +0.25 s** and **Reset timing** shift subtitles that are out of sync. This works for subtitle files, not for subtitles built into the video.
  - **Size:** **Small**, **Normal**, **Large** or **Huge**.
- **Remembered language:** the audio and subtitle language you choose is used for the next videos too.

**Subtitles are found automatically:**
- **Beside the video:** a subtitle file next to a video on your phone or network drive loads by itself. It needs the same name as the video, optionally with a language tag: `movie.mkv` → `movie.srt`, `movie.en.srt`. On Android 11 and newer, Android hides subtitle files on the phone from apps that don't manage all files, so for videos on the phone use **Load subtitle file…** instead. Network drives aren't affected.
- **Online:** when a video has no subtitles at all, TG Player can find one on **OpenSubtitles.com**, in your phone's language and then English. A message says *Subtitles found online*. Turn this off with **Find subtitles online** in More.

### More options

- **Playback speed:** 0.5× to 3×.
- **Sleep timer:** **15**, **30** or **45 min**, **1 h**, or **End of file**. It pauses playback when the time is up.
- **A-B repeat:** **Set A**, then **Set B**, to loop a part of the video. **Clear** stops the loop.
- **Equalizer** (below).
- **Volume boost:** **+3**, **+6** or **+10 dB** for quiet files.
- **Background play:** keeps the sound going after you leave the player.
- **Media info:** the video's format, resolution, frame rate and bitrate; the audio format, channels, sample rate and language; and the subtitle track.

### Equalizer

A 10-band graphic equalizer for all sound in the player:

- **Switch:** on and off. Touching any control turns it on.
- **Curve:** a live picture of what the settings do.
- **Presets:** Flat, Bass boost, Treble boost, Voice, Loudness, Rock, Pop, Jazz, Classical and Dance. *Custom* appears when you set bands yourself.
- **10 bands** from 31 Hz to 16 kHz, each ±12 dB. Drag or tap to set them.
- **Preamp:** from −12 to +6 dB, and **Reset to flat**.
- **Saved:** your settings are kept. Loud peaks are softened instead of distorting.

### Picture-in-picture

- **Turning it on:** tap the picture-in-picture button. With **Picture-in-picture on leave** on (the default), pressing Home while a video plays also shrinks it into a small window.
- **When it's not available:** for songs, while casting, or on devices without picture-in-picture.

### Casting to a TV

- **Starting:** the **Cast** button sends playback to a Chromecast or Google TV on the same network. The queue and position move to the TV, and your audio and subtitle choices carry over.
- **Phone, network-drive and Telegram files:** these stream to the TV from the phone over Wi-Fi.
- **Web links and Jellyfin:** the TV plays these straight from the source.
- **Subtitles:** SRT, VTT and TTML subtitles go to the TV. ASS/SSA subtitles don't.
- **Leaving the player:** casting continues if you leave the player screen.
- **Hotspot:** a phone sharing its own hotspot can't cast its local files.

### Resume, queues and background play

- **Resume:** videos start where you left off, if you watched more than 5 seconds and less than 95%. Your position is saved every 15 seconds and when you leave.
- **Queues:** the player plays whatever list you started from: a folder, a season, your songs, a shuffle or a Jellyfin folder. **Play next file automatically** moves on when a file ends.
- **Background play:**
  - **Music** keeps playing after you leave the player.
  - **Videos** pause when you leave, unless **Background play** is on.
- **Notification and lock screen:** playback shows the usual media controls. Tapping the notification opens TG Player.
- **Interruptions:** playback pauses when you unplug headphones, and makes way for calls and other apps' sound.

---

## 9. More tab: settings

### Playback

| Setting | Default | What it does |
|---|---|---|
| Resume where you left off | On | Files and links start from your last position. |
| Play next file automatically | On | When a file ends, play the next one in the folder. Applies the next time the player starts. |
| Background play for videos | Off | Keep the sound going after leaving the player. |
| Picture-in-picture on leave | On | Pressing Home while a video plays shrinks it to a small window. |
| Find subtitles online | On | Look up subtitles on OpenSubtitles.com for videos that have none. |
| Prefer FFmpeg audio decoder | Off | Use TG Player's built-in audio decoders before the phone's own. Turn on if some files have no sound. Applies the next time the player starts. |

### Network streams

| Setting | Default | What it does |
|---|---|---|
| Download ahead to disk | On | Links keep buffering to storage while paused, instead of a small memory buffer. |

### Storage

- **Streaming cache limit:** **2**, **5** (default), **10** or **20 GB**, shared by Telegram and web links. Older streamed files are removed first; offline downloads never are.
- **Clear network cache:** shows how much space downloaded links use, and frees it.
- **Clear watch history:** forgets the saved positions for local files and links.

### Files

- **Show hidden files:** include folders and files whose names start with a dot.

### About

The app version, the playback components it's built on (TDLib, Media3, FFmpeg), the notice that TG Player is unofficial and not made or endorsed by Telegram, and the TMDb credit (*This product uses the TMDB API but is not endorsed or certified by TMDB*).

---

## 10. Android TV

<img src="images/tv-banner.png" width="320" alt="Android TV banner: the amber play mark and TG PLAYER">

- **Installing:** TG Player installs on Android TV and Google TV and appears in the TV's app row with its banner. No touchscreen is needed.
- **Remote, controls hidden:** **←/→** skip 10 seconds, **OK** plays or pauses, and **↑/↓** show the controls.
- **Remote, controls showing:** the arrows move between buttons.
- **Equalizer:** **↑/↓** change the selected band.
- **Layout:** the other screens use the phone layout and work with the remote's arrows. There isn't a separate TV-style layout yet.

---

## 11. Supported files and formats

**Files TG Player lists and plays**

- **Video:** MKV, MP4, M4V, AVI, MOV, WebM, TS, 3GP, FLV, WMV
- **Audio:** MP3, M4A, AAC, FLAC, OGG, Opus, WAV, WMA, ALAC
- **Streams:** HLS (`.m3u8`) and DASH (`.mpd`) over `http://` or `https://`

**Video** plays with your phone's own decoders, so what plays depends on the phone. Most phones handle H.264, H.265/HEVC and VP9.

**AV1** plays on every phone: TG Player uses the phone's AV1 decoder when it has one, and its own built-in **dav1d** decoder when it doesn't (common on Android 8 and 9). The built-in decoder runs on the processor, so very high resolutions may stutter on slower phones.

**Audio** uses the phone's decoders and, when those can't play a track, TG Player's built-in FFmpeg decoders. This covers the cinema formats phones often can't play:
- Dolby Digital (AC3) and Dolby Digital Plus (E-AC3);
- DTS;
- Dolby TrueHD;
- plus AAC, MP3, FLAC, Opus, Vorbis, ALAC and PCM.

Being on the list doesn't guarantee a file plays: an unusual codec inside a familiar container may still need the phone's support. WMV and WMA files in particular may not play.

---

## 12. Your data and privacy

- **Telegram:** your login and Telegram data stay in the app's private storage on the phone, and are never included in phone backups.
- **Kept on the phone only:** watch history, favourites, watch-later lists and settings. TG Player has no account and no cloud sync. The one exception is Jellyfin, which reports your progress to **your own** server.
- **Saved logins:** network drive passwords and the Jellyfin sign-in token are kept in the app's private storage.
- **Third-party services:**
  - **TMDb:** receives movie titles to look up details.
  - **OpenSubtitles:** receives a video's name and fingerprint when it searches for subtitles.
  - **Telegram:** handles everything in the Telegram section.
- **Full policy:** [privacy policy](privacy-policy.md), also at More → About → **Privacy policy**.

---

## 13. Troubleshooting

| Problem | Try this |
|---|---|
| Video, Music or Browse is empty | Tap **Allow file access** on the *Your files* card and allow Music and audio, and Photos and videos. |
| A new video isn't in the Video tab | Android hasn't indexed it yet. Wait a moment or restart the phone; files you download or record show up by themselves. |
| A file has no sound | Turn on **Prefer FFmpeg audio decoder** in More, then reopen the player. |
| Subtitles are out of sync | **Audio and subtitles → −0.25 s / +0.25 s** (subtitle files only). |
| The Library shows wrong posters | Open the title and use **Fix match**. |
| A network drive won't connect | Check the address, shared-folder name and password; the error message says which is wrong. For SMB, try your PC's IP address instead of its name. For SFTP, a changed server key blocks the connection on purpose. |
| A link won't play | Make sure it starts with `http://` or `https://` and points to a video file or an HLS/DASH stream, not a web page. |
| Telegram takes too much space | Telegram **Library → Storage**: lower the limit or **Clear streaming cache**. Offline downloads are kept. |
| Can't cast phone files to the TV | Phone and TV must be on the same Wi-Fi. This doesn't work when the phone is sharing its own hotspot. |
| Jellyfin says it signed you out | Sign in again from **Browse → Jellyfin**. |

---

## 14. Known limits

- **Login and signup:** no QR-code login for Telegram, and no signing up for a new Telegram account.
- **Jellyfin:** one server at a time. Jellyfin offers skipping of intros and recaps only, not credits.
- **Sleep timer and A-B repeat:** these stop if you close the player screen.
- **Notification:** tapping it opens the app, not the exact video.
- **Android TV:** there's no dedicated TV layout yet.
- **Playlists:** no playlists of your own (yet). Queues come from folders, seasons and lists.
