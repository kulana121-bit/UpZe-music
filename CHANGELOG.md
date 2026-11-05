# Changelog

All notable changes to UpZe are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and UpZe follows [Semantic Versioning](https://semver.org/).
Android `versionCode` always increases, whatever the version name.

## [Unreleased]

## 1.0.1 — 2026-11-05

`versionCode` 22. Same package and signing key; installs over 1.0.0.

### Fixed
- **First-run setup skipped the Customize and Artists steps.** `MainActivity` decided between Welcome/app and the setup
  flow from `Graph.settings.state.value` once the app was ready. A plain `StateFlow.value` read is not observed by
  Compose, and the collected settings were only read while not ready, so after start-up that screen never recomposed on
  a settings change: finishing Welcome went straight to the app (setup only appeared on a later cold start, where
  Finish/Skip then could not leave it), and Settings › Run setup again did nothing. It now collects one combined
  `(ready, settings)` state. The Welcome flow's last step leads into **Customize** (look, glass, player, dock, home,
  **Artists**, motion & sound).
- Settings: **Customize** and **Your artists** (Appearance › Setup, and in the Settings list and its search) now open
  the screens in-app, above the dock; Back, Close and Done return to Settings.
- Setup flow audit: Back on the first step from Settings returned nothing (now closes); the keyboard from the artist
  search stayed up after leaving the step; landscape left the steps ~200 dp under the preview (preview now sits beside
  the steps on landscape/wide screens); artist suggestions were re-downloaded every time the step was revisited or the
  picker reopened (cached for the session); a failed artist search left its error and Retry on the suggestions view;
  artist names were laid out 112 dp wide in 96 dp cells (overlapping neighbours on narrow grids); Welcome hid Back on
  its last step.

### Changed
- **Player: Queue and Lyrics open full screen.** Swipe up on the player (or tap Up next / Lyrics) opens a full-screen
  pane with Queue and Lyrics tabs; swipe left/right to switch (the tab indicator follows the finger), swipe down on the
  header or past the top of the list, or press Back, to close. Same pane in every player design, portrait and
  landscape (this also replaces the landscape Queue/Lyrics panes that could show nothing). Horizontal swipes on the
  artwork still skip.
- **Classic player layout:** the four-tab bar and the Sleep/Sound chips are gone. Two distinct entry cards at the bottom
  (Up next with the next song, Lyrics); Related, Credits, Sleep timer, Sound & EQ and Song options are in the top-right
  ⋮ menu; a small chip shows a running sleep timer; more room around the transport controls.
- **Container transforms:** pages (list items, cards, settings rows), the Customize/Artists screens, sheets, dialogs, the
  full-screen Queue/Lyrics pane and the Classic player's Related/Credits panes grow out of the element that was tapped
  and shrink back into it on close. Lite devices get a short fade instead.
- **Long-press on any song** (Home in all four designs, Search, playlists, albums, artists, library lists, the queue,
  song cards) opens the same song-options sheet as its ⋮ button, with a haptic.
- **Tab lift on every dock:** tapping a tab lifts its icon out of the bar as a round glass bubble on a gooey metaball
  neck that stretches and snaps, then drops back into its slot (spring, haptic). On the Classic dock (expanded, mini
  squircle, folded circle), its Search orb, Island, Orb and Split docks, and the tablet side rail (the bubble leaves the
  rail sideways). The active tab gets a soft accent glow (the bar tints toward it) and, where the tabs have no labels, a
  small dot. Real backdrop blur on the bubble on Android 12+; frosted fill with a light rim on Android 8–11; lite devices
  get a short lift without the neck.
- **3D depth on lists:** a wider flat focus band: the centre ~6-7 rows of playlists, albums and the queue stay full size
  and flat; only rows beyond that tilt and fade. The synced-lyrics drum is unchanged.

### Performance
- New "lite effects" tier (`Graph.liteFx`: low-RAM devices, ≤ 96 MB app heap or < ~1.6 GB RAM) turns off list 3D depth,
  the full-screen artwork blur, sheet/dialog transforms (short fades instead) and caps artwork decodes at 480–544 px;
  Coil's memory cache is 15% of the heap there (20% elsewhere). Blur stays off below Android 12 as before.
