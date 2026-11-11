# Pack 5 (Nayi Jeet), Part 1 of 2: personalised Home + Wrapped anytime (versionName/versionCode unchanged: v1.05 / 35)

Everything is built on data the app already keeps on the device (PlayStats per-track plays/repeats, the per-day log,
time-of-day buckets; History rows). No new tracking, no network.

## Files
- `data/HomeFeed.kt`: new pure `HomePersonal` (greeting by hour, "Jump back in" from History with "x ago", "Your top artists" from PlayStats; each returns empty when there is too little data).
- `ui/screens/HomeSpotlight.kt`: greeting via `HomePersonal.greeting`, refreshed at each hour; "Recently played" became "Jump back in" and moved up under the moods (same History data and threshold, skips the spotlight song, shows when played / play count, a paused loaded song resumes instead of restarting); new "Your top artists" row and a "Your stats, anytime" card after "On repeat" / time-of-day (both hidden without counted plays). Nothing removed; the other sections keep their order.
- `ui/screens/WrappedScreen.kt` (new): Your stats screen. Week / Month / Year / All time picker, "All your sources, counted together" line, cards in order: total plays, listening days + current streak, top 5 songs, top 5 artists, most repeated, favourite time of day; each card hides when the range has no data for it.
- `logic/Wrapped.kt` (new): `Wrapped.summarize(PlayStatsData, Range, today)` — the single pure aggregation (no Android) — plus `Wrapped.streak`.
- `ui/Nav.kt`: `Route.Wrapped` (one line).
- `ui/AppRoot.kt`: `Route.Wrapped -> WrappedScreen()` (one line; the route switch lives here, not in MainActivity.kt).
- `ui/screens/Library.kt`: "Your stats" tile under Listen together (entry point).
- `app/src/test/.../WrappedStatsTest.kt` (new, 10 tests): in/out of range (incl. future days), all-time counter, top-N order and cap with ties, most repeated / time of day limited to the range, hidden cards, streak (unbroken, broken by a gap or a zero day, still current until today ends), empty data → empty result for every range.
- `app/src/test/.../HomePersonalTest.kt` (new, 4 tests): greeting, Jump back in (new listener → empty), "ago" labels, top-artists threshold and order.

## Build (JDK 21, AGP 9.3.0, compileSdk 37)
- `compileDebugKotlin`: before BUILD SUCCESSFUL · after BUILD SUCCESSFUL (no new warnings in changed files).
- `testDebugUnitTest`: before 254 tests, 0 failed, 9 skipped (47 classes) · after 268 tests, 0 failed, 9 skipped (49 classes). +14 = the two new classes. No existing test was edited; none that passed before fails now.

## Notes
- Repeat counts and time-of-day buckets are lifetime per song (the day log holds plays only). For Week/Month/Year the range picks which songs qualify; "Most repeated" says "in total", and the time-of-day share scales each song's buckets to its plays in the range.
- History stores no playback position, so "resume" applies to the song currently loaded in the player; other cards play History from that song.
- "On repeat" ranking is unchanged (plays + 2 × repeats + likes, recency-decayed).
- No share-image export (Part 2).

# UpZe v1.05 (internal 1.0.10, versionCode 35): 12 small jobs

## 4A. True pass-through when every effect is off
- `playback/EngineAudioProcessor.kt`: when the engine is identity (EQ neutral or off, no EQ crossfade running, no
  bandwidth extension / transient restore / 3D Sound / upscale) and the levelling gain is exactly 1, each buffer is
  copied out byte for byte: no float conversion, no dither, no gain. The loudness meter and Live-lyrics meter read a
  float copy only when they're running. Any effect on → processing exactly as before. Pure/bit-perfect escape untouched.
- `dsp/Engine.kt`: `EngineCore.passThrough()` adopts pending settings (same code path `process()` uses, now
  `adoptPending()`) and reports identity at the same rate. Processing itself is unchanged.
- `app/src/test/.../EnginePassThroughTest.kt` (new, 5 tests): PCM16 and float byte-identical with identity config
  (EQ off, and EQ on but flat); EQ preamp, bandwidth extension, transient restore, 3D Sound and a ReplayGain gain each
  change the output.
Note: the app's defaults keep Enhance (light) and Transient restore on, so pass-through engages once those are off.

## 3E/3F. Idle loops gated (no behaviour change while playing or on screen)
- `playback/PlaybackService.kt`: the service ticker keeps its 200 ms (40 ms while fading) cadence only while
  **player.isPlaying OR a sleep timer is set** (its live countdown and fade). Otherwise it waits for playback to start
  or a sleep timer to be set (`tickerPlaying` / `Playback.sleepTick`), with a 30 s heartbeat (`IDLE_HEARTBEAT_MS`),
  and runs its body immediately on wake.
- `playback/PlayerConnection.kt`: the 250 ms position poll runs only while **playing OR (something collects `position`
  AND the app is in the foreground)** (ProcessLifecycleOwner ≥ STARTED); otherwise it stops. The position is sampled
  the moment the gate opens or closes and on every player event in `publish()` (seek, pause, track change), so the
  seekbar and lyrics clock never show a stale position.

## 3C/3D. Volume levelling: loudness normalisation + ReplayGain (one gain path)
Gain precedence (fixed, decided only in `GainPlan.decide`): a ReplayGain value for the chosen mode wins (Album falls
back to Track and vice versa); loudness normalisation fills in only when there is none, or when ReplayGain is Off;
otherwise unity. One decision → one gain stage, so the two can never double-apply.
Defaults: Loudness normalisation **Off**, ReplayGain **Off** (no change for existing users until switched on).
- `playback/Loudness.kt` (new): `GainPlan` (target -18 LUFS = ReplayGain 2.0 reference; boosts max +6 dB, only with a
  known peak, and never above peak −1 dB; cuts only while a song is still being measured), `ReplayGain` tag parser
  (Vorbis comments, ID3 TXXX, MP4 freeform, Opus R128), BS.1770 `LoudnessMeter`, and `LoudnessStore` (measured
  loudness per track id, persisted in `files/loudness.tsv`).
