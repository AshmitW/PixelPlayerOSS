# PixelPlayerOSS: Navidrome Edition (Unofficial Fork)

This is an **unofficial fork** of [PixelPlayerOSS](https://github.com/PixelPlayerHQ/PixelPlayerOSS)
by [@lostf1sh](https://github.com/lostf1sh), maintained by [@AshmitW](https://github.com/AshmitW).
It is not affiliated with, endorsed by, or supported by the original authors.

**Why this exists:** I love PixelPlayerOSS and use it daily, but I wanted *complete*
[Navidrome](https://www.navidrome.org/) support now, and official development of
those features may take time. This fork adds full Navidrome support on top of the
original app. Until the official app covers the same ground, this is the version
I use and maintain.

Found a bug, or want a feature? **Please open an issue.** Feedback is welcome.

## What this fork adds (on top of upstream)

Complete two-way Navidrome integration:

- **Favorites sync (two-way):** hearting a song in the app stars it on the server
  instantly, with an offline-safe queue that retries. Server-side stars sync down,
  and the Liked tab refreshes itself when opened or pulled.
- **Server lyrics:** synced lyrics served by your Navidrome server, preferred over
  third-party sources, persisted locally so they work offline.
- **Playlist sync (two-way):** renames, adds, removes, and reorders push to the
  server and survive offline periods. Server-side edits sync down. New playlists
  are created on the server by default, with a local-playlist option, and local
  edits are never silently overwritten.
- **Offline downloads:** Spotify-style download buttons on playlists, albums,
  Liked songs, individual songs, or everything. Downloads live in an app-private
  cache (no duplicate files in your library), include lyrics and artwork, survive
  restarts, respect a Wi-Fi-only toggle, and support quality tiers via server
  transcoding. Streamed songs are auto-cached, and a Downloads & storage screen
  manages it all. When offline, unavailable songs gray out.

Everything else (local playback, Jellyfin, the Material 3 UI) behaves as in the
original app.

## Download

Grab the latest APK from [Releases](https://github.com/AshmitW/PixelPlayerOSS/releases).
Every build is signed and installable over the previous one.

Works great with [Obtainium](https://github.com/ImranR98/Obtainium) for automatic
updates: add this repo's URL as an app source (enable pre-releases while builds
are marked as such).

> Note: these builds are signed with this fork's own key and use the same package
> id as the official F-Droid app, so you can't install one over the other. Pick one.

## Build from source

Requires JDK 21, Android SDK 37 (min SDK 30 / Android 11+).

```sh
git clone https://github.com/AshmitW/PixelPlayerOSS.git
cd PixelPlayerOSS
./gradlew :app:assembleDebug -Ppixelplayer.enableAbiSplits=false
```

The `upstream-main` branch tracks the original project unmodified;
[compare](https://github.com/AshmitW/PixelPlayerOSS/compare/upstream-main...main)
shows everything this fork changes.

## License

This project is a **modified version** of PixelPlayerOSS and is distributed under
the [GNU General Public License v3.0](LICENSE), the same license as the original.
Original work Copyright (c) Theo Vilardo and PixelPlayerOSS contributors.
See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). Full source for all
modifications is this repository.

All credit for the app itself goes to the original authors. This fork only
builds on their work.