- `profileinstaller` plus a baseline profile for start-up, Home, lists and the player (R8 stays on for release).
- Smoothness pass on everything new: the pane drag, pager tab indicator, tab-lift bubble/neck and container transforms
  read their animated values only in layout/draw (offset/graphicsLayer/draw lambdas), the neck Path and brushes are
  cached (drawWithCache, rewind per frame, no allocation), gesture deltas no longer launch a coroutine per event, and the
  queue, playlist, search and artist lists have stable keys plus contentType.

## 1.0.0 — 2026-11-05

First release under the UpZe name (formerly MusicSpot). Version names restart at 1.0.0; `versionCode` is 21 (MusicSpot
2.0.17 was 20), and the package name `com.ravi.musicspot` and signing key are unchanged, so it installs over MusicSpot and
keeps your library, settings and sign-ins.

### Added
- **In-app updater.** On app start (at most once every 12 hours, in the background) and from Settings › About ›
  Check for updates, UpZe asks GitHub for the latest release of kulana121-bit/UpZe-music (unauthenticated, ETag-cached).
  A newer version opens an "Update available" sheet with the release notes and Update / Later / Skip this version.
  Update downloads the APK with a progress bar, checks its size, asks for "Install unknown apps" permission if needed,
  and opens the system installer. Settings › About › "Check for updates automatically" (on by default). Failures are
  silent apart from a line in the Playback log. Adds the `REQUEST_INSTALL_PACKAGES` permission and a FileProvider
  limited to the update download folder.
- Settings › About › **Developer** section: Ravi, **GitHub** (github.com/kulana121-bit/UpZe-music) and **Instagram**
  (@jus7_rv) rows with icons. Links open with `ACTION_VIEW` (Instagram opens in its app when installed) and never crash
  when no app can open them.
- Unit tests for the updater's version comparison, GitHub release JSON parsing and release-notes cleanup.

### Fixed
- Tapping a link in Settings (licence, credits, Jamendo "Get a client_id") could crash the app on devices without a
  browser: Compose's `LocalUriHandler` throws when no activity handles the link. Links now go through a guarded
  `ACTION_VIEW` helper.
- Home-screen widget: its "open app" `PendingIntent` used request code 0 for the same activity as the media session's,
  so with `FLAG_UPDATE_CURRENT` the widget could overwrite the notification's "open the player" extra. It now has its
  own request code.
- Lint errors: unused `BoxWithConstraints` scope in the Island dock (now a plain `Box`, one less subcomposition); the
  intentional `StateFlow.value` read in `MainActivity` is documented and suppressed.
- Rename leftovers: README "UpZe (formerly UpZe)", `THIRD_PARTY_NOTICES.md` and the Gradle project name still said
  MusicSpot; a misplaced manifest comment.

### Changed
- Gradle wrapper regenerated for Gradle 9.5.0, now including `gradlew.bat`.
- README rewritten; repository docs added (CONTRIBUTING, CODE_OF_CONDUCT, SECURITY, issue/PR templates, CI workflow).

### Releasing
Create a GitHub Release tagged `vX.Y.Z` (matching `versionName`), attach the signed `UpZe-vX.Y.Z.apk`, sign it with the
same key as every earlier release, and bump `versionCode` each time. Installed copies pick it up through the updater.

---

## Earlier history (MusicSpot)

MusicSpot 2.0.7 – 2.0.17 (versionCode 9 – 20) were built outside this changelog; their changes are marked `// v2.0.x` in
the source. Older entries follow unchanged.