- `playback/EngineAudioProcessor.kt`: the single levelling gain stage (before the engine core, smoothed, hard-capped at
  full scale when boosting) plus per-track measurement; `setTrack(id, tags)`.
- `playback/PlaybackService.kt`: initialises `LoudnessStore`; on item transition / tracks changed, passes the current
  track id and its parsed ReplayGain tags (local files and streams alike) to the engine.
- `dsp/Engine.kt`: `ReplayGainMode` (Off/Track/Album); `EngineSettings.loudnessNormalize` / `replayGain`.
- `data/Settings.kt`: `AppSettings.loudnessNormalize = false`, `replayGain = OFF`, passed into `engine()`.
- `ui/settings/SoundPage.kt`: Settings › Sound › "Volume levelling": Loudness normalisation switch + ReplayGain
  choice (Off / Track / Album). Disabled while pure hi-res / bit-perfect is on (that path bypasses all processing).
- `app/src/test/.../LevellingTest.kt` (new, 7 tests): precedence, no double-apply, boost caps, tag parsing, BS.1770 meter.
Known limits: with gapless playback a new song's first fraction of a second can still play at the previous song's gain
(the sink processes slightly ahead of the transition event); the gain then eases to the new value. YouTube's own
loudness value isn't used (outside the files this job touched).

