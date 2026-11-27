# Wiki index

LLM-maintained knowledge base for TG Player. The AI owns these pages; the human
reads them. See `AGENTS.md` for the rules, `log.md` for history.

## Core

- [[overview]] — what TG Player is: one player, many sources (phone, Telegram,
  SMB/FTP/SFTP, Jellyfin, web, Library).
- [[architecture]] — three OS components, owned Media3 player, hand-rolled
  Compose UI over globals; playback data flow and threading invariants.
- [[conventions]] — package layout, style, `tails:` comments, git/release flow.
- [[dependencies]] — toolchain, libraries, native ABIs, build facts for optimization.

## Entities

- [[player-engine]] — `app.tgplayer.player`: Media3 core, routing, DataSources,
  read-ahead cache, equalizer, cast, extractors, subtitles.
- [[ui-compose]] — `app.tgplayer.ui` + player screens: manual nav, four image
  loaders, state over globals.
- [[data-integrations]] — persistence (SQLite/Prefs/files), TDLib, Jellyfin/TMDb
  HTTP, LAN drives, DropIt.

## Synthesis

- [[synthesis/optimization-targets]] — ranked, evidence-cited performance/size
  hotspots across all layers (drives the CodeOptimization work).

## Related (project memory)

LAN mDNS multicast is dropped by the router → DropIt uses UDP unicast.
