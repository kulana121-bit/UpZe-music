# v1.05 — Pack 6 (Bade Khwab), Part 2 of 2: DJ Mode + music recognition (the last pack)

versionName / versionCode unchanged (v1.05 / 35). Source only: no APK, no release. Built on the Pack 6 Part 1 source.

## Job 1: DJ Mode (bundle item #13). Done, with the two-player overlap. Not yet run on a phone.
- **BPM analysis** (`logic/DjMode.kt`, pure): decoded PCM is reduced on the fly to a 5 ms log-energy onset curve (about 6,000 floats per 30 s window; no PCM is held). Autocorrelation finds the beat period (70–180 BPM; slower songs read double time, faster ones half time). A comb search then refines the BPM to ~0.02 BPM and finds the first beat.
- **No fake numbers.** A window with normalised autocorrelation below 0.15, silence, or under 8 s of audio returns null. A track whose head and tail tempos differ by more than 2% is also unknown (the tempo drifts). In the store, unknown is a real state ("U"). DJ Mode falls back to the plain crossfade for those tracks.
- **"Downbeat" means the first detected beat.** There is no bar (1-2-3-4) detection.
- **Analysis** (`data/BpmStore.kt`): BpmAnalyzer decodes two windows: the first 30 s, and the last 30 s (so the outgoing grid is measured where the transition happens). It uses MediaExtractor + MediaCodec through a small random-access adapter (Media3MediaDataSource). That adapter reads through the player's own resolving/cache data source, so headers, chunking and the media cache all work as in playback.
- The analysis runs on one background-priority thread and only while DJ Mode is on: for the playing song and the next song. Memory is the codec's buffers plus a few 256 KB read blocks.
- It costs some data: about 30 s at each end of a song. The head is mostly bytes the player prebuffers anyway.
- **Store:** results are cached per track in `bpm.tsv` (append-only, max 5,000), and a cached track is never analysed again. A failed analysis (network/decoder) is NOT stored. It is tried again after the player service restarts.
- **Tempo match:** `DjMode.tempoMatch` gives the incoming song's speed factor, with an octave-aware half/double-time match. The cap is ±6%. Beyond 6%, or with either BPM unknown, the factor is exactly 1.0 and the transition is the plain crossfade at normal speed.
- The factor multiplies the user's speed (Pack 3 `PlaybackParameters`). Pitch handling is unchanged (`DjMode.parameters`): keep-pitch stays pitch 1.0, and otherwise pitch follows the speed, as the speed picker does.
- After the handoff, the matched tempo eases back to the user's own speed over 8 s.
- **Beat-aligned transition with a real overlap** (`PlaybackService.Dj`): the transition is armed 6 s before the crossfade window when both tempos are known and within the cap. A second ExoPlayer (the "deck") then prepares the next song paused and muted.
- The deck has no audio focus, so it can't pause the main player. It has its own copy of the sound engine, so EQ and enhancer still apply.
- `DjMode.plan` picks the deck start so the incoming first beat lands exactly on an outgoing beat inside the window. If the intro is longer than half the window, the deck starts a whole number of beats before the first beat.
- The deck starts with a posted, timed play(). A phase lock keeps it on the grid: speed nudges of up to 3% while the deck is still quiet, then 1%.
- Volumes during the overlap: the outgoing song uses the existing equal-power fall, and the deck uses the equal-power rise.
- **Handoff:** 350 ms before the outgoing song ends, the main player (muted) seeks to the next song at the deck's position. It re-seeks once if it is more than 40 ms out of step, then takes over with a 200 ms equal-power swap. The deck is released immediately.
- That song skips its own crossfade fade-in, because the deck already faded it in. Queue, notification, play counting and history follow the main player as usual.
- **Every surprise falls back to the plain crossfade:** pause, seek, skip, a queue change, a deck not ready on time, beat drift over 400 ms, or DJ Mode turned off. The main player is never left muted (4 s handoff timeout).
- DJ Mode never arms with repeat-one, an active sleep timer, live radio, the same song next, or songs too short for crossfade (the existing 3x rule).
- Each decision goes to the playback log ("DJ: armed 124.0 → 120.0 BPM…", "DJ: plain crossfade (beyond ±6%)").
- **Stability:** I could not run the two-player overlap on a device here, so I can't prove it is stable on the 3 GB Android 10 phone. It is built so every failure path falls back to today's crossfade, never to silence or a stuck mute.
- If it proves unstable on the phone, the documented fallback (beat-aligned, tempo-matched starts with the existing crossfade, no deck) is a small change inside `Dj.tryArm`. It is NOT shipped as a fake.
- **Harmonic reorder is OUT of this part:** no key detection, and the queue is never reordered. Nobody should claim it.
- **Settings › Playback › Transitions › "DJ Mode"** (default OFF). Turning it on sets gapless OFF and makes sure there is a crossfade window: crossfade becomes 8 s if it was under 6 s, otherwise your length is kept.
- Turning it off only turns DJ Mode off. Gapless and crossfade stay as they are.
- If you turn gapless back on while DJ Mode is on, DJ Mode waits, and the row says "Waiting: needs crossfade on (gapless off)".
- With DJ Mode off, or with one track, nothing new runs: no analysis and no deck. The ticker's only change is that it multiplies by a gain that stays 1.0.
- **Indicator:** a small "DJ" chip shows while a beat-matched transition is armed or running. It appears above the lyrics capsule on the player and in the Up next header (both in Panes.kt). It is hidden otherwise.