### MusicSpot 2.0.6 — 2026-11-04
- Fix "Unhandled input format ... encoding=4" (ERROR_CODE_AUDIO_TRACK_INIT_FAILED) on Android 10: the sound engine now keeps the input PCM encoding (16-bit in, dithered 16-bit out) instead of always emitting float into a 16-bit sink.
- Motion: every option surface now opens and closes with one shared Material-motion spec (`MotionSpec` in ui/motion/Motion.kt; `MotionSheet`, `MotionDialog`/`MotionAlertDialog`, `MotionDropdownMenu` in ui/motion/Surfaces.kt). Song menu (⋮/long-press/player More), credits & stream-info sheet and sleep timer slide up on a spring over a fading scrim and can be dragged down; add-to-playlist, new/rename/delete playlist, clear history, sign-out and storage dialogs scale from 0.9 with a fade; the playlist ⋮ menu scales and fades out of its button; search suggestions fade in/out; player panes rise on open/sink on close and fade-through between Up next/Lyrics/Related/Credits; page push/pop (shared axis X), tab switches (fade-through) and the cover-art shared element use the same specs. Closing always reverses. Instant with Reduce motion; no blur below API 31.
- New bottom dock: a floating rounded glass dock (icons + labels, Search included) with a liquid blob behind the active tab, tinted from the artwork. While scrolling down it morphs into a compact pill (icons only; with a song playing, the active icon over a dot row) and the mini player glides down beside it — one critically damped spring drives both, staged so they never overlap (DockGeometryTest). Tap the single-icon pill or scroll up / reach the top to expand. "Hide navigation bar on scroll" is now "Navigation bar on scroll": Mini (default) / Hide / Always visible (an earlier "off" maps to Always visible). Page bottom padding stays at the expanded height so lists never jump. Tablet side rail unchanged.

### MusicSpot 2.0.5 — unreleased (code only; version not bumped, no release built)
Fixes from Ravi's real playback log (Lenovo TB-8505X, Android 10, Indian mobile network, "Koyal" by Duryodhan Rana).

#### Fixed
- **NewPipe crash on Android 8–12** (`NoSuchMethodError: Collectors.toUnmodifiableList`): core library desugaring on
  (`desugar_jdk_libs_nio:2.1.5`). Verified in the debug APK dex: no `java.util.stream.Collectors.toUnmodifiableList`
  references remain; NewPipe calls `j$.util.stream.DesugarCollectors.toUnmodifiableList`.
- **ExoPlayer 403 after a good probe**: googlevideo streams are now fetched in ≤1 MB ranged chunks, always with a `Range`
  header, on the same OkHttp client and the same headers as the probe (the probe also sends `Accept-Encoding: identity`
  like ExoPlayer). ExoPlayer's real googlevideo request (status, Range, User-Agent, header names) is written to the
  playback log.
- **YouTube never recovering**: a mid-stream 403 stands down only the client that minted that URL; stand-downs expire
  after 2 minutes (was 10); when every client is stood down they are cleared once and the walk runs once more.
- **Endless replay loop**: at most 2 automatic replays per song (the 2nd skips YouTube if YouTube refused the stream),
  then the error stays on screen with Share log / Retry. No auto-skip.
- **JioSaavn "search timed out"**: IPv4-first DNS (a dead IPv6 route used to eat the whole connect timeout), connect
  timeout 6 s (JioSaavn 5 s, read 8 s), search budget 20 s (was 9 s), both queries in parallel, autocomplete.get raced
  in after 4 s, HTTP status + latency logged. Sandbox latency: 0.33–0.58 s per query.
- **"no audio formats"** now logs why (playability status, adaptive/muxed counts, mime types, url/cipher counts,
  HLS-only or SABR responses).
- **SoundCloud** "Could not get client id" also fails on a desktop JVM, so it is NewPipe/SoundCloud, not Android:
  SoundCloud stays off by default, is left out of the automatic fallback, and its settings row says so.

#### Faster
- YouTube and the fallback search race: the JioSaavn search starts with the YouTube walk; after 6 s a ready fallback
  stream wins. The last working client leads the next walk. The next 1–2 queue items are pre-resolved once the current
  song plays. Playback starts after 1.2 s of buffer. One OkHttp connection pool (HTTP/2) for API, streams and artwork;
  DNS/TLS warm-up to YouTube Music, JioSaavn, googlevideo and the artwork CDNs at app start. Coil uses the shared
  client, a memory cache and a 200 MB disk cache; artwork is requested at the size it is drawn. Pages you return to
  render from an in-memory result cache. Time to first audio is logged.

#### New
- **Home › On repeat** (Your favourites): most played, repeated and liked songs (plays + repeats×2 + liked bonus,
  21-day half-life), 2-row grid with Play all and Shuffle; hidden until there are 2+. New play-count store: a play counts
  after 30 s (half of a short song); a consecutive replay counts as a repeat.
