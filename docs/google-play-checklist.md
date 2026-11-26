# Google Play submission checklist

Answers for the Play Console forms, matching the code on the **Development** branch (no Deals tab, no install counter). Publish to Play from Development only. Re-check these whenever a feature that sends data somewhere is added.

## Store listing

- **Privacy policy URL:** `https://github.com/savadks95/Android-Community-Video-Player/blob/main/docs/privacy-policy.md` (also linked in the app: More → About → Privacy policy).
- **App name and icon:** keep "TG Player" and the amber play icon. Never use Telegram's name as the app name, its logo, or its blue paper plane.
- **Description:** say "Unofficial app built on the Telegram API; not made or endorsed by Telegram."
- **Screenshots:** use your own videos, or clips you have the rights to. Don't show pirated movies or posters for films you don't own, and don't show adult or suggestive channels.

## App content → Data safety

Google counts data as **collected** when the app sends it off the device, and as **shared** when it goes to a third party. Data the user explicitly asks to send somewhere (signing in to Telegram, opening their own server) is exempt from "shared", but still has to be declared as collected.

| Data type (Play's names) | Collected? | Shared? | Required or optional | Purpose | Why |
|---|---|---|---|---|---|
| Personal info → **Phone number** | Yes | No | Optional (only to use Telegram) | Account management | Sent to Telegram to sign in. |
| Personal info → **Other info** | Yes | No | Optional | Account management | Two-step verification password, sent to Telegram once to sign in. |
| App activity → **In-app search history** | Yes | No | Optional | App functionality | Telegram searches go to Telegram. |
| App activity → **App interactions** | Yes | No | Optional | Advertising or marketing | Views and taps on Telegram's sponsored messages are reported to Telegram, as its API terms require. |
| Files and docs → **Files and docs** | Yes | No | Optional | App functionality | Titles read from file names go to TMDB for posters; with "Find subtitles online" on, a title and file hash go to OpenSubtitles. |

Everything else (location, contacts, photos, audio files, health, financial, device IDs, crash logs, diagnostics): **not collected.**

Other questions:

- **Is all of the user data collected by your app encrypted in transit?** Yes. Telegram (MTProto), TMDB and OpenSubtitles are all encrypted.
- **Do you provide a way for users to request that their data be deleted?** Yes. Log out of Telegram (deletes all Telegram data on the device), or clear the app's storage. The developer keeps no data. Give the privacy policy's section 4 as the deletion link.
- **Processed ephemerally?** No, for each type above.

**Your call:** Jellyfin and network-drive sign-ins go only to servers the user owns and enters themselves, so this sheet treats them as not collected. If you'd rather be conservative, add Personal info → **User IDs** (Jellyfin and drive user names) as collected, purpose App functionality. In that case answer **No** to "encrypted in transit", because home servers may use plain HTTP.

## App content → Sensitive permissions

- **READ_MEDIA_VIDEO** (Photo and video permissions declaration): core use is a video player that lists and plays every video on the device and browses its folders. A one-time Photo Picker can't do that, because the app needs the whole library and needs to see new videos as they appear.
- **READ_MEDIA_AUDIO:** a music player that lists all songs on the device. Google has no separate declaration for it.
- **Foreground service (mediaPlayback):** background music and video sound, started when the user plays something, with a media notification. Link a short video showing playback continuing after leaving the app.
- **All files access:** no longer requested.

## App content → other forms

- **Ads:** "Yes, my app contains ads". Telegram's sponsored messages appear in public channels, labelled "Sponsored".
- **Content rating questionnaire:** the app shows user-generated content from Telegram, so answer yes to user-generated content and user interaction. Telegram applies its own Android content restrictions (TDLib's default; the app never turns them off).
- **Target audience:** 18+, or 13+ at the youngest. Not for children.
- **Account deletion:** not required. Users can't create an account in the app; they sign in to an existing Telegram account.