## Job 2: Music recognition, "What's playing" (bundle item #15). Done, keyless.
- **Route verified first.** Shazam's public recognition endpoint (the one the open-source SongRec uses) works with NO account and NO API key.
- It was verified end to end from the Kotlin code here: our own `ShazamSignature` from 10 s of 16 kHz audio identified Billie Jean (Michael Jackson), Stayin' Alive (Bee Gees) and Blinding Lights (The Weeknd; it returned the Remix entry, which uses the same recording).
- So there is no key slot in Settings: it isn't needed, no key is shipped, and none is asked for.
- Caveat: the endpoint is unofficial. If Shazam changes it, the sheet says "Couldn't reach the recognition service" with Try again. It never pretends to listen.
- **Fingerprint** (`recognition/ShazamSignature.kt`, pure): a port of SongRec's fingerprinting (GPL-3.0, credited in the file), covering the FFT, peak spreading, peak picking and the binary format with CRC-32.
- Only the peaks are sent: no audio, no location. The request ids are random each time.
- **Entry:** a "What's playing" button next to the voice-search mic in Search (the only Search entry point; nothing elsewhere).
- Permission: the existing first-use flow. RECORD_AUDIO is asked only when the button is tapped. The manifest is unchanged (RECORD_AUDIO was already there).
- **Flow:** a sheet opens in a clear listening state with a level bar and Cancel. The mic records 16 kHz mono into memory only and tries to identify at 4, 8 and 12 s.
- The mic stops the moment a song is found, at 12 s, or on Cancel / closing the sheet (the job is cancelled). The buffer is zeroed, and nothing is written anywhere.
- The match is resolved by searching every enabled source in your priority order (`MusicSource.search(query, 5)`, in parallel, 6 s each), the AiMix pattern. `TrackMatcher` scoring picks the best (threshold 0.72; ISRC wins when both sides have one; ties within 0.02 go to the higher-priority source).
- The sheet shows the best match (art, title, artist, which source) with **Play**, which starts it in the player.
- Recognised but no playable copy: it says so, with "Search for it" and "Listen again". No match: "No match. Couldn't recognise this song." with Try again. Network failure: "Couldn't reach the recognition service" with Try again.
- **Privacy line on the sheet:** "The microphone is on only while this sheet is open. Nothing is recorded or saved."