- **Infinite play** switch in Up next (default on): with ≤2 songs left, ~10 songs are appended from YouTube Music radio
  (JioSaavn recommendations if YouTube fails), deduped against the queue and recent history, re-ranked towards the
  artists and songs you play and like, shown under an "Autoplay" divider.
- **Swipe on song rows** (album, playlist, artist, search lists; not carousels, queue, player, sliders or dock): right =
  Play next, left = Add to queue, snaps back, haptic tick at 35%, "Playing next" / "Added to queue" pill.
- **Scroll position restore** on every page and dock tab, including Home carousels (saved state per back-stack entry).
- **Player motion** (all off with Reduce motion, no blur): artwork scale + shadow on play/pause, buffering pulse on the
  artwork, animated artwork-colour gradient that glides between songs, title/artist slide in the skip direction, marquee
  for long titles and artists, seek thumb grows while dragging, play button morphs with a spring, prev/next nudge.

### MusicSpot 2.0.4 — unreleased (code only; version not bumped)
- Playback log: Settings › About › Playback log (last 300 lines, also saved to playback-log.txt) with Copy / Share / Clear. Records each play attempt: track and source, every YouTube client (player HTTP status, probe HTTP status, exception), availability wait, NewPipe decipher result, JioSaavn fallback search result and stream HTTP status, final ExoPlayer error code. Cookies, tokens and URL query strings are removed.
- Player error now names the failing steps (e.g. "YouTube: all clients refused (403) · JioSaavn: no match") and has a "Share log" button next to Retry.
- JioSaavn fallback: gets its own time budget after YouTube fails (YouTube 22 s, then search 9 s + stream 12 s; overall limit 45 → 60 s); searches with a cleaned title ("Koyal | Duryodhan Rana | …" → "Koyal Duryodhan Rana", then "Koyal"); lenient matching (YouTube title junk, "- Topic" channels, artist named in the title, longer video versions); Hindi/Odia vowel signs no longer stripped when comparing titles.
- Home: Search/Settings buttons removed from the top (the dock has them); bigger greeting; "Continue listening" hero card on a gradient from the artwork; "Recently played" grid; soft shadows on artwork; larger section titles.
- Motion (all off with Reduce motion): staggered entrance on first load (Home, Search results, Library, album/artist/playlist pages), press-scale feedback on cards, tiles, rows and player buttons, dock icon hop on tab select, loading/error crossfades, cover scale respects Reduce motion.

### MusicSpot 2.0.3 — 2026-11-04
- Fix YouTube "Response code: 403": client cascade, URL probing, availability wait, silent re-resolve, automatic JioSaavn fallback.
- Bottom nav hides on scroll down, returns on scroll up (Settings › Appearance toggle).

### MusicSpot 2.0.2 (versionCode 4) — 2026-11-03

#### Fixed
- **Crash when playing songs** ("Player is accessed on the wrong thread", debug build): `PlaybackService.maybeAutoplay`
  read `player.mediaItemCount` / `getMediaItemAt` inside a `Dispatchers.IO` coroutine. The radio is now fetched on IO and
  every player read/write happens on the main thread. Regression test `PlaybackThreadingTest` (Robolectric, API 29).
- **StackOverflowError on the first tap of Play before the player had connected**: `PlayerConnection.run` called itself
  (`?: run { … }` resolved to the member, not the stdlib `run`). Controller calls from other threads are now posted to the
  main thread; the connect listener runs on the main executor.
- **Streams rejected/throttled because of a duplicate User-Agent**: `OkHttpDataSource.setUserAgent()` *adds* its UA after
  the per-request headers, so googlevideo got the minting client's UA and a browser UA. The default UA is now a default
  request property that the resolver's headers override. Resolved URLs are cached for 30 min (seeks no longer re-resolve).
- **Black text on the dark background**: Material's `LocalContentColor` defaults to black and nothing in the main shell
  provided one. The theme root now provides `onBackground` inside a `Surface`; every colour role is set for dark, light
  and pure-black and checked to WCAG AA (≥ 4.5:1) including album-art seeds; sheets/transparent surfaces set their content
  colour. Test `ThemeContrastTest` (API 29, no dynamic colour).
- **Release (R8) build did not open on Android 10**: see PROGRESS.md (2.0.2) for what was found and verified. The release
  is now built without R8 code optimisation and in R8 compat mode, with every app class kept and the crash handler /
  crash screen / API-gated glass holders kept intact.

