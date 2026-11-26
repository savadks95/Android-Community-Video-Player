# TG Player privacy policy

*Effective 26 November 2026*

TG Player is a video and music player for Android phones and Android TV. It plays files on your device, media from your Telegram chats and channels, your Jellyfin server, network drives (SMB, FTP, SFTP) and web links. TG Player is an unofficial app built on the Telegram API; it is not made or endorsed by Telegram.

This policy explains what TG Player keeps on your device and what it sends to other services. In short: **the developer runs no servers for TG Player and receives none of your data.** There are no analytics, advertising or crash-reporting tools in the app.

## 1. What stays on your device

These are stored only in TG Player's private storage on your device. They are excluded from Android backups, so they never leave the device that way.

- **Watch history and progress, favourites, Watch later, the Library list, and your settings.**
- **The Telegram session:** TDLib, Telegram's official library, keeps your login session, a cache of your chats and messages, and the media you stream or download for offline viewing.
- **The app lock password**, if you set one: only a salted PBKDF2 hash is kept, never the password itself.
- **Jellyfin:** the server address, your user name and the access token the server issued. Your Jellyfin password is sent once to your server to sign in and is never stored.
- **Network drives:** each drive's address, user name and password, used only to connect to that drive.

## 2. Files on your device

With your permission (Android's "Music and audio" and "Videos" permissions), TG Player reads the videos and music on your device so it can list and play them. These files are never uploaded anywhere. TG Player does not ask for access to all files.

## 3. What is sent to other services, and when

| Service | When | What is sent |
|---|---|---|
| **Telegram** | When you sign in and use the Telegram section | Your phone number, the login code and your two-step verification password (if you have one) go to Telegram to sign you in. After that, TG Player asks Telegram for your chats, messages and media, and tells Telegram when you view or tap a sponsored message, as Telegram requires. Telegram's [privacy policy](https://telegram.org/privacy) applies. |
| **TMDB** (The Movie Database) | When the Library matches titles, and when a video plays | The title and year read from a video's file name or caption, to fetch posters and details. Telegram content and your account details are not sent. TMDB's [privacy policy](https://www.themoviedb.org/privacy-policy) applies. |
| **OpenSubtitles** | Only if **Find subtitles online** is on (More → Playback) | The video's title, your preferred subtitle languages and, when available, the file's size and OpenSubtitles hash (a fingerprint of the file's first and last 64 KB, not its contents). The privacy policy of [OpenSubtitles](https://www.opensubtitles.com) applies. |
| **Your Jellyfin server** | When you use Jellyfin | Your sign-in, and the requests needed to browse and play, go only to the server address you entered. |
| **Your network drives** | When you browse them | Your drive's credentials and file requests go only to that drive. |
| **Web links** | When you play a link | The request goes only to the server in that link. |
| **Google Cast** | Only when you cast | The media you cast is sent to your Chromecast or Google TV on your network. Casting uses Google Play services; Google's [privacy policy](https://policies.google.com/privacy) applies. |

TG Player does not sell or share your data with anyone else, and it does not use your data for advertising. Telegram's own sponsored messages are shown in channels because Telegram's API terms require it; Telegram chooses them.

All connections to Telegram, TMDB and OpenSubtitles are encrypted. Connections to your own Jellyfin server, network drives and web links use whatever the address you entered supports (plain HTTP is allowed for home servers).

## 4. Deleting your data

- **Log out of Telegram:** Telegram section → account button → **Log out**. This ends the session and deletes TG Player's Telegram data from the device.
- **Remove offline downloads:** Telegram section → **Library** → **Downloads**, and remove them there.
- **Delete everything:** Android Settings → Apps → TG Player → Storage → **Clear storage**, or uninstall the app.
- **Your Telegram account** belongs to Telegram, not to TG Player. To delete it, use Telegram's own app or [my.telegram.org/deactivate](https://my.telegram.org/deactivate).

Because the developer holds none of your data, there is nothing on the developer's side to delete.

## 5. Children

TG Player is not directed at children under 13 and does not knowingly collect data from them.

## 6. Changes

If this policy changes, the new version will be published at this address with a new effective date.

## 7. Contact

Questions about this policy: open an issue at [github.com/savadks95/Android-Community-Video-Player/issues](https://github.com/savadks95/Android-Community-Video-Player/issues).