## Files (one line each)
- logic/DjMode.kt (new): onset envelope, BPM + beat grid, head/tail combine, tempoMatch (±6%), parameters (pitch kept), beat-aligned plan.
- data/BpmStore.kt (new): BpmStore (per-track cache, unknown kept), BpmAnalyzer (MediaExtractor/MediaCodec two-window decode), Media3MediaDataSource.
- data/Settings.kt: `djMode` (default false). Nothing else.
- playback/PlaybackService.kt: Playback.djArmed / djStretch; the `Dj` controller (analysis, arming, deck, phase lock, handoff, fallbacks, tempo ease-back); the shared resolving/media-source factories; djGain in applyVolume; one ticker hook; release in onDestroy.
- playback/PlayerConnection.kt: applySpeed includes the DJ stretch; it doesn't re-apply while the service is easing the tempo.
- recognition/ShazamSignature.kt (new): fingerprint generator and encoder.
- recognition/SongRecognizer.kt (new): ShazamClient (request + answer parsing), RecognitionMatch (search + best-match pick), MicRecognizer (memory-only capture, 4/8/12 s attempts).
- ui/settings/SettingsPages.kt: the DJ Mode switch in Transitions.
- ui/screens/Search.kt: the "What's playing" button, its permission launcher, and WhatsPlayingSheet.
- ui/player/Panes.kt: DjChip, shown above the lyrics capsule (LyricsCapsule now wraps LyricsCapsuleBody) and in the Up next header.
- test/DjModeTest.kt (new, 12 tests): click tracks at 128 / 93.5 / 120 / 174 / 100.3 / 71 BPM within ±1 BPM; 126 BPM at 48 kHz plus the first-beat time; a 200 BPM pulse reads half time; noise, random clicks, silence and a too-short clip all give unknown (null); head/tail combine and the drift rule; ±6% cap with fallback at exactly 1.0; pitch kept; plan lands on the grid; long-intro whole-beat skip; store round trip incl. unknown.
- test/RecognitionTest.kt (new, 8 tests): answer parsing (match / no-match / junk); best-match pick across sources, priority tie-break, ISRC, no-match path; signature binary layout + CRC; silence gives no peaks. The `liveIdentify` test is skipped unless MUSICSPOT_SHAZAM_PCM / MUSICSPOT_SHAZAM_EXPECT are set.
- Not touched: AndroidManifest.xml (nothing missing), versionName/versionCode, every other file.

## Build and tests
- Toolchain: JDK 21, AGP 9.3.0 / Gradle 9.5.0, SDK platform 37.0, build-tools 36.0.0. This sandbox is aarch64, so aapt2 was supplied via `-Pandroid.aapt2FromMavenOverride` (command line only; no project file changed).
- Before (Part 1 source, same toolchain): compileDebugKotlin BUILD SUCCESSFUL. testDebugUnitTest: 290 run, 0 failures, 0 errors, 9 skipped (53 classes). This matches Part 1's own numbers.
- After: compileDebugKotlin BUILD SUCCESSFUL, no warnings in changed code. The one warning in SettingsPages.kt (LocalClipboardManager, line ~413) is old code.
- testDebugUnitTest after: 310 run, 0 failures, 0 errors, 10 skipped (55 classes).
- 20 new tests. The 10th skip is the gated live recognition test. No old test was edited, and nothing that passed before fails now.
- Live recognition check (run here with real song previews, not in CI): 3 of 3 identified correctly.
- Not run on a phone. Check on the 3 GB Android 10 device:
  - A DJ transition between two steady-tempo songs within 6% (watch for the DJ chip and the "DJ:" lines in the playback log).
  - A pair more than 6% apart (it should be a plain crossfade).
  - Pause or skip during the overlap.
  - "What's playing" next to a speaker.

# v1.05 — Pack 6 (Bade Khwab), Part 1 of 2: Android Auto browsing + multiple queues

versionName / versionCode unchanged (v1.05 / 35). Source only: no APK, no release. Built on the Pack 5 Part 2 source.

## Job 1: Android Auto browse tree. Done.
- The README's "MediaSession browsing" claim is now true. PlaybackService is a MediaLibraryService with a MediaLibrarySession and a full browse tree. README.md is not edited.
- Tree (your own library only, no live sources): root → Liked songs · Playlists (one node per playlist → its tracks) · History (newest first) · On this device (downloaded + fully cached, each song once).
- Empty sections and empty playlists are hidden, so every browsable node has playable songs under it.
- Media ids: `upze:root|liked|playlists|history|device`, `upze:pl/<enc id>`, `upze:play|<parent>|<enc track id>`. The app's own queue items keep the bare track id, so the two can't collide.
- Playing a browse song (onSetMediaItems) queues its parent's tracks and starts at the tapped song.
- A stale id (the song was removed from that list) fails the request cleanly. It never crashes and never plays the wrong thing.
- onAddMediaItems resolves a browse song to its real track. The app's own items take exactly the same path as before.
- Search (onSearch / onGetSearchResult): title, artist or album, max 50 results, each song once. A result plays inside the section it was found in.
- Children and search results are paged.
- Callbacks wait for Graph to be ready, because a head unit can start the service cold.
- The "recent" root (params.isRecent) answers NOT_SUPPORTED. Playback resumption stays the restored queue.
- Transport, the ticker, SafePositionPlayer/fader and the session activity are untouched. Only the session type and the callback object changed.

