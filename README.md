<div align="center">

<img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="96" alt="UpZe icon" />

# UpZe

**A free, open-source music player for Android that streams legal catalogues, plays your own files and servers, and runs every song through a real sound engine.**

[![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B%20(minSdk%2026)-3DDC84?logo=android&logoColor=white)](#download--install)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?logo=jetpackcompose&logoColor=white)](https://developer.android.com/compose)
[![License: GPL-3.0](https://img.shields.io/badge/License-GPL--3.0-blue.svg)](LICENSE)
[![Latest release](https://img.shields.io/badge/release-v1.0.0-FF5358)](https://github.com/kulana121-bit/UpZe-music/releases)

[Download](https://github.com/kulana121-bit/UpZe-music/releases) · [Features](#features) · [Build](#build-from-source) · [Contributing](CONTRIBUTING.md) · [Changelog](CHANGELOG.md)

</div>

---

UpZe (formerly MusicSpot) is written in Kotlin with Jetpack Compose and AndroidX Media3. It plays from YouTube Music,
JioSaavn and other free catalogues, from the music on your phone and from your own Subsonic/Navidrome or Jellyfin
server, imports your Spotify and YouTube Music libraries, and gives you an EQ, AutoEq headphone profiles and an honest
upscaler. No ads, no telemetry, no account required.

## Features

### Playback sources
| Source | What it gives |
|---|---|
| YouTube Music (Innertube, NewPipe Extractor fallback) | Full catalogue, Opus ~160 kbps; 256 kbps with a signed-in Premium account |
| JioSaavn | Catalogue, 320 kbps AAC where available; automatic fallback when YouTube refuses a stream |
| Audius · Jamendo · Internet Archive | Official APIs; FLAC where offered (Jamendo needs your free client_id) |
| SoundCloud | Via NewPipe Extractor (off by default; currently broken upstream) |
| On this device | FLAC/ALAC/WAV/hi-res files through MediaStore |
| Subsonic / Navidrome, Jellyfin | Your own server; original files in Lossless mode |
| Spotify | Library **metadata only** (import/sync); songs play from a matched source |

Sources can be switched on/off and re-ordered. The router plays from your priority order and fails over to the next
match when a source can't play a song (ISRC when present, otherwise title/artist/duration with remix/live/cover guards).
Queue with reorder, play next, shuffle, repeat, Infinite play, gapless or 0–12 s crossfade; sleep timer with fade;
downloads (WorkManager, Wi-Fi-only option) and an offline mode.

### Sound engine & EQ
- Software engine inside ExoPlayer: 10-band graphic EQ, parametric EQ, presets, A/B bypass, preamp with auto headroom,
  bass boost, stereo widener and a peak limiter.
- AutoEq headphone profiles, searched and fetched on demand.
- Enhance: windowed-sinc upscaler to 48/96/192 kHz (only rates your output supports), bandwidth extension and transient
  restore — labelled "Upscaled"/"Enhanced", never "Lossless".
- Pure hi-res output, and bit-perfect USB DAC mode on Android 14+.

### Lyrics
- Provider chain in your order: LRCLIB, LyricsPlus (word-by-word), KuGou, NetEase, YouTube Music.
- Synced and enhanced LRC, instrumental-break dots, romanisation, ±3 s offset, disk cache.

### UI, dock & motion
- Material 3 with dynamic colour from the artwork; Dark, Light, System and Pure black themes.
- Liquid-glass surfaces (lens refraction on Android 13+, blur on 12, tinted fallback on older devices).
- Several Home, Now Playing and dock designs to pick from at first launch or in Settings › Appearance.
- One shared Material-motion spec for sheets, dialogs, menus and page transitions; Reduce motion makes them instant.
- Tablet layout with a side rail.

### Home-screen widget
- Now-playing widget with artwork-tinted card, title/artist and previous / play-pause / next.

### Accounts & updates
- Optional Google (YouTube Music library) and Spotify sign-in through locked-down WebViews; cookies are encrypted with
  an Android Keystore key and excluded from backups. Everything works as a guest.
- Built-in updater: checks GitHub Releases (at most every 12 h, or from Settings › About › Check for updates), shows
  the release notes, downloads the APK and hands it to the system installer.

## Screenshots

> Captured on a Lenovo Tab M8 (Android 10) during pre-1.0 development, before the rename to UpZe.

| Now Playing | Sound engine | Appearance |
|---|---|---|
| <img src="docs/screenshots/now-playing.png" width="240" alt="Now Playing" /> | <img src="docs/screenshots/sound.png" width="240" alt="Sound settings" /> | <img src="docs/screenshots/appearance.png" width="240" alt="Appearance settings" /> |

## Download & install

1. Open the [latest release](https://github.com/kulana121-bit/UpZe-music/releases) and download `UpZe-vX.Y.Z.apk`.
2. Open the APK on your phone. If asked, allow your browser or file manager to **install unknown apps**.
3. Tap **Install**. Later versions install over it and keep your data, and the app tells you when one is out.

Requirements: Android 8.0 (API 26) or newer (one APK for arm64-v8a, armeabi-v7a, x86 and x86_64).

> Coming from MusicSpot? UpZe keeps the package name `com.ravi.musicspot` and the same signing key, so it installs
> over MusicSpot 2.0.x and keeps your library, settings and sign-ins.

## Build from source

Requirements: JDK 17+, Android SDK with platform 37 and build-tools 36.0.0, and network access to Google Maven, Maven
Central and JitPack (NewPipe Extractor).

```sh
git clone https://github.com/kulana121-bit/UpZe-music.git
cd UpZe-music
./gradlew :app:assembleDebug          # debug APK: app/build/outputs/apk/debug/ (package com.ravi.musicspot.debug)
./gradlew :app:testDebugUnitTest      # JVM + Robolectric unit tests
./gradlew :app:lintDebug              # Android lint (NewApi is an error: minSdk 26 must keep working)
```

Release builds are signed with the project's permanent key, which is **never** in this repository. Point the build at
a properties file with `storeFile`, `storePassword`, `keyAlias` and `keyPassword`:

```sh
MUSICSPOT_KEYSTORE_PROPERTIES=/path/to/keystore.properties ./gradlew :app:assembleRelease
# or: ./gradlew :app:assembleRelease -PkeystoreProperties=/path/to/keystore.properties
# or: ./gradlew :app:assembleRelease -PunsignedRelease=true   (unsigned APK)
```

The release build fails rather than fall back to the debug key.

### Releasing a new version

1. Bump `versionCode` (must always increase) and `versionName` in `app/build.gradle.kts`, and add a `CHANGELOG.md` entry.
2. Build `:app:assembleRelease` signed with **the same key** as every earlier release (Android refuses an update
   signed with a different key) and keep the R8 `mapping.txt`.
3. Create a GitHub Release with the tag `vX.Y.Z` (matching `versionName`), paste the changelog entry as the notes, and
   attach the signed APK (`UpZe-vX.Y.Z.apk`). Don't mark it as a pre-release.
4. Installed copies find it through the in-app updater (`/releases/latest`, first `.apk` asset).

## Project structure

```
app/src/main/java/com/ravi/musicspot/
├── App.kt, MainActivity.kt   Process-wide Graph (stores, sources, player), entry activity
├── account/                  Encrypted account store, Google/Spotify sessions, library sync
├── core/                     HTTP client, crash guard, encryption, JSON files, playback log, links
├── data/                     Settings, library, downloads, home feed, play stats, AutoEq
├── dsp/                      Sound engine: EQ, limiter, resampler, enhancers
├── logic/                    Pure logic: queue, sleep timer, search, taste, contrast
├── lyrics/                   Lyrics providers, LRC parsing, romaniser, cache
├── playback/                 MediaSessionService, ExoPlayer setup, chunked data source, engine processor
├── source/                   Music sources and the router (YouTube, JioSaavn, local, servers, …)
├── spotify/                  Spotify library import (metadata only)
├── ui/                       Compose UI: screens, player designs, docks, settings, motion, glass, theme
├── update/                   In-app updater (GitHub Releases)
├── widget/                   Home-screen now-playing widget
└── yt/                       YouTube Music Innertube client, parser, stream resolver, NewPipe bridge
app/src/test/                 Unit tests (routing, DSP, lyrics, logic, updater, Robolectric start-up)
```

The Kotlin package stays `com.ravi.musicspot` so existing installs upgrade in place.

## Tech stack

Kotlin 2.4 · Jetpack Compose & Material 3 · AndroidX Media3 (ExoPlayer, MediaSession) · Kotlin coroutines & Flow ·
kotlinx.serialization · OkHttp · Coil 3 · WorkManager · NewPipe Extractor · Kyant Backdrop & Haze · Gradle 9.5 with
AGP 9.3 · JUnit 4 & Robolectric.

## Roadmap

- [ ] Play Store–independent F-Droid build
- [ ] Android Auto browsing
- [ ] More widget sizes and a lock-screen lyrics view
- [ ] Translations
- [ ] Fix SoundCloud once NewPipe Extractor supports it again

Ideas and votes are welcome in [issues](https://github.com/kulana121-bit/UpZe-music/issues).

## Contributing

Bug reports, ideas and pull requests are welcome. Please read [CONTRIBUTING.md](CONTRIBUTING.md) and the
[Code of Conduct](CODE_OF_CONDUCT.md) first. Security problems: see [SECURITY.md](SECURITY.md).

## Credits

UpZe stands on the shoulders of these projects (full list in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)):

- [OpenTune](https://github.com/Arturo254/OpenTune) — Innertube parser, player clients, lyrics providers, player panes (GPL-3.0)
- [NewPipe Extractor](https://github.com/TeamNewPipe/NewPipeExtractor) — YouTube stream extraction fallback (GPL-3.0)
- [AndroidX Media3 / ExoPlayer](https://github.com/androidx/media) — playback and media session (Apache-2.0)
- [Kyant Backdrop](https://github.com/Kyant0/AndroidLiquidGlass) and [Haze](https://github.com/chrisbanes/haze) — liquid glass and blur (Apache-2.0)
- [AutoEq](https://github.com/jaakkopasanen/AutoEq) — headphone correction profiles (MIT)
- [LRCLIB](https://lrclib.net) — synced lyrics
- [Coil](https://github.com/coil-kt/coil), [OkHttp](https://github.com/square/okhttp), [kotlinx](https://github.com/Kotlin) — images, networking, serialization, coroutines

## License

UpZe is free software: you can redistribute it and/or modify it under the terms of the
[GNU General Public License v3.0](LICENSE) or (at your option) any later version. It includes code from GPL-3.0
projects (OpenTune, NewPipe Extractor), so derivatives must stay GPL-3.0 as well.

## Disclaimer

UpZe hosts no music. It plays streams from services you choose to enable, your own files and your own servers. It does
**not** stream audio from Spotify (Spotify is used for library metadata only), does not use rip proxies or torrents,
and does not bypass DRM. Respect the terms of service of every service you use and the copyright of the music you play.
UpZe is not affiliated with YouTube, Google, JioSaavn, Spotify or any other service it can connect to.

## Developer

Made by **Ravi**.

- GitHub: [kulana121-bit/UpZe-music](https://github.com/kulana121-bit/UpZe-music)
- Instagram: [@jus7_rv](https://www.instagram.com/jus7_rv/)