## 3A. Playback speed + pitch (0.5x–2x)
- `playback/PlayerConnection.kt`: session-wide `speed` state + `setSpeed(speed, keepPitch)` applied via
  `PlaybackParameters` (keepPitch = pitch 1.0 through Media3's Sonic time-stretch; off = pitch follows speed). It is
  re-applied in `publish()`, so it survives track changes, queue replacement and player/controller rebuilds.
  Helpers: `MIN_SPEED`/`MAX_SPEED`, `clampSpeed`, `speedLabel`.
- `ui/player/Panes.kt`: new `SpeedSheet` with a 0.5–2x slider (0.05 steps), presets (0.5/0.75/1/1.25/1.5/2x) and a
  "Keep natural voice (preserve pitch)" toggle.
- `ui/player/AuraPlayer.kt`: the previously unused `Ic.Speed` icon is now a Speed chip in the player deck (shows e.g.
  "1.25x" while not 1x) that opens `SpeedSheet`.

## 3B. Save the whole queue as a playlist
- `ui/player/Panes.kt`: Up next header gets a "Save queue as playlist" button that hands the entire queue (in order)
  to the existing `AddToPlaylistDialog` via `Menus.addToPlaylist`. The dialog and playlist code are reused unchanged.

## 2E. UI smoke tests (Robolectric + Compose, no screenshots)
- `app/src/test/java/com/ravi/musicspot/UiSmokeTest.kt` (new): 10 tests. Home, Library, Search, Player (AuraPlayerScreen)
  and Cached songs each compose with an empty and a populated state (local stores only, no network).
- `data/CachedSongs.kt`: test hook only. `internal fun publishForTest(entries)` sets `complete` (no index/cache change).
- `playback/PlayerConnection.kt`: test hook only. `internal fun publishForTest(state)` sets `state` without a MediaController.
  No behaviour change; nothing in the app calls either hook.

## 2D. Five silent failures now logged (PlaybackLog, redacted; no behaviour change)
- `source/SourceManager.kt`: `raceYouTube` fallback path: the empty catch on a fallback candidate's resolve, and the
  swallowed fallback-search failure, now log the track/source and the exception.
- `playback/PlaybackService.kt`: three blanket `runCatching` blocks (posted player action, resume URL refresh,
  refused-URL handling) now log the failure via `onFailure`.
- `source/MonochromeSource.kt`: `resolve` logs each mirror failover, from-mirror → to-mirror, with the reason.
- `source/QobuzProxySource.kt`: `resolve` logs each proxy failover, from-proxy → to-proxy, with the reason.

## 2C. Innertube parser fixture tests (tests only, parser unchanged)
- `app/src/test/java/com/ravi/musicspot/InnertubeParserTest.kt` (new): 8 tests covering search (All + Videos tab), album page
  (header credit, fixed-column duration), playlist page (own tracks vs Suggestions, both continuation shapes), and stream
  format parse/rank (`YtFormats` in `yt/YtStreams.kt`).
- `app/src/test/resources/innertube/{search,album,playlist,playlist_continuation,player_formats}.json` (new): small
  hand-written fixtures. No parser bug found; `InnertubeParser.kt` and `YtStreams.kt` untouched.

## 2A. Autoplay seed-guard fix + test update
- `playback/PlaybackService.kt`: `maybeAutoplay` skipped a seed whose id matched the previous seed (`autoplayFor`), even
  after the queue was replaced (e.g. replaying the same song, or a restored queue then `play(seed)`), so Infinite play
  never appended radio. `onMediaItemTransition` now resets `autoplayFor` on `MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED`
  (fires only when the current item itself is replaced; appends and tidy removals do not trigger it).
- `PlaybackThreadingTest.playViaPlayerConnection` (no timeout change, nothing skipped): the stale 1.0.2 assertion
  "played song removed" now asserts the 1.0.7 rule (played song kept at index 0, exactly once). The test tone went
  from 2 s to 60 s because every wait advances Robolectric's virtual clock and a 2 s tone auto-advanced past the seed.
- `app/build.gradle.kts`: versionName "v1.05", versionCode 35.

## 2B. Lint: `./gradlew lintDebug` runs and passes (16 errors -> 0; no baseline)
- `data/CachedSongs.kt`: UnsafeOptInUsageError x11, class-level `@OptIn(UnstableApi)` (SimpleCache/ContentMetadata).
- `playback/BluetoothAutoPlay.kt`: MissingPermission, explicit BLUETOOTH_CONNECT check on API 31+ before reading
  the device class; without it the class is unknown (null), the same result as the old swallowed SecurityException.
- `MainActivity.kt`: StateFlowValueCalledInComposition x2, initial `collectAsState` snapshot wrapped in `remember`.
- `ui/player/FullPane.kt`: UnusedBoxWithConstraintsScope, `BoxWithConstraints` -> `Box` (scope unused).
- `ui/settings/SoundPage.kt`: SuspiciousIndentation, `soundItems` body re-indented (whitespace only).
- 97 warnings + 5 hints remain as warnings (no lint baseline file was added; none are errors).

# UpZe v1.04 (internal 1.0.9, versionCode 34): fix pack, 4 fixes only

Verified here: `./gradlew assembleDebug` BUILD SUCCESSFUL (0 errors, debug APK built; no release/signed build).
`./gradlew testDebugUnitTest`: 224 tests, 214 passed, 9 skipped (LiveSmokeTest / LiveV205Test need MUSICSPOT_LIVE=1),
1 failed: `PlaybackThreadingTest.playViaPlayerConnection` ("Timed out waiting for autoplay radio appended", 15 s
wall-clock wait under Robolectric). It fails the same way on the untouched v1.03 source on this machine, so it is not
caused by this pack. 13 new tests, all pass. Not run on a device.

## 1. Monochrome never claims unverified FLAC (source/MonochromeSource.kt)
- The old probe returned `getOrDefault(true)`: a failed probe was labelled FLAC / 1411 kbps.
- Now `probeStreamFormat()` reads the first 12 bytes and returns Flac / NotFlac(codec) / Failed. FLAC / 1411 only on a
  confirmed `fLaC` header. A failed probe (exception, HTTP error, empty body) is labelled "Quality not verified" with
  no bitrate; non-FLAC bytes get the delivered format (AAC, MP3, Ogg) or "Quality not verified". A FLAC Content-Type
  alone is not proof. Neither label ever counts as lossless (`QualityEstimate.isLosslessStream`).
- Test: `MonochromeProbeTest` (8 tests, incl. probe failure by exception, unreachable host, HTTP 500, empty body).

## 2. Updater verifies SHA-256 before installing (update/UpdateLogic.kt, update/Updater.kt)
- `UpdateLogic.sha256Hex`, `verifyDownload` (MATCH / MISMATCH / NO_DIGEST), `verificationFailedMessage`.
- `Updater.update`: after the download (or a reused earlier download), the file's SHA-256 is compared with the
  release asset digest. Anything but a match: file deleted, sheet shows "Update failed verification: …", installer
  not launched. A release without a published digest is treated as unverifiable and is not installed.
- Test: `UpdateVerifyTest` (5 tests: match, mismatch with equal size, no digest, digest parsed from release JSON).

## 3. Cached songs: Clear all (ui/screens/LibraryExtras.kt, data/CachedSongs.kt)
- Header button (sweep icon, "Clear all") on Library › Cached songs. Confirmation shows the songs and the size that
  will be freed; confirming empties `Graph.cached` and removes those songs' media-cache resources, then the empty
  state shows. New `CachedSongs.clearAllPreview()` / `clearAll()`. Per-song remove and Settings › Storage unchanged.

## 4. Bookkeeping
- `app/build.gradle.kts`: versionName "v1.04", versionCode 34.
- `CHANGELOG.md`: v1.02 (1.0.7), v1.03 (1.0.8), v1.04 entries; public numbers as headlines, internal as a mapping.
- `README.md`: release badge v1.04.
- `ui/AppRoot.kt`, `lyrics/KuGou.kt`: stale comments corrected to match the code.

# UpZe v1.03 (1.0.8), source only, no APK built

Not compiled or run here (no Android toolchain in this pass); please run `./gradlew assembleDebug` and the unit tests once. New test: `Lyrics108Test` (lyrics clock + letter timing).

## 1. Faster opening (App.kt, MainActivity.kt, ui/AppRoot.kt, baseline-prof.txt)
- Settings, library + play stats, downloads, accounts and artist seeds now load **in parallel** on IO (were one after another), so the first screen appears sooner.
- The network warm-up (which builds the shared OkHttp client) moved off the main thread and runs after the stores are ready.
- The Recents icon bitmap is drawn after the first frame (`decorView.post`) instead of before it.
- The update check waits 4 s after start so it doesn't compete with the first screen.
- Baseline profile also covers `lyrics/**` and `account/**`.

## 2. Faster, lighter animations (nothing removed)
- `MotionSpec`: SHORT 210→150 ms, MEDIUM 420→280, EXIT 280→190; surface spring stiffness 214→400, pop 306→560; dock/container/player springs ~1.7x stiffer; fade-through, shared-axis, container transforms, stagger and icon swaps shortened to match. Same curves, same moves, just quicker.
- Menu rows, list stagger, song-menu cutout, full-screen pane cascade (1000→600 ms clock), pane open/close springs, player cover/play-button/seek-bar springs, theme colour glide (980→600 ms), SoftArt crossfade (700→420 ms), dock accent (980→560 ms) all quicker.
- Ambient loops (aura drift, cover breathing, shimmer) are now paced on every device (~30 FPS; ~20 FPS on lite devices). They're seconds-long motions so they look the same, but the glass above them re-blurs half as often.
- Cover shadow 30→18 dp; Lossless glow 14→8 px.

## 3. Full-screen lyrics: smoothness + sync
- New `rememberSmoothPosition()` (ui/player/LyricsClock.kt): the player position was only updated every 250 ms, so words lit up to 1/4 s late and in steps. The lyrics now run on a frame-accurate clock anchored to the player (drift eased in, seeks snap).
- The active line glides to centre in 520 ms (was 1260 ms, which read as lag); line colour 980→320 ms, line scale spring 56→260.
- Lighter: drum tilt 30°→18° (off on lite devices), active-line glow 28→14 px, only the visible pager page is kept composed.

## 4. Lighter blurs (ui/glass/Glass.kt, ui/motion/Surfaces.kt)
- Liquid glass blur 6..26 dp → 4..16 dp, and the inner-shadow pass is removed on all devices.
- Frosted 8..34 dp → 6..20 dp; Haze (Android 8–11) 8..32 dp → 6..18 dp sampled at 1/4 (1/5 on old/lite) instead of 1/3 (1/4).
- Focus blur behind sheets/menus 26 dp → 12 dp.

## 5. Queue: softer 3D at the bottom
- `scroll3d(below = …)`: rows below the playing song get 30% of the effect (≈8° tilt, 96% size, 85% opacity at the edge). Rows above keep the full drum. Flat rows skip the 3D setup entirely. Queue auto-scroll 900→520 ms.

## 5b. Letter-by-letter sync in full-screen lyrics
- Word-synced lines now light **letter by letter**: each word's time is shared across its letters, with a soft wipe through the letter being sung (`LetterSyncedLine` in Panes.kt). The line is laid out once; only a clip moves per frame (no recomposition). RTL lines wipe right to left.

## 6. Motion Lyrics mode (ui/player/LiveLyrics.kt, playback/BeatMeter.kt)
- Named **Motion Lyrics** in the UI. Player ⋯ menu › **Motion Lyrics** (and back: **Turn off Motion Lyrics**); also Settings › Lyrics › Motion Lyrics. Saved as `AppSettings.liveLyrics` (internal key unchanged).
- The artwork moves behind the lyrics: while Motion Lyrics play, the player background becomes the artwork full-screen and blurred (the cheap 32 px SoftArt wash, stronger and less zoomed, plus a dark wash), and the sharp cover fades out of the way. Lyrics are white, so they read on any cover and in light theme. Songs without synced lyrics keep the normal cover. The cover stays composed, so its swipe / double-tap gestures and the hero animation still work; the overlay takes no touches.
- Songs with a Spotify Canvas: the video stays clear (no blur, no dark wash, no glow) and Motion Lyrics still play over it, with a soft text shadow instead of a scrim and fewer particles. Songs without a Canvas get the full-screen blurred artwork behind the lyrics.
- Load: the Motion Lyrics frame loop is capped at ~60 fps (30 fps on lite devices and over a Canvas video) even on 90/120 Hz screens; skipped frames touch no state, so nothing redraws. The blur is the existing 32 px SoftArt wash (no RenderEffect).
- Fixed two comment typos from the speed pass that would not have compiled (SoftArt's Crossfade and the menu row entrance in Overlays).
- Replaces the artwork with a kinetic lyric stage: each line lands in a new spot (centre, corners, tilted blocks) zooming through the last one; every word pops in when sung with a spring overshoot; the sung word glows in the song colour; the block floats and kicks on the beat; beat ripples, a pulsing glow and drifting particles fill the screen; accent colour follows the song's tone (bass vs treble); instrumental breaks show bouncing dots; the next line waits faintly at the bottom. Tap opens full-screen lyrics.
- Beat/tone come from the sound engine's own audio (`BeatMeter`: RMS, ~150 Hz bass, >2.5 kHz treble, metered only while the stage is on screen). In Pure hi-res (engine bypassed) the sung words drive the pulse.
- Songs without synced lyrics (and while loading) show the normal artwork. The lyrics capsule hides in this mode. Reduce motion keeps the words, drops the motion; lite devices get fewer particles and no glow.

## 7. 3D Sound / Spatial 3D (dsp/Spatial3D.kt, dsp/Engine.kt, ui/settings/SoundPage.kt)
- New engine stage after transient restore, before the resampler and limiter. Stereo only (mono and multichannel pass through untouched); bypassed in Pure hi-res / bit-perfect like the rest of the engine.
- HRTF-style headphone rendering from lightweight DSP only (~60 multiply-adds per frame, no libraries, fine on Android 10 / API 29): shuffler widening of the side signal above ~250 Hz; crossfeed with a ~0.3 ms interaural delay and ~1.1 kHz head-shadow low-pass; pinna/front cue (+2.5 dB at 3 kHz, −2.5 dB at 8.5 kHz); early reflections from a sparse 6-tap-per-ear synthetic room impulse (7–27 ms, damped above 5 kHz); level compensation.
- **Intensity** 0–100% scales every stage. On/off and intensity changes glide over ~40 ms (no clicks); switching off fades to dry, then the stage is dropped so the engine can go bit-exact again. The delay lines are built on the settings thread, never on the audio thread.
- UI: Settings › Sound (and the player's Sound sheet) › **3D Sound**: "Spatial 3D" switch + Intensity slider. Engine badge shows "3D Sound"; Credits shows its state. Labelled honestly: it is virtual speakers for stereo on headphones. Never called Atmos, and it doesn't claim surround or height channels.
- Settings: `AppSettings.spatial3d` (off by default) and `spatialIntensity` (0.5). Engine: `EngineSettings.spatial` / `spatialIntensity`, `EngineStatus.spatial`.
- Realism pass: speakers modelled at ±30°, ~2 m, in a medium room. ITD 0.26 ms; the far ear's head shadow is now a shelf (lows in full, highs at a quarter) instead of a plain low-pass; deeper pinna notch; image-source early reflections (floor bounce at 1.6 ms, then side walls, ceiling, back wall to 29 ms, later bounces darker); and a low-level late room tail (4-line Hadamard FDN, ~0.35 s RT60, 18 ms pre-delay, damped). Still ~110 multiply-adds per frame. Offline check on noise: level change about −0.5 dB, stable decay.
- Tests: `Spatial3DTest` (off = bit-exact, mono untouched, crossfeed works without overload, fade-out finishes, label has no "Atmos").

## 8. Home (dashboard) smoothness
- **Main fix: the stagger bug.** `staggerIn(i)` resolved to the Motion.kt overload, which ignored the page's StaggerGroup. So every shelf, header and row that scrolled into view faded in again, after `index × 30 ms` with no cap (section 15 ≈ 0.45 s invisible), and dozens of fades ran during every fling. Now only the screen's first load cascades (index capped at 6), and anything that appears later shows at once. This applies app-wide (Library, Search, Collections too).
- Finished downloads are derived off the main thread with `distinctUntilChanged`. Before, every progress tick of a running download recomposed and re-sorted the whole Home.
- The spotlight's hero list is remembered (was rebuilt every pass), and its tint glide is read in the draw phase (was recomposing the spotlight every frame for 700 ms on each song change).
- The spotlight's Shuffle pill is a still glass film instead of a live backdrop blur that re-rendered on every scroll frame.
- Card shadows are 6 dp (was 10 dp), and off on lite devices.

## 9. Full-screen lyrics: smoothness and sync feel
- Lines switch 150 ms before their timestamp (`LYRIC_LINE_LEAD_MS`), so the glide to centre and the colour change finish as the voice starts. Before, the animations only began at the timestamp, which read as ~0.3 s late. Letters lead by 40 ms. The same line lead applies to Motion Lyrics.
- Colour change is 220 ms (was 320 ms).
- One font weight for every line (was Black when active, ExtraBold otherwise). The weight change re-wrapped the line and changed its height mid-glide, so the scroll jumped.
- The letter sweep draws with one rectangle clip per wrapped line instead of a Path with one rect per letter (and a bounding-box query per letter) every frame. The sung glow is 10 px (was 14 px).

## 10. Spotify Canvas freeze fix (Motion Lyrics on → open something → back to the player)
- Each new video frame now also invalidates the Compose draw of the video view. The player background is recorded into the glass/blur layer (Haze on Android 8–11, the backdrop layer on 12+), and a TextureView updating on its own didn't always re-record it. So the screen showed a stale frame while the video kept running.
- `CanvasVideo(url, modifier, visible)`: the video pauses while hidden (player collapsed below 30%, a full-screen Lyrics/Queue/Related pane, or the app in the background). On return the surface is re-attached, the player re-seeks for a fresh frame, and playback resumes.
- Watchdog (250 ms poll): no frame for 1.5 s while it should be playing → re-attach and re-seek. A second stall, or any player error → the player is rebuilt (`key(url, generation)`).

## Version
- versionCode 33, versionName "v1.03" (tag your release `v1.03`).

# UpZe v1, pass 3 (source only, no APK built)

Verified: compiles with 0 errors and 173 JVM unit tests pass (5 new; 3 lyrics-chain tests updated to the new rule). Not run on a device.

## Pass 3c
- **Dynamic colours** (ui/theme/Theme.kt, logic/ArtTheme.kt): the cover's colours are now scored by how much of the cover they fill and how colourful they are. Before, the app used Palette's "vibrant" swatch, which is often just a tiny speck. Backgrounds and surfaces follow the cover's dominant hue; the accent comes from its strongest colourful colour. Saturation is kept within readable limits instead of being flattened to grey. Black-and-white covers stay neutral. The whole colour scheme fades between songs over 980 ms: primary, containers, all surfaces, secondary text and outlines. Before, only 5 colour roles faded. The player background and the dock use the same picking.
- **Queue** (PlaybackService.tidyQueue, ui/player/Panes.kt): played songs are no longer removed. They stay above the playing song. When the song changes, the queue scrolls so the playing song sits at the top. The playing row is highlighted with a softly breathing band in the artwork colour. Played rows are slightly dimmed. Duplicates are still removed, but only from upcoming songs. Only history beyond 150 played songs is trimmed (oldest first), so memory stays bounded on 2 GB phones. The 3D tilt now centres on the playing song at the top.

## Pass 3b
- Quality defaults to **Lossless when available** on both Wi-Fi and mobile data. Existing installs move to it once (settings revision 2). If no lossless copy exists, it falls back to Max. Note that lossless uses much more mobile data.
- Player: a small glowing gold **LOSSLESS** label (or **HI-RES** for 24-bit) appears next to the song name in the main deck, the compact header and the full pane, but only while the stream really is lossless. It breathes slowly and stays steady when reduced motion is on.
- Home: removed the shortcut bubbles (Liked, Downloads, History, On device, Search). They are still available in Library and the Search tab.
- With mobile data on Lossless too, the auto lossless upgrade now also runs on mobile data.

## Source order (data/Settings.kt)
- New default: Qobuz, Tidal/Monochrome, NetEase, Local, Subsonic, Jellyfin (lossless), then Audius, Jamendo, Internet Archive, SoundCloud, then JioSaavn second to last and YouTube Music last.
- Existing installs are moved to this order once (`orderRevision`). A later reorder in Settings is kept.
- New installs have Qobuz turned on too.

## Lyrics order (data/Settings.kt, lyrics/LyricsRepository.kt)
- Default order is word-by-word providers first (LyricsPlus, AMLL, Musixmatch, QQ Music, NetEase, KuGou), then line-by-line (LRCLIB, JioSaavn), then plain (YouTube Music, Lyrics.ovh). Existing installs migrate once.
- The chain now prefers by type, not by whichever answered first. Word-by-word beats line-by-line, and line-by-line beats plain. Before, a line-synced answer stopped the search, so it hid word timing from providers later in the list.
- Providers are asked in parallel (4 at a time) and answers are read in your order, so a full search no longer adds up every provider's wait.

## Automatic lossless upgrade (playback/PlaybackService.kt, source/SourceManager.kt, source/Routing.kt)
- Once a song is playing from a lossy stream, the app searches the enabled lossless sources in the background. When it finds the same recording as a real lossless stream (FLAC/ALAC/WAV, or 600+ kbps), it switches at the same position with the usual fade-in.
- The upgrade is tried once per song. It is skipped for live radio, downloads, offline playback and the High quality setting, so it is off on mobile data by default.
- Lossy cached songs no longer lock the quality. When online and upgrades are wanted, the sources are asked first and the cached copy is only the fallback. A lossless cached copy always plays from cache. The cache entry is replaced by the lossless stream after an upgrade.

## Update keeps appearing (update/Updater.kt, update/UpdateLogic.kt)
- Root cause: GitHub release tag `v1.01` contains an APK whose versionName is `"v1"` (versionCode 31). The app compared "1.01" with "1", so it offered the update forever, even right after installing it.
- Fixes: (1) The app compares the installed APK with the release asset by SHA-256 (GitHub's asset digest), or by exact size when there is no digest. If they match, it reports up to date. (2) After download, the APK's versionCode is read. If it isn't higher than the installed one, the installer doesn't open, the sheet says "You already have the latest version.", and the tag isn't offered again.
- For future releases, keep the tag and `versionName` the same (tag `v1.02` means versionName `"v1.02"`) and raise `versionCode` each time. Note: this zip has versionCode 27, but the published v1.01 uses 31. Set 32 or higher before your next build, or Android will refuse to install it over v1.01.

# UpZe v1 — Lossless Sound Sources & Word-by-Word Lyrics Expansion

- **Sound Source Expansion (Tidal / Monochrome HiFi Pool)**: Added `MonochromeSource` (`SourceKind.MONOCHROME`, prefix `"mono"`), querying Tidal catalogue with verified genuine 16-bit 44.1kHz / 24-bit Hi-Res FLAC via mirror pool (`tracks.monochrome.st`, `api.monochrome.tf`, `frankfurt-1.monochrome.tf`, `ohio-1.monochrome.tf`) with automatic `fLaC` header validation to guard against stream cuts.
- **Sound Source Expansion (Qobuz Lossless Proxy)**: Added `QobuzProxySource` (`SourceKind.QOBUZ`, prefix `"qob"`), offering experimental lossless FLAC streaming via proxy mirrors.
- **Sound Source Expansion (NetEase Cloud Music)**: Added `NetEaseSource` (`SourceKind.NETEASE`, prefix `"ne"`), offering full mainstream catalogues with Lossless FLAC (16-bit 44.1kHz up to 24-bit Hi-Res `br=740`/`999`) and 320 kbps MP3 without requiring any user account. Integrated into search, routing, failover, settings, and diagnostics.
- **Bit-Perfect Lossless Radio (Radio Browser)**: Added `Radio.flacStations()` helper querying 24/7 audiophile FLAC lossless broadcast stations (Radio Paradise 4 channels, Mother Earth Radio, JB Radio-2, Czech Radio D-Dur) with ICY metadata.
- **Lyrics: Musixmatch RichSync & Synced Flow**: Added `Musixmatch.kt` (`MusixmatchProvider`, id `"musixmatch"`), acquiring anonymous tokens to parse word-by-word `RichSync` JSON with automatic fallback to line-synced LRC. Superior coverage for Hindi, Punjabi, Bollywood, and English music.
- **Lyrics: AMLL TTML Syllable Lyrics**: Added `Amll.kt` (`AmllProvider`, id `"amll"`), querying `api.amll.dev` for Apple Music-style TTML word-by-word syllable timings with automatic fallback to synced LRCLIB mirror.
- **Lyrics: JioSaavn Lyrics**: Added `JioSaavnLyrics.kt` (`JioSaavnLyricsProvider`, id `"jiosaavn_lyrics"`), fetching official synced and plain lyrics directly from JioSaavn's Indian music catalogue.
- **Lyrics: Lyrics.ovh Fallback**: Added `LyricsOvh.kt` (`LyricsOvhProvider`, id `"lyricsovh"`), keyless open REST fallback for plain text lyrics.
- **Lyrics: QQ Music QRC Syllable Timings**: Upgraded `QQMusic.kt` to parse word-timed syllable QRC formatting via `YrcParser` and BetterLyrics `/qq` endpoints.
- **Lyrics: KuGou KRC Decryption**: Implemented `KrcDecryptor` (16-byte XOR key + zlib inflate) and `KrcParser` in `lyrics/SyllableLyrics.kt`. `KuGou.kt` fetches exact syllable-timed word-by-word lyrics with seamless fallback to line-synced LRC.
- **Lyrics: NetEase YRC**: Implemented `YrcParser` parsing `yrc` format with exact word start and duration offsets into `LyricWord` and `LyricLine(wordSynced = true)`.
- **Word-by-Word Chain Priority**: Default `lyricsOrder` updated to prioritize word-by-word capable providers ahead of line-only and plain providers: `lyricsplus` → `musixmatch` → `amll` → `kugou` → `netease` → `qqmusic` → `jiosaavn_lyrics` → `lrclib` → `ytmusic` → `lyricsovh`. All existing sources strictly preserved.

# UpZe v1, fix pass 2 (source only, no APK built)

Verified: the whole app compiles (0 errors) and 168 JVM unit tests pass (3 new). Not run on a device.

## Related songs is back (ui/player/AuraPlayer.kt, Panes.kt, yt/YtMusic.kt)
- When Queue and Lyrics moved full screen, the Related pane (and Credits) lost every way in: the deck only had Lyrics / Up next / Sleep / Sound. A Related chip (Explore icon) now sits after Up next. It opens the in-player pane with the same rise animation, and its switcher still reaches Credits.
- When YouTube Music has no Related tab for a song, the pane shows "Radio from this song" instead of an empty page.
- `YtMusic.related()` no longer does a no-op walk of the whole response tree or keeps a throwaway list.

## Spotify sign-in crash
- `data/LibraryClean.kt` (new) and `data/Library.kt`: an import could store the same song id twice (a Spotify playlist with a repeated song, or two Spotify songs matched to one playable recording). Every keyed Lazy list throws on a repeated key. Merges now keep each id once (O(n) with a hash set; `mergeLiked` was O(n·m)), synced and created playlists are de-duplicated, and libraries already on disk are cleaned at load and on backup restore.
- `account/Sync.kt`: sync catches Throwable (minus cancellation), so an OutOfMemoryError or StackOverflowError on the IO thread is reported as "Sync failed" instead of killing the app. Matching runs in chunks of 64 songs (same 4-way parallelism) instead of creating thousands of coroutines at once.
- `core/JsonFile.kt`: `BackgroundFaults` handler on IoScope, the lyrics scopes, the PlaybackService IO scope and the Spotify sign-in scope. A failed background job is logged instead of crashing the app.
- `ui/account/SpotifyConnectActivity.kt`: the WebView frame is detached before it is re-attached (fixes "child already has a parent"). After the token check, nothing runs if the screen was closed. On success the screen closes before the cookie wipe, so the wipe's callback no longer touches a destroyed WebView.
- Repeated ids are also guarded in the artist picker search and the Add-to-playlist sheet.

## 2 GB phones
- `ui/components/OffMain.kt` (new) and `HomeSpotlight.kt`: Home's Favourites, time-of-day picks, artists and mixes are recomputed on Dispatchers.Default. They used to run groupBy/sort over the whole library on the main thread at every library edit, so Home stuttered during an import. The first frame is unchanged.
- `App.kt` `onTrimMemory`: under pressure, Coil's memory cache and the blurred-art cache are trimmed or cleared (covers reload from the disk cache). This makes the low-memory killer less likely to close the app while music plays.

# UpZe v1 (versionName "v1", versionCode 27): low-end glass and recomposition pass

Verified: the whole app compiles (Kotlin 2.4.21 + Compose plugin, 0 errors), 165 JVM unit tests pass (3 of them new), and Gradle assembleRelease runs with R8 and is signed with the permanent upze key. The 4 Robolectric/live tests weren't run. No animation or feature was removed. Aura drift, Vinyl, lyrics glow/word highlight and Listen Together are unchanged on capable devices.

## ui/glass/Glass.kt
- New `effectiveGlass(chosen, lowEnd, liteFx)`: Graph.lowEnd → Solid (unchanged). On Graph.liteFx devices, Liquid and Frosted render as Water: a translucent song-tinted film with sheen and 1 dp rim (`HazeGlass.water`). No Kyant backdrop layer is recorded, no blur/lens RuntimeShader runs, and no Haze RenderScript blur runs on Android 8–11 lite devices. Lists scrolling under the dock, mini player and player decks no longer re-blur per frame.
- `glassStyle()` and `Modifier.glass()` both go through it, so GlassLayer stops recording the sampled layer on lite devices automatically. The user's Settings choice isn't overwritten.

## ui/player/AuraPlayer.kt
- `AuraSeekFill`: the played part is drawn in `drawBehind` (DrawScope `inset` to the played width, same gradient, same pill ends). The position is read only at draw time: no recomposition and no re-layout per tick or scrub frame.
- `AuraMiniProgress`: the position State is read in the draw lambda (it was read in composition with `fillMaxWidth(frac)`).
- Removed the unused `Modifier.blur` import (no real-time blur remains; the wash is the cached SoftArt).

## ui/player/Player.kt
- `PlayerTickText`: the string is a `derivedStateOf` over the latest lambda, so the elapsed/remaining times recompose once per displayed second instead of on every 250 ms tick.

## ui/chrome/Chrome.kt
- `MiniProgressLine` (mini player): the position is read at draw time only.

## ui/screens/HomeSpotlight.kt
- Removed the unused `blur` import and the dead `blurOk` flag (the hero already uses the cached SoftArt wash, so there is no blur pass).

## ui/player/Panes.kt
- Already deferred in the previous pass: synced lyrics take the position State unread, the word highlight is a leaf, the capsule's active line comes from `derivedStateOf`, and romanization runs off the main thread. Lazy keys and contentType are set.

## Lazy lists (Overlays.kt, LibraryExtras.kt, settings/*.kt)
- All 55 static `item {}` blocks now have stable `key` + `contentType`, and the Add-to-playlist list has a contentType. All `items()`/`itemsIndexed()` calls across the UI already had keys.

## Tests
- `LogicTest.kt`: `GlassGradeTest` (capable device keeps the grade; lite swaps Liquid/Frosted for Water; low-end is always Solid).

## App icon
- The new red headphones replace the launcher icon. The adaptive foreground is at every density, with a black adaptive background (`ic_launcher_background` #FF000000), legacy square and round PNGs, and a white headphone silhouette for Android 13+ themed icons. The widget preview and pinned shortcuts use the new icon automatically. The notification small icon (vector) is unchanged.

## Note on "v1"
- The in-app updater compares semantic versions. "v1" reads as 1.0.0, which is lower than any 1.0.x GitHub release, so the update sheet will offer the GitHub 1.0.6 release as an "update". Tag the next GitHub release v1.x or higher (for example v1.1) before relying on the updater.


# UpZe 1.0.6 → performance, stability & feature pass

Verification done here (no Android SDK platform was available, so no Gradle/APK build or device run):
- Whole `app/src/main` compiled with Kotlin 2.4.21 + Compose compiler plugin + serialization plugin against the project's real dependency jars: **0 errors** (the original tree compiled the same way, also 0 errors).
- `app/src/test` compiled against it and run with JUnit 4.13.2: **162 tests, all passing** (every test file except the 4 Robolectric/live-network ones: StartupRobolectricTest, PlaybackThreadingTest, ThemeContrastTest, LiveSmokeTest — those need the Android SDK/Robolectric runtime; none of their targets' public APIs changed).
- Golden rule respected: no animation, Aura, Liquid Glass, 3D Scroll, Vinyl or feature was removed, disabled or downgraded.

Already present in 1.0.6 and verified, so left as is: ambient loops paced to ~30 FPS on lite devices (`Loops.kt` + `DeviceTier`), RGB_565 software blur cache ≤96 px (`ArtBlurCache.kt`) and RGB_565 palette with LRU (`Theme.kt`), allocation-free DSP frame loop with reused buffers (`Engine.kt`, `EngineAudioProcessor.kt`), widget art capped at 256 px (`NowPlayingWidget.MAX_RV_PX`), lyrics cancellation + binary search (`LyricsChain`, `activeIndex`), expired/403 URL re-resolve at the same position (`refreshStaleUrlOnResume`, `onLoadExpired`), per-file Mutex in `JsonFile`.

## Phase 1 — History duplicates (fixed)

### data/HistoryEntry.kt (new)
- `HistoryEntry(track, atMs, source, plays)` moved out of Library.kt; `plays` defaults to 1 so old JSON still loads.
- `HistoryOps.upsert`: removes the song's old row, inserts it at index 0 with the new `playedAt`, `plays + 1`; a replay within 30 s of the same play is not double-counted.
- `HistoryOps.dedupe`: one row per song (newest kept, plays summed) for existing dirty history; `isClean`, `remove`, `merge` (backup/sync merge without duplicates).

### data/Library.kt
- History deduped on load and written back once if it was dirty.
- `recordPlay` uses `upsert`; `mergeHistory` uses `merge`; `removeHistory` removes the song's row; `replace` dedupes.
- Library JSON write debounce set to 1500 ms (I/O stays on Dispatchers.IO behind the per-file Mutex).

### data/PlayStatsData.kt (new) / data/PlayStats.kt
- Play counts kept in stats (unchanged semantics) via pure `PlayStatsOps.count/seed`, plus a per-song time-of-day histogram (`dayparts`) for the smart mix. `timeOfDayPicks()` added.

### ui/screens/Collections.kt
- History list: stable id-only `key` (one row per song), `contentType`, `Modifier.animateItem()` so a replayed song animates to the top instead of duplicating. Row menu looks up by track id.

### ui/screens/Library.kt
- History tile counts songs, not plays.

## Phase 2 — Performance

### dsp/Engine.kt
- EQ changes no longer cut a running crossfade: while the 2048-frame (~43 ms) EQ crossfade is active, new settings coalesce (latest wins) and are adopted when it ends. Removes the tick heard while dragging EQ sliders; still zero allocations on the audio thread.

### ui/player/Panes.kt
- Synced lyrics: romanization computed off the main thread (`produceState` on Default); `itemsIndexed` with stable keys.
- Lyrics capsule: active line via `derivedStateOf`; position is read only in a new leaf (`CapsuleWords`), so the 40–60 Hz position tick no longer recomposes the whole capsule.
- Related-songs lazy items keyed.

### ui/screens/LibraryExtras.kt
- Radio station lists: stable keys (`distinctBy` guards duplicate ids that would crash LazyColumn), `contentType`; section headers keyed.

## Phase 3 — Stability

### source/SourceManager.kt
- `hedged()`: for non-YouTube primaries, a backup source is started if the primary has not answered after `HEDGE_AFTER_MS = 1500`; first success wins, the loser is cancelled.

### together/RoomProtocol.kt / together/ListenTogether.kt
- 15 s heartbeat with a 60 s stale watchdog; reconnect with exponential backoff 2 s → 30 s with +25 % jitter; resumes from the last message id so no events are lost or replayed. Pure helpers `isStale`, `nextBackoff`, `backoffWithJitter` (unit-tested).

## Phase 4 — Features

### logic/Fades.kt (new)
- Equal-power `rise/fall` (sin/cos), `crossfadeGain`, `inCrossfade`, 150 ms `rampUp/rampDown` in 8 ms steps.

### playback/PlaybackService.kt
- 150 ms volume ramp on play/pause/setPlayWhenReady (through `SafePositionPlayer` + `Fader`), widget toggle included; resuming after audio-focus loss (a call, another app) also ramps up instead of jumping in. Ducking itself stays with ExoPlayer. All ExoPlayer access stays on Main.immediate.
- Equal-power 1–10 s crossfade (40 ms fine steps only during fades; coarse ticks otherwise, queue saved every 5 s).
- Zero-wait next track: at 80 % of the song or 20 s before the end, resolves the next URL and pre-caches 512 KB with `CacheWriter`.
- Autoplay uses time-of-day affinity.

### logic/Taste.kt
- `TimeOfDay` (morning/afternoon/evening/night, Laplace-smoothed song and artist affinity, boost) and `autoplayMix(daypartAffinity)`.

### ui/screens/HomeSpotlight.kt / ai/AiMix.kt
- "For this time of day" shelf on Home (shown with ≥ 4 songs); the AI mix prompt includes time-of-day context.

### lyrics/LyricsRepository.kt / App.kt / data/Downloads.kt
- Offline lyrics: on download, synced lyrics are saved as `<id>.lrc` plus `<id>.json` (with romanized lines) under `files/downloads/lyrics`; deleted with the download. `lyricsFor` reads offline first, so lyrics work in airplane mode. Romanization LRU-cached on Default.

## Tests
- `LogicTest.kt`: `HistoryDedupTest` incl. **`sameTrackPlayedThreeTimesIsOneHistoryEntryWithLatestTimeAndThreePlays`** (3 plays → exactly 1 History entry, latest timestamp, `plays == 3` in History and stats), `TimeOfDayTest`, `FadesTest`.
- `ListenTogetherTest.kt`: `ListenTogetherHeartbeatTest`.

## Pass 3d (2026-11-08) — v1.02 (versionCode 32)
- EQ chip opens a Sound sheet over the player (same controls as Settings → Sound).
- Sound enhancer (Light) and transient restore on by default; one-time migration (orderRevision 3); skipped on lowest-end devices.
- Smoothness: lyrics rows recompose only when their own state changes; word-synced line rebuilds once per word (was every 250 ms tick, with glow); styles remembered; queue keys no longer include the row index; cascade no longer reads firstVisibleItemIndex in composition.
- Full screen pages ordered Lyrics, Queue, Related (Related now full screen too; swipe between all three).
- Up next icon → PlaylistPlay (distinct from Lyrics).
- Lyrics one weight bolder (active Black, others ExtraBold).
- Compile 0 errors, 185 JVM tests pass. Not device-tested, no APK built.