## Job 2: Multiple persistent queues. Done.
- Up to 10 named queues in `queues.json`. Each keeps its own tracks, index, position, shuffle and repeat. One queue is active.
- `read()/load()/save()` still mean the active queue, so the restore, the 5 s position save and Backup work unchanged.
- Migration: the first read turns an existing `queue.json` into the active "My queue", unchanged (tested).
- `queue.json` keeps being written with the active queue, so a downgrade still restores the queue you were on.
- queues.json writes are held until it has been read. Edits made before that are replayed, so the other queues are never overwritten (tested).
- Switching (PlaybackService.switchQueue, on the player thread): saves the current queue's full state first, including an empty queue so a cleared queue stays cleared. Then it marks the new queue active and loads its tracks, index, position, shuffle and repeat. Play/pause stays as it was.
- My choice for **New queue**: it starts empty and is NOT switched to. **Copy current** saves the current queue (songs, position, shuffle, repeat) as "Copy of <name>", also not switched to. This is separate from "Save queue as playlist", which is unchanged.
- Rename is inline. Delete asks once. You can't delete the last queue. Deleting the active queue switches to the next queue (or the previous one) first.
- UI: a "Queues" button in the Up next header opens the Queues dialog. The active queue is marked "Playing". No changes were needed in Overlays.kt, because the queue actions live in Panes.kt.
- Known limitation: switching to an empty queue stops playback and the player sheet closes. The next song you play starts filling that queue. To get back to another queue, play anything, then open Up next › Queues.
- Single-queue playback, tidy (1.0.7 smart queue) and autoplay are unchanged. With one queue, the app behaves as before.
- Backup now includes every queue (`queues`). `queue` stays the active one for older readers. Restore still doesn't touch queues (same as before).

## Files (one line each)
- playback/AutoBrowse.kt (new): pure tree, id encode/decode, resolve, search, paging.
- playback/PlaybackService.kt: MediaLibraryService + MediaLibrarySession callback (browse/search/onSetMediaItems); switchQueue/deleteQueue; saveQueue(force).
- playback/PlayerConnection.kt: queues flow, switchQueue, newQueue, copyCurrentQueue, renameQueue, deleteQueue.
- data/Library.kt: NamedQueue, QueueSet, QueueSets (pure rules), and QueueStore generalised (migration, 10-queue cap, legacy mirror).
- data/Backup.kt: optional `queues` field (all queues). FORMAT stays 1, and older files still decode.
- ui/player/Panes.kt: "Queues" button + QueuesDialog (switch, new, copy, rename, delete).
- AndroidManifest.xml: MediaLibraryService + MediaBrowserService actions (MediaSessionService kept); com.google.android.gms.car.application meta-data.
- res/xml/automotive_app_desc.xml (new): `<uses name="media"/>` only.
- test/AutoBrowseTest.kt (new, 8 tests); test/QueueStoreMultiTest.kt (new, 7 tests).

## Build and tests
- compileDebugKotlin: BUILD SUCCESSFUL.
- testDebugUnitTest before: 275 run, 0 failures, 0 errors, 9 skipped (51 classes).
- testDebugUnitTest after: 290 run, 0 failures, 0 errors, 9 skipped (53 classes).
- 15 new tests. No old test was edited, and nothing that passed before fails now.
- Not run on a phone or a head unit. Check Android Auto with the Desktop Head Unit (DHU) or in a car.

# Pack 5 (Nayi Jeet) Part 2: Widgets (2026-11-11), still v1.05 (versionCode 35, unchanged)

Corner bug, the real cause: `makeBackground()` painted one fixed 640x300 card (256x120 bitmap), and `w_bg` showed it with `centerCrop`, so the pre-rounded corners were cropped away on every real widget size (square on Android 10). Fix: one shared helper (`WidgetCard`) renders the card at each widget's ACTUAL size (from AppWidgetManager options: min/max width/height in dp to px, portrait = minW x maxH, landscape = maxW x minH). The bitmap keeps the widget's aspect (scaled so its longest side is at most 400 px for the binder limit), the radius is scaled with it (22 dp, clamped to half the short side), and every widget shows it with `fitXY`. `onAppWidgetOptionsChanged` re-renders on resize. No widget's outer layer is a centre-cropped or square image any more. The album-art thumbnail keeps its rounded-bitmap treatment.