#### Added
- **New Home**: greeting + avatar, mood chips, Quick picks paged grid, Keep listening, your mixes & playlists, YT Music home
  shelves (endless), Trending in India (YT charts IN), JioSaavn trending / new releases / top playlists, Hindi, Punjabi and
  Haryanvi hits, artists you like, shortcuts (Liked, Downloads, History, On device), "See all" pages, pull to refresh,
  skeleton shimmer, cached feed for instant/offline open, responsive tablet layout. JioSaavn albums open in-app.
- **Motion**: shared-element cover art (cards → album/playlist/artist header), shared-axis push/pop and fade-through tab
  transitions, predictive back (page follows the gesture), staggered list entry, press scale on cards, play/pause shape
  morph, lyrics active-line spring, animated chip selection, shimmer placeholders. All respect Reduce motion.
- **Theme setting**: System / Dark / Light / Pure black (Settings › Appearance).

### MusicSpot 2.0 (versionCode 2) — 2026-11-03

A ground-up rewrite. v1.0 was an offline prototype built from the Google Stitch "Obsidian Redux" designs with a bundled
demo catalogue; v2.0 is a real streaming and local music player.

#### Added
- **Real catalogues instead of the demo catalogue**: YouTube Music (Innertube client with an OpenTune-derived parser and a
  player-client walk, NewPipe Extractor fallback), JioSaavn (320 kbps), Audius, SoundCloud, Jamendo, Internet Archive,
  device music (MediaStore), Subsonic/Navidrome and Jellyfin servers.
- **Source router** with user priority, quality-first mode for Max/Lossless, cross-source matching (ISRC, title/artist/
  duration, remix/live guards) and automatic failover.
- **Accounts**: optional Google sign-in (YouTube Music library sync) and Spotify library import/sync via locked-down
  WebViews; secrets encrypted with an Android Keystore AES-GCM key, excluded from backup. Guest mode.
- **Welcome flow** (sign-in optional, sources, quality).
- **Lyrics chain**: LRCLIB, LyricsPlus (word sync), KuGou, NetEase, YouTube Music; enhanced-LRC word timing, instrumental
  gaps, romanisation, per-song and global offset, cache.
- **Software audio engine** replacing `android.media.audiofx`: graphic + parametric EQ (RBJ biquads), AutoEq profiles,
  auto headroom, bass boost, widener, limiter, bit-exact A/B bypass, polyphase upscaler, bandwidth extension, transient
  restore, TPDF dither, CPU guard. Pure hi-res and bit-perfect USB output.
- **Liquid-glass UI** (Kyant Backdrop / Haze / fallback), side rail on wide screens, cover morph between mini player and
  Now Playing, non-scrolling Now Playing with swipe-up panes.
- **Library**: playlists (create/rename/delete), liked, history, downloads, device music, synced YouTube Music and Spotify
  playlists.
- **Settings**: searchable; accounts, sources (reorder, servers), quality per network, playback (gapless/crossfade, sleep
  default), sound, lyrics, appearance, storage (real sizes), live Diagnostics, About & licenses.
- **Real downloads** (WorkManager with notifications) and an offline mode.
- JVM unit tests for routing, matching, lyrics, queue, sleep timer, DSP and the account store; optional live smoke tests.

#### Changed
- Now Playing no longer scrolls; lyrics/queue/related moved into a swipe-up panel.
- Release builds are minified with R8 and resource-shrunk, signed with the permanent MusicSpot key (no debug-key fallback).
- License: the app is now GPL-3.0-or-later because it incorporates OpenTune and NewPipe Extractor code.

#### Removed
- Demo catalogue, demo audio/artwork, simulated downloads, simulated diagnostics and failure switches, fake collaborative
  playlists and simulated voice search.
- `android.media.audiofx` Equalizer/BassBoost/Virtualizer (replaced by the in-app engine).

### MusicSpot 1.0 (versionCode 1)

- Offline Compose prototype of the 32 Stitch screens with 10 bundled demo tracks, ExoPlayer + MediaSession playback,
  queue, sleep timer, likes/playlists in DataStore, timed demo lyrics, device-EQ mapping and simulated streaming features.
