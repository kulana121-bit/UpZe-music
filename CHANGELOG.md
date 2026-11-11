# Changelog

All notable changes to UpZe are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and UpZe follows [Semantic Versioning](https://semver.org/).
Android `versionCode` always increases, whatever the version name.

## Version numbering
Public releases are tagged `v1.0x` on GitHub and that is the version name the app shows. Older entries below use the
internal 1.0.x numbers. Mapping: 1.0.7 = **v1.02** (versionCode 32), 1.0.8 = **v1.03** (versionCode 33),
1.0.9 = **v1.04** (versionCode 34).

## [v1.05] - 2026-11-11
Pack 2A (tests/stability, part 1 of Pack 2):
- Fixed the long-failing `PlaybackThreadingTest.playViaPlayerConnection`: a new queue whose seed song matched the previous queue's seed never got its autoplay radio (the per-seed guard now resets on playlist change), and the test's stale queue assertion was updated to the 1.0.7 smart-queue behaviour (played songs are kept above the playing song). Test tone lengthened so Robolectric's virtual clock cannot play it out mid-test.
- Pack 2B: `./gradlew lintDebug` now runs and passes — 16 errors fixed (0 remain), no lint baseline added; 97 warnings remain as warnings.
- Pack 2C: InnertubeParser fixture tests (8) — parser unchanged, no bug found.
- Pack 2D: the five silent-failure spots now log through PlaybackLog (secrets redacted); no behaviour change.
- Pack 2E: UI smoke tests (10) — Home, Library, Search, Player, Cached Songs, empty + populated.
- Pack 3: playback speed + pitch 0.5x-2x with a pitch-preservation option (survives track changes); Save-queue-as-playlist from the queue screen; loudness normalisation + ReplayGain through ONE gain stage (ReplayGain wins when present; both Off by default; Settings > Sound > Volume levelling).
- Pack 4: service ticker and position poll gated when idle (battery); true engine pass-through when every effect is off — buffers pass byte-identical (tested).
- Pack 5 Part 1: personalised Home (time-aware greeting, Jump back in, Your top artists — rows hide until there is enough data) and a Wrapped Anytime stats screen (Week / Month / Year / All time: total plays, listening streak, top songs/artists, most repeated, favourite time of day), all from on-device stats.
- Pack 5 Part 2 (widgets): the Android 10 square-corner bug is fixed at the root — every widget card is rendered at the widget's actual size and shown fitXY (corner-pixel tests run on API 29); the Now Playing widget is tighter, and there are three new widgets: a compact 4x1 player, a This-week stats widget (its numbers come straight from Wrapped), and a Shortcuts widget (Search / Downloads / Shuffle / Resume — all wired to real actions).
- Pack 6 Part 1: Android Auto browsing is real now (a MediaLibraryService browse tree over your own library — Liked, Playlists, History, On this device — and every browsable song actually plays), and queues are plural: up to 10 named persistent queues, each with its own position, shuffle and repeat; the old single queue migrates untouched.
- Pack 6 Part 2: DJ Mode (Settings > Playback, default Off) — per-track BPM learned on-device, beat-aligned transitions with a real two-player overlap and tempo match within +/-6%, with a plain-crossfade fallback whenever a tempo is unknown or too far apart; harmonic reorder is NOT included and not claimed. Music recognition: "What's playing" in Search identifies a song playing around you through a keyless route and resolves the match into UpZe's own sources; the mic is on only while the sheet is open and nothing is recorded.
- Tests now 310 (0 failed, 10 skipped on the build machine). ALL packs complete — source only; the single bundled-release decision is the owner's.

## [v1.04] - 2026-11-11
_Internal 1.0.9 · versionCode 34. A fix pack: no new features, nothing removed._
### Fixed
- Tidal / Monochrome never claims unverified FLAC. A stream is labelled FLAC / 1411 kbps only when its first bytes
  are a confirmed `fLaC` header. If the probe fails (network or HTTP error, empty answer), the stream shows
  "Quality not verified" and never counts as lossless; a non-FLAC stream shows the format actually delivered
  (AAC, MP3, Ogg). Before, a failed probe was treated as FLAC.
- The in-app updater checks the downloaded APK's SHA-256 against the digest GitHub publishes for the release asset.
  On a mismatch (or a release with no digest) the file is deleted, the sheet says the update failed verification,
  and the installer is not opened. Before, only the byte size was checked.
### Added
- Library › Cached songs: a **Clear all** button. It asks first, showing how much space will be freed, then empties
  the cached-songs list and removes those songs from the media cache. Per-song "Remove from cache" and
  Settings › Storage are unchanged.
### Housekeeping
- This changelog brought up to date (v1.02, v1.03); README release badge now v1.04; two stale code comments
  corrected (account login pages are real; KuGou uses word-timed KRC first, then LRC).

## [v1.03]
_Internal 1.0.8 · versionCode 33._
### Faster
- Opening: settings, library, play stats, downloads, accounts and artist seeds load in parallel; the network warm-up
  runs off the main thread after the stores are ready; the update check waits 4 s after start.
- Animations are quicker and lighter (shorter durations, stiffer springs, ambient loops paced to ~30 FPS, ~20 FPS on
  lite devices); nothing was removed. Lighter blurs for liquid glass, frosted glass, Haze and the sheet focus blur.
- Home: fixed the stagger bug that faded rows in again on every scroll; Home no longer recomposes on download
  progress ticks; lighter spotlight and card shadows.
- Queue: softer 3D effect below the playing song.
### Lyrics
- Full-screen lyrics run on a frame-accurate clock; lines switch 150 ms early so the glide finishes as the voice
  starts; one font weight for every line so lines no longer jump mid-glide.
- Word-synced lines light up letter by letter.
- **Motion Lyrics** mode (player ⋯ menu, or Settings › Lyrics): kinetic, beat-reactive lyrics over the blurred
  artwork, or over a clear Spotify Canvas video.
### Added
- **3D Sound / Spatial 3D** (Settings › Sound): headphone virtual-speaker rendering for stereo, with an intensity
  slider. Off by default, bypassed in Pure hi-res. Labelled honestly: never called Atmos or surround.
### Fixed
- Spotify Canvas freezing after Motion Lyrics was on and another screen was opened.

## [v1.02] - 2026-11-08
_Internal 1.0.7 · versionCode 32._
### Sound and quality
- Quality defaults to **Lossless when available** on Wi-Fi and mobile data (existing installs move once); falls back
  to Max when no lossless copy exists.
- A gold **LOSSLESS** (or **HI-RES**) label next to the song name, shown only while the stream really is lossless.
- Automatic lossless upgrade: while a lossy stream plays, enabled lossless sources are searched in the background
  and a real lossless copy of the same recording takes over at the same position.
- New default source order: lossless sources first (Qobuz, Tidal/Monochrome, NetEase, Local, Subsonic, Jellyfin),
  YouTube Music last. Existing installs move once; a later reorder is kept.
- Sound enhancer (Light) and transient restore on by default (one-time migration; skipped on lowest-end devices).
  The EQ chip opens a Sound sheet over the player.
### Lyrics
- Word-by-word providers are asked first, and the chain prefers word-by-word over line-by-line over plain.
  Providers are asked in parallel (4 at a time).
### Look and feel
- Dynamic colours follow the cover's dominant colours, fading between songs.
- Queue keeps played songs above the playing one; full-screen pages are Lyrics, Queue, Related.
- Home shortcut bubbles removed (still in Library and Search).
### Fixed
- The update sheet kept offering the build that was already installed. The app now compares the installed APK with
  the release asset by SHA-256 (or exact size without a digest) and refuses to "update" to a versionCode that is not
  higher.

### Between 1.0.6 and v1.02
CHANGES.md also records the "v1" passes made after 1.0.6 (published as versionName "v1" / tag v1.01). It does not say
exactly which release each first shipped in, so they are listed here once rather than guessed:
- Low-end glass (Water film on lite devices) and recomposition passes; new red headphones app icon.
- Related songs pane restored; Spotify sign-in crash fixes (duplicate ids, sync errors, WebView reattach).
- Lossless source expansion: Tidal / Monochrome, Qobuz proxy, NetEase, FLAC radio stations.
- Word-by-word lyrics expansion: Musixmatch RichSync, AMLL TTML, QQ Music QRC, KuGou KRC, NetEase YRC, JioSaavn
  lyrics, Lyrics.ovh.
- History holds each song once; offline lyrics for downloads; crossfade, play/pause ramps and zero-wait next track;
  Listen Together heartbeat and reconnect.

## [1.0.6] - 2026-11-07
### One design each, two docks
- Now Playing is always the Aura player; Home is always Spotlight (big artwork). The Classic, Deck (cassette) and Poster players and the Classic, Aura and Bento Home dashboards were removed.
- Docks: Classic and Orb (floating cover) remain; Island and Split were removed.
- Settings › Appearance keeps only the Dock design choice. First-run setup (Customize) drops the Player and Home steps: 5 steps instead of 7.
- Saved settings and backups from older versions load unchanged apart from the design: an old player or Home choice becomes Aura / Spotlight, the Island dock becomes Classic and the Split dock becomes Orb. No other setting is reset.
- About 4,000 lines of UI code that only the removed designs used are gone (smaller app, faster to build).

## [1.0.5] - 2026-11-07
### Faster on 2 GB phones (same look, same features)
- Players (Classic, Aura, Deck, Poster): position, loop phases, reel rotation, pulses and heart bursts are now read only while drawing, so covers and decks no longer recompose every frame. The play/pause shape morph lives in its own small composable.
- Ambient loops run at about 30 FPS on lite devices, with the phase computed from elapsed time so speed and easing stay the same. Touch and scroll stay at full rate.
- Draw code reuses Paths, strokes and gradients instead of allocating them every frame.
- Glass: on Android 12+ full-screen content is no longer recorded twice per frame. Palette colours decode at 96 px in RGB_565 and are cached per cover. Song-tint fades no longer recompose the whole app.
- Lists: every lazy item has a unique key and a contentType (fixes possible "key already used" crashes on duplicate songs and pending downloads). Row data is remembered, and swipe rows don't recompose on every drag frame.
- Audio engine: no allocation in the real-time loop. EQ chains are built off the audio thread and swapped atomically. The resampler table at odd rates (e.g. 44101 Hz) is capped at about 1.6 MB instead of 37-75 MB.
### Fixed
- Library and play stats could be overwritten with near-empty data if you played or liked a song while they were still loading at startup.
- JSON saves run on IO, one at a time per file, atomically, and are flushed when the app goes to the background.
- Expired YouTube URLs after a long pause are refreshed and playback continues at the same position. The client is no longer benched for an expiry, which used to cause an audible source switch.
- Rapid skips cancel in-flight lyrics lookups, so old lyrics never land on the new song.
- Listen together: leaving a room closes its stream and cancels its coroutines.
- Bluetooth auto-play no longer leaks its media controller. Widget controls run on the player thread.
- Widget artwork is capped at 256 px, cutting each update from about 1 MB to about 380 KB (prevents TransactionTooLargeException).
- After an EQ crossfade into bypass, the rest of that buffer no longer gets the old EQ.

## [1.0.4] - 2026-11-07
### Fixed
- Crash when opening or closing the full player (corner radius went negative during the spring animation). All animated corner radii are now clamped at 0.
- Home: the blurred cover behind the hero spilled below it as a hard-edged strip. Background art is now clipped to its box (Home, Aura home, players).
### Added
- Playlist tools on every song list (online playlists and albums, Liked, History, Downloads, Cached, On this device): search inside, sort, Play next, Add to queue, Share; online albums/playlists can also be pinned to the top of Library.
### Faster
- Startup profile now also covers playback, data, settings and core code, so Android pre-compiles more of the app at install.

## [1.0.3] - 2026-11-07

### Seamless source switch
- A stream that fails mid-song (expired or refused URL, CDN error, Wi-Fi to mobile data) is now retried in the background while the buffer keeps playing: a fresh URL is fetched and playback continues at the same byte, with no pause.
- If the fresh stream is a different source or quality, the switch happens at the same position with a 0.6 s fade-in instead of a hard stop.
### Fixes from the final review
- AI Integration: clearing the Base URL or Model field no longer snaps back to the preset; picking a preset fills both.
- Resolver bookkeeping after a refused URL now runs on the main thread.

### Added
- **Backup & restore** (Settings › Backup & restore): one JSON file with settings, liked songs, playlists, saved
  albums/artists, history, play counts and your artists, saved anywhere through the system file picker. Restore
  asks first and keeps sign-ins. Downloads and sign-in tokens are not included.
- **Smart queue, always on:** duplicate songs (same id, or the same recording on another source) are removed as soon
  as they are added; songs already played leave the queue, so the playing song is always on top (not while shuffle
  or Repeat all is on).
- **Infinite play keeps ~20 songs lined up,** topping up whenever 10 or fewer are left (follows on through a second
  radio when one radio runs short).
- **Lyrics waiting dots:** during an intro or a beat/solo break in synced lyrics (word or line), three dots hop and
  light up in turn (OpenTune style) and the last line dims; they fold away when singing resumes.
- **Player ⋮:** "Cast / play on another device" (system output picker; Cast settings on Android 10) and "Pin to home
  screen" (a launcher shortcut with the cover that plays the song).
- **Playlist page:** search inside the playlist; Sort by (Custom, Recently added, Title, Artist, Album, Duration),
  remembered per playlist; ⋮ with Play next, Add to queue, Pin to top of Library, Add to home screen, Share (song
  list as text, plus the YouTube Music link for synced playlists), Rename, Delete. Own playlists: song ⋮ or
  long-press → "Remove from playlist".
- **Settings › AI Integration:** any OpenAI-compatible provider (OpenAI, Gemini, Groq, OpenRouter, DeepSeek, Mistral,
  Anthropic, or a custom https URL) with your own key (Android-keystore encrypted, never backed up). "Make my mix" sends
  only artist/song names from your stats, likes and history, finds the suggestions on YouTube Music/JioSaavn and saves
  an "AI Mix" playlist. Optional mood chips, 15/25/40 songs, Test button.
- **Settings › Listen stats:** 1 week, 3 weeks, 1 month, 1 year, all time. Minutes count-up hero, top artist, animated
  per-day/month/year bar chart, ranked Songs/Artists/Albums with animated bars (tap to play). New per-day play log,
  seeded once from history. Reduce motion respected.
- **Settings › Playback:** SponsorBlock (YouTube songs; hash-prefix lookup; choose categories), Play when Bluetooth
  connects (audio devices only), Pause at zero volume (resumes when turned up), Skip songs that fail (max 5 in a row).
- **Settings › Privacy (new):** Incognito (no history, stats, cached-song index or recent searches) and Block
  screenshots (FLAG_SECURE, live). Clear listen stats / recent searches.
- **Library › Cached songs:** every song played to the end stays in the media cache and is listed here; it plays with
  no internet (the cache is tried before any network call, so it also saves data). Oldest make room when the cache
  limit is reached; ⋮ › Remove from cache; Download-all turns them into permanent downloads.
- **Library › Listen together:** host a room (6-character code) or join one; any number of listeners hear the host's
  song, play/pause and seeks in sync (each phone streams on its own). Listeners can "Send to the room" from a song's ⋮.
  Uses the free ntfy.sh relay (configurable `togetherServer`); only song info and position travel.
- **Library › Live radio:** stations near you (approximate location, asked on tap; ~400 km, then your state), top
  stations in your country, and search, from the Radio Browser directory. Live streams are never cached or downloaded,
  skip lyrics and Infinite play.
- **History:** ⋮ or long-press on a song → "Remove from history" (just that entry).

### Changed
- The song menu hides "Play next" for the song that is already playing (the player's ⋮).

- **Interactive swipe-up to Queue / Lyrics.** The page follows the finger as a rounded card while the player sinks
  back (scales, rises, dims); release past a third of the screen, or flick, and it springs open, otherwise it falls
  back. A haptic tick marks the open point. Pulling the pane down shrinks it into a card that follows the finger.
- **Pane entrance cascade (Material motion).** Header, tabs, then queue rows (from the first visible one) and lyric
  lines (rippling out from the playing line) rise and fade in on the emphasized curve. Swiping between Queue and
  Lyrics makes the outgoing page recede. All draw-phase only; instant under Reduce motion.
- **Player motion.** A new song pops the cover in on a spring; tapping play bursts a ring out of the button; the
  playing lyric line has a soft glow in the song colour (not on lite devices).
- **Settings → Appearance → Blur intensity** (Off – 100%) for every blurred glass style. Off samples nothing.

### Changed / performance
- **Dock and mini-player glass no longer freeze.** Haze now samples the content behind the glass at ¼ (Android 8–11,
  lite devices) or ⅓ size and blurs that, so the blur keeps up with scrolling in real time; noise pass removed.
  The Liquid lens drops chromatic aberration, and lite devices skip its inner-shadow pass.
- Full-screen artwork washes (Classic and Aura players, Aura and Spotlight home) use a 32 px decode stretched with
  bilinear filtering instead of `Modifier.blur`: same soft look on every Android version, far cheaper, and glass on
  top no longer re-blurs a blurred layer.
- The player and Aura background drift is read in the draw phase only (no recomposition per frame) and is off on
  lite devices, where it made every glass control re-blur each frame.
- Synced lyrics recompose only when the active line changes; only the word-synced line reads the position each tick.

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