## Files
- widget/WidgetCard.kt (new): the shared rounded-card renderer (per-pixel anti-aliased rounded rect, art-coloured diagonal gradient, 1 dp glass edge), widget-size reading, a small cache.
- widget/NowPlayingWidget.kt: the card is rendered per widget id at its real size via WidgetCard. Stores Palette colours instead of one fixed bitmap. Adds onAppWidgetOptionsChanged. One render path for both now-playing layouts. Every push refreshes the stats widget. Art thumbnail is 192 px (64 dp). Adds the thin `NowPlayingCompactWidget` receiver at the end of the file.
- widget/StatsWidget.kt (new): "This week" widget. `StatsCard.from` is a direct read of `Wrapped.summarize(WEEK)` (plays, current streak, top song) with no maths of its own. Empty state: "Play something — your week in music will appear here". Tap opens Your stats.
- widget/ShortcutsWidget.kt (new): 4x1 Search / Downloads / Shuffle / Resume.
- res/layout/widget_now_playing.xml: w_bg uses fitXY. Padding is 12/8 dp (was 14), art 64 dp (was 84), buttons 34/40/34 dp (was 40/48/40). Removed the small "UPZE" label to fit the lower height. Removed the useless clipToOutline.
- res/layout/widget_now_playing_compact.xml (new): 4x1 row with 40 dp art, title/artist, play/pause, next.
- res/layout/widget_stats.xml (new): stats card layout.
- res/layout/widget_shortcuts.xml (new): shortcuts layout.
- res/xml/widget_now_playing_info.xml: min 250x110dp changed to 250x92dp, minResize 220x100 changed to 220x84. Target 4x2 kept.
- res/xml/widget_now_playing_compact_info.xml (new): 250x40dp, target 4x1, horizontal resize.
- res/xml/widget_stats_info.xml (new): 180x92dp, target 3x2, resizable up to 320x180dp (4x2), updatePeriodMillis 1800000.
- res/xml/widget_shortcuts_info.xml (new): 250x40dp, target 4x1.
- res/drawable/widget_bg.xml: radius 28 changed to 22 dp to match WidgetCard (only seen before the first render and in the picker).
- res/drawable/widget_ic_search.xml, widget_ic_download.xml, widget_ic_shuffle.xml, widget_ic_top.xml (new): white vector icons.
- AndroidManifest.xml: three receivers (NowPlayingCompactWidget, StatsWidget, ShortcutsWidget). Nothing else changed.
- MainActivity.kt: no route extra existed, so added the smallest handling. `EXTRA_OPEN_ROUTE` ("open_route") with ROUTE_WRAPPED / SEARCH / DOWNLOADS / SHUFFLE / RESUME, read in onCreate (fresh start only) and onNewIntent, then removed from the intent. Shuffle plays Liked songs shuffled (else History) through `Graph.player.play(shuffle = true)`. Resume waits for PlaybackService's restored queue (up to 8 s), then plays. Both open the player. With nothing to play, a pill says so.
- ui/AppRoot.kt: two optional params (`openRoute`, `onOpenRouteConsumed`) and one LaunchedEffect. Wrapped goes to Home + Route.Wrapped, Search to the Search tab, Downloads to Library + Route.Downloads.
- test/WidgetCornerTest.kt (new, Robolectric sdk 29, 5 tests): wide 320x92dp, tall 160x220dp and 4x1 250x40dp cards have transparent corners, an opaque centre and filled edges, and keep the widget's aspect. Also tests the raw pixel buffer and reading the size from the options Bundle.
- test/StatsWidgetTest.kt (new, 2 tests): the widget's numbers equal Wrapped.summarize on fixture data (plus the fixture's literal values), and a new user gets the empty line.

## Build and tests (same toolchain as Part 1)
- Before (Part 1 output): compileDebugKotlin BUILD SUCCESSFUL. testDebugUnitTest: 268 tests, 0 failures, 0 errors, 9 skipped (49 classes).
- After: compileDebugKotlin BUILD SUCCESSFUL, no warnings in changed files. testDebugUnitTest: 275 tests, 0 failures, 0 errors, 9 skipped (51 classes). +7 new. No existing test edited. Nothing that passed before fails now.

## Notes
- Shortcuts: all four are wired, none dropped. Search and Downloads open those screens. Shuffle and Resume actually start playback (from a cold tap they open the app, wait for the stores and player, then play). Resume needs a saved queue. With none, it says "No previous session to resume yet".
- The stats card uses the default UpZe colours (it has no artwork). The now-playing cards use the artwork's colours.
- The corner tests use a per-pixel renderer, so they run under Robolectric's default (legacy) graphics and need no native graphics runtime.
- Widgets were compiled and unit-tested, NOT run on a device or launcher. Please check them on the Android 10 phone.
- Card bitmaps are rendered on the caller thread (~400x150 px, cached per size and colours).

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
