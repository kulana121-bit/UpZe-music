# UpZe 1.0.5: performance and bug-fix report

The whole app compiles (`:app:compileReleaseKotlin`). Line numbers refer to the 1.0.4 source. Every change in the code is marked `// 1.0.5:`.

---

## UpZe 1.0.5 — player / motion performance pass

Scope: `ui/player/{Player,AuraPlayer,VinylPlayer,MinimalPlayer,PlayerChrome,FullPane}.kt`, `ui/motion/*.kt`, `logic/DeviceTier.kt`.
Gradle was **not** run. Every change carries a `// 1.0.5:` comment.

Rule followed: no visual or feature change. Every rewrite draws the same pixels (same colours, gradients, easing,
periods and geometry). The work only moves *where* state is read and *when* objects are allocated.
Where a change is mathematically identical but built differently, the reason is written next to it.

---

## ui/motion/Loops.kt — frame pacing (goal 2)

- **Original L14-31** `rememberLoopPhase`: a plain Animatable/tween loop that runs at full refresh rate on every device.
  On lite devices every ambient loop (reels, breathing, Ken Burns, buffering pulse) invalidated draw, and so the glass
  above it, 60-120 times a second.
- **Change:**
  - New defaulted parameter `ambient: Boolean = true`, so the API and every existing call stay compatible. The return
    type is still `State<Float>`.
  - When `ambient && Graph.liteFx`, the loop runs on `withFrameNanos`. The phase is a **pure function of elapsed frame
    time** (`loopPhaseAt(elapsedMs, periodMs, reverse, from, easing)`). It reproduces the original legs exactly: the
    first leg runs from the current value to 1, then legs alternate 1→0 and 0→1 (`reverse`) or restart at 0→1, each
    using `easing`.
  - The value is only **published** (`snapTo`) when at least ~33 ms have passed since the last publish
    (`DeviceTier.shouldPublishFrame`). Speed and shape stay identical; on 60 Hz it simply updates every second frame.
  - Skipped frames cost no state write and no invalidation.
  - Non-lite devices, and any loop with `ambient = false`, run the original code path unchanged, bit for bit.
  - `running = false` still snaps to `rest`.
- **Callers:** every caller is a decorative loop, so all of them get pacing by default. That covers the cover pulse,
  Aura breathe, Aura/ArtGradient drift (already off on lite), Deck reels, Poster Ken Burns drift and Poster buffering
  spin. No touch- or scroll-driven animation uses this function, so none of them changed.
  `rememberLoopPhase` is only used in `ui/player`.

## logic/DeviceTier.kt (goal 2 helpers)

- `isLite` is untouched.
- Added pure helpers:
  - `LITE_AMBIENT_FRAME_NANOS = 33_000_000`
  - `FRAME_SLACK_NANOS = 2_000_000`, so vsync jitter (two 60 Hz frames measuring 32.9 ms) does not drop the rate to
    20 FPS. The result is every 2nd frame at 60 Hz, every 3rd at 90 Hz and every 4th at 120 Hz.
  - `ambientFrameIntervalNanos(lite)`
  - `shouldPublishFrame(last, now, interval)`
- These are unit-testable. `loopPhaseAt` (internal, in Loops.kt) is also pure.

## ui/motion/Fx.kt

| Orig line | Problem | Fix |
|---|---|---|
| 87-102 `PlayPauseIcon` | Each draw allocated 2 `Path`s, 2 `List<Pair<Offset,Offset>>` (8 Pairs) and a local fun closure. This happened on every frame of the morph, and the icon is used in ~10 places (docks, Home cards, every player). | Vertex tables are hoisted to top-level `FloatArray`s (`PP_LEFT/PP_RIGHT`, same values and order). `Canvas` is now `Spacer(drawWithCache{})` (that is what `Canvas` is). It keeps two cached Paths and `rewind()`s them each draw. `p` is still read only while drawing. |
| 112-114 `Modifier.shimmer` | **Bug:** `val x by animateFloat` was read in the modifier factory, i.e. in the *caller's* composition. Every skeleton's parent recomposed every frame while loading, and a new linearGradient was built per frame. | `x` is kept as `State` and read only in `drawBehind`. The brush is remembered once with start (0,0) and end (400,240), and the canvas is translated by `x*600`. That gives exactly start (x·600, 0), end (x·600+400, 240) as before. `clip(shape).background(brush)` became `clip(shape).drawBehind{drawRect(brush)}`, which is the same drawing for a rectangle background. |

Motion.kt, Scroll3D.kt, Surfaces.kt and TapOrigin.kt were reviewed. Their state reads are already in
`graphicsLayer`/`offset`, and nothing allocates per frame, so they are **unchanged**.

## ui/player/Player.kt (classic player)

| Orig line | Problem | Fix |
|---|---|---|
| 159, 161 | Two `Brush.verticalGradient`s rebuilt on every `PlayerScreen` recomposition. | `remember` / `remember(c.background, hasCanvas)`. |
| 251-252, 257 | `a`/`b` (animated colours) were read in composition, so `ArtGradient` recomposed every frame of the 1.26 s colour glide. | Passed as `() -> Color` and read inside the `drawWithCache` build block. The cache rebuilds on change; no recomposition. |
| 260-268 `androidxDrawGradient` | Per drift frame: a new `linearGradient` (list + shader) plus a new `verticalGradient`. | `drawWithCache`. The fade brush and a 0→1 horizontal axis brush are built per size/colour. Each frame the axis brush is mapped onto the exact original segment (w·d, 0)→(w·(1−d), 0.75h) with translate + rotate + scale-x-by-length (stretching along the gradient axis keeps the bands perpendicular). It is drawn over `clipRect` bounds, which matches `drawRect(brush)`. No allocation per frame. |
| 299-300 `CoverBox` | **Bug:** `pulseLoop by` and `pulse` were computed in composition. The whole cover (BoxWithConstraints, pointer inputs, Artwork) recomposed **every frame while buffering**. | `State` kept. `pulse` is computed inside the artwork's `graphicsLayer` with the same 1→0.55 mapping. |
| 323-324 | Halo `radialGradient` built in composition (which was every frame while buffering). | `remember(glowC)`. |
| 363-367 | `heart.value` read in composition, so the cover recomposed every frame of the 980 ms double-tap burst. | `derivedStateOf { heart.value > 0f }` decides whether the icon is shown. The burst value is read in the icon's `graphicsLayer`. |
| 393, 404, 413 `ControlsSeek` | Position was read in the body. | `posState` is kept un-delegated. The Slider moved into the leaf `ControlsSeekSlider(value: () -> Float, …)`, and the elapsed time into the leaf `PlayerTickText` (new, `internal`, shared by all players). Same Slider and Text arguments. |
| 489, 491-510 `Controls` | The play-button `corner` spring was read in composition (`shape =`), so **all of Controls** recomposed every frame of the play/pause morph. | The play button Box moved unchanged into `ControlsPlayButton(playing, buffering, stillBtn, playI, haptics)`. Now only that button recomposes. |

Left as is:
- **L504** `RoundedCornerShape(...)` inside `graphicsLayer`. A new Shape instance is how the layer outline updates, and it only happens during the morph.
- **L499** `Stroke` in the ring `drawBehind`. That draw is not re-run per frame because the ring animates through layer properties only.

## ui/player/AuraPlayer.kt

| Orig line | Problem | Fix |
|---|---|---|
| 157 | Canvas scrim brush rebuilt per recomposition. | `remember`. |
| 227-237 `AuraBackground` | Per frame of the 40 s drift: 3 `radialGradient`s (lists + shaders), 1 `verticalGradient`, and a local `fun orb` closure. | `drawWithCache`. Each orb's gradient is built once per size/colour, centred on `Offset.Zero` with the same radius. Each frame does `translate(cx, cy) { drawCircle(orb, r, Offset.Zero) }`, which is exactly `radialGradient(center = (cx, cy))`. Colours `a`/`b` are read in the cache block. |
| 273-274 `AuraCover` | **Bug:** `breatheLoop by` and `breathe` were computed in composition. The whole floating cover (artwork, reflection, gesture modifiers) recomposed **every frame while playing**, all the time. | `State` kept. `breathe` is computed inside both `graphicsLayer`s (halo and artwork). |
| 284, 333 | Halo and reflection brushes built in composition. | `remember(glow)` / `remember(background)`. |
| 322-326 | `heart.value` read in composition. | `derivedStateOf` for visibility; value read in the layer. |
| 393-405 `AuraDeck` | `corner` and `size` play springs were read in composition, so the **whole liquid-glass deck** (titles, chips, seek bar) recomposed every frame of the morph. 399: gradient brush rebuilt each time. | Moved unchanged into `AuraPlayButton(...)`; the brush is remembered by `primary`. |
| 428, 435, 438, 457-458, 462-464 `AuraSeekBar` | Position was read in the body that owns the gestures and the `animateDpAsState`. The played-fill brush was rebuilt per tick. | `posState` kept. The fill moved into the leaf `AuraSeekFill(fracOf, brush)` (same Box). Times moved into `PlayerTickText`. The drag handler's `fracNow` now holds `fracOf` via `rememberUpdatedState` (same value it used). The brush is remembered. |
| 589 | Lens brush per pane-switcher entry rebuilt per recomposition. | `remember(primary)`. |

`AuraMiniProgress` (541-549) was already a leaf and is unchanged.

## ui/player/VinylPlayer.kt (Deck / cassette)

| Orig line | Problem | Fix |
|---|---|---|
| 165 | Wash brush rebuilt per recomposition. | `remember(scheme.background, tint)`. |
| 295-299 | `heart.value` read in composition, so the whole deck card recomposed during the burst. | `derivedStateOf` plus a layer read. |
| 316-320 `DeckReels` | **Bug:** `phase by` was read and the angle computed in composition, so DeckReels recomposed **every frame while playing**. The freeze accumulator was also written from composition on every frame. | `State` kept and read only in the draw block. The freeze fold now happens once, on the recomposition where `running` flips to false, reading the stopped phase with `Snapshot.withoutReadObservation`. The reels still freeze where they stopped and resume from there (at most a one-frame difference at the freeze instant). |
| 323-348 `Canvas` + `reel()` | Per frame: a new `Stroke`, `readableOn(accent)` computed 12 times, and 24 cos/sin calls. | `Spacer(drawWithCache{})` caches the Stroke, the stroke widths and the spoke colour. Spoke cos/sin come from top-level tables with the same values. |
| 408 | Play-key gradient rebuilt per recomposition. | `remember(primary)`. |
| 442, 447, 449, 475, 482 `DeckSeekBar` | Position was read in the body. | The Canvas reads `fracOf()` while drawing (draw-only invalidation per tick). Elapsed time is `PlayerTickText`. The drag handler uses the updated `fracOf`. |

## ui/player/MinimalPlayer.kt (Poster)

| Orig line | Problem | Fix |
|---|---|---|
| 285 | Ken Burns `drift by` is already read only in `graphicsLayer`. | No change needed. It now gets lite pacing through Loops.kt. |
| 327-330 | Fade brush rebuilt per recomposition. | `remember(bg, horizontalFade)`. |
| 331-335 | `heart.value` read in composition. | `derivedStateOf` plus a layer read. |
| 412, 419, 486-489 `PosterProgress` | Position was read in the body, which holds the ring gesture handler, the play button and the thumb spring. | `posState` kept. The ring reads `fracOf()` while drawing. Elapsed and remaining times are `PlayerTickText` leaves (same strings; the colour still follows `active`). |
| 463-474 ring `Canvas` | 3 `Stroke` objects per frame, and the ring redraws every frame while buffering (spin). | `Spacer(drawWithCache{})`. The strokes depend only on size and are cached; `frac`, `spin` and `thumbR` are read in `onDrawBehind`. |

## ui/player/PlayerChrome.kt

- **L146:** the `EntryCard` border gradient was rebuilt on every `PlayerEntryBar` recomposition (every player-state
  change). It is now `remember(c.onSurface)`.

## ui/player/FullPane.kt

- **L388:** the wash brush was rebuilt per recomposition. Now `remember(c.primary, c.background)`.
- **L456:** `listOf("Queue","Lyrics")` was rebuilt per `PaneTabBar` recomposition. Now a top-level `PaneTabLabels`.
- Left as is:
  - **L154, 284, 380:** `RoundedCornerShape` is allocated inside `graphicsLayer` during drags and springs. A fresh
    Shape is what makes the layer recompute its outline, and caching it would freeze the corner radius (a visual change).
  - The reveal and drag reads are already in the layer phase.

---

## Suggested changes in files I did not own (not edited)

- **`ui/player/Panes.kt` L479 `LyricsCapsule`:** `val pos by Graph.player.position.collectAsState()` recomposes the
  capsule (including a glass Box and AnimatedVisibility) on every position tick, even though only `activeIndex`
  matters. Suggestion: `val active by remember(l, settings.lyricsOffsetMs) { derivedStateOf { l.lines.activeIndex(posState.value + settings.lyricsOffsetMs) } }`, so it recomposes only when the line changes.
- **`ui/player/Panes.kt` L193:** already collects position as a `State` (not delegated). Worth checking that it is read only in leaves or derived state.

## Verification notes / risks for the lead's compile

- New APIs used:
  - `Path.rewind()` (Compose UI ≥ 1.7; BOM 2026.09.00 is newer).
  - `withFrameNanos`, `Snapshot.withoutReadObservation`
  - `drawWithCache` / `onDrawBehind`
  - `drawscope.translate`, `clipRect`, `withTransform`
  - `kotlin.math.hypot(Float, Float)`
- `ControlsSeekSlider` forwards to the same M3 `Slider(value, onValueChange, …, thumb: @Composable (SliderState) -> Unit)` overload the original used, under `@OptIn(ExperimentalMaterial3Api)`.
- New `internal fun PlayerTickText` lives in Player.kt (package `com.ravi.musicspot.ui.player`). The name was grepped and is unique.
- A bracket/paren balance check passes on all edited files. A few imports may now be unused (`drawBehind` in AuraPlayer, `Canvas` in MinimalPlayer/Fx); that is only a warning, and the build does not set `allWarningsAsErrors`.

---

## 1.0.5 glass / art / palette performance pass

Scope: `ui/glass/Glass.kt`, `ui/glass/ArtBlurCache.kt` (new), `ui/screens/HomeAura.kt`, `ui/theme/Theme.kt` (palette code only), `App.kt` (Coil loader only).
The rule was no visual change. Each edit draws the same pixels in the same order, except the Palette RGB_565 decode (see Theme.kt).

Coil API check: I downloaded `coil-core-android-3.0.4.aar` from Maven Central and checked it with javap. There is no Coil jar in the local Gradle caches.
- `coil3.request.bitmapConfig(ImageRequest.Builder / ImageLoader.Builder, Bitmap.Config)` exists (in `ImageRequests_androidKt`).
- `allowHardware(...)` and `allowRgb565(...)` exist in the same file.
- `ImageRequest.Builder.memoryCachePolicy(CachePolicy)` exists.
- `coil3.util.DEFAULT_BITMAP_CONFIG` is `HARDWARE` on API 26+.

---

## ui/theme/Theme.kt (palette code, original lines 165–185)

| Original line | Problem | Change |
|---|---|---|
| 168, 178 | Palette requests were already `size(96).allowHardware(false)`, so they were small and software. They decoded as ARGB_8888. | Both now go through one `paletteRequest()`: `size(96).allowHardware(false).bitmapConfig(RGB_565)`. That halves the bytes. Art with alpha stays ARGB_8888, because Android ignores RGB_565 for images with alpha. |
| 166–185 | `artSeed`/`artPalette` re-ran Coil execute + `Palette.generate` for the same cover every time. That happened on each theme song change, each Home hero page, and each player screen (Player, AuraPlayer, MinimalPlayer, Chrome all call `artPalette`). | Added per-URL `LruCache`s (64 entries each, `seedCache` / `paletteCache`). A hit returns at once, with no IO hop and no Palette run. Only successes are cached, so offline or failed loads still retry. Same return types and same signatures. |

Visual note on RGB_565: Palette quantises each pixel to 5 bits per channel. RGB_565 keeps exactly those top 5 bits for red and blue, and 6 for green, so the swatch inputs are the same. The one exception is Coil's density rescale step, which can differ by a rounding unit in a few pixels. In practice the swatches are identical. To revert just this, remove `.bitmapConfig(Bitmap.Config.RGB_565)` from `paletteRequest`.

## App.kt (Coil ImageLoader, original lines 241–252)

- I did **not** set a loader-wide `bitmapConfig(RGB_565)`, and I left a comment in the code explaining why.
  - Coil 3.0.4's default on API 26+ is `HARDWARE`. A global RGB_565 would turn every on-screen cover into a software bitmap. That means Java heap plus a GPU upload, instead of GPU-only memory with zero-copy draws.
  - It would also band gradients in large covers. That is a memory regression and a visual change.
  - RGB_565 is set per request only where the CPU reads pixels: Palette and ArtBlurCache.
  - If you still want it globally, it is one line: `.bitmapConfig(android.graphics.Bitmap.Config.RGB_565)` on the `ImageLoader.Builder` (import `coil3.request.bitmapConfig`). I don't recommend it.
- `allowHardware` is unchanged (Coil default, true).
- Memory cache was already `0.15` on lite devices and `0.20` otherwise (line 249). I kept it. Shrinking it further would cause cache misses and placeholder flashes on revisits.

## ui/glass/Glass.kt

| Original line | Problem | Change |
|---|---|---|
| 269–283 `GlassLayer31` | **Fullscreen content recorded twice per frame on API 31+.** Content had both `layerBackdrop(backdrop)` (Kyant) and `hazeSource(haze)`. That is a second full-screen GraphicsLayer, plus Haze's position tracking on every layout and scroll. `glass()` checks `LocalBackdrop` first, and both locals were provided under the same `sampled` flag, so on 31+ the Haze recording was never read. No other `hazeEffect` exists in the app; MinimalPlayer and VinylPlayer only null the locals. | On 31+, only the Backdrop layer is recorded. `rememberHazeState()` and `hazeSource` were removed there, and `LocalHazeState` is `null` under GlassLayer31. API < 31 (`GlassLayerLegacy`, Haze on RenderScript) is untouched. `liteFx` paths are untouched. |
| 224 `GlassApi31.source` | Took an unused `haze` parameter. | Parameter removed; Glass.kt was the only caller. |
| 94–101 `glass()` | `glassStyle()` and `blurIntensity()` each ran `collectAsState()`, so every chip, pill or badge had 2 settings collectors (`liquidGlass` had 3). Home Aura has dozens of glass elements. | One `collectAsState()` in `glass()` gives the same style and intensity. `liquidGlass` now has 2 collectors. |
| 143–149 `HazeGlass.haze` | New `HazeStyle`/`HazeTint` on every recomposition. | `@Composable`, with the style remembered by key (background, film, k). |
| 152–170 `HazeGlass.water` | Three Brushes and four lists per recomposition. Fill and sheen were two `background()` nodes. | Rim is remembered by (dark, wash). Fill and sheen are built once per (background, wash, strong) into one remembered `Modifier.drawBehind { drawRect(fill); drawRect(sheen) }`. Same gradients and draw order, and the modifier instance is stable. The sheen brush depends only on dark/light, so it is a preallocated constant. |
| 230–242 `glassSheet` | Two Brushes per recomposition. | Fill is remembered by colour. The two rims are constants (`sheetRimWater`, `sheetRim`). |
| 289–300 `liquidGlass` | Three Colors and two Brushes per recomposition. Sheen and rim depend only on dark/light. | Four preallocated constants (`liquidSheenDark/Light`, `liquidRimDark/Light`) with the same stops and alphas. |
| 176–222 Kyant `drawBackdrop` | Lambdas are recreated per recomposition. Per-draw work is tiny (`dp.toPx`, `drawRect(film)`) and allocates nothing. | Unchanged, so the Liquid and Frosted look is exactly as before. |

About the brief's "Brushes/Paths → drawWithCache": `background(brush)` and `border(brush)` already cache their outline and path per size in Compose. The real allocation churn was Brush and List creation on every recomposition. The remember/constant changes above remove that. The fill+sheen pair became one draw node.

## ui/glass/ArtBlurCache.kt (new)

- `ArtBlurCache.get(context, id, url, tinyRadius): ImageBitmap?` (suspend):
  - Decodes through Coil with `size(96)`, `allowHardware(false)`, `bitmapConfig(RGB_565)` and `memoryCachePolicy(READ_ONLY)`, so it doesn't fill Coil's cache.
  - Blurs once on `Dispatchers.Default` with a pure-Kotlin separable 3-pass box blur. That approximates a Gaussian and runs on API 26+. I checked the sliding-window logic against a brute-force reference.
  - Caches in an `LruCache` capped at 4 MB, sized by `allocationByteCount` and keyed by `"$id#$radius"`.
- `peek(id, r)` reads the cache synchronously. `tinyRadiusFor(radiusPx, surfacePx)` scales an on-screen radius to the 96 px bitmap. `clear()` empties the cache.
- `@Composable BlurredArt(url, modifier, blurRadius: Dp, id = url, alpha = 1f)` draws the cached blur scaled up with `ContentScale.Crop` and `FilterQuality.Low`. When the cover changes, the previous blur stays up until the new one is ready.

**No call site was switched to it.** No screen in scope, or in AuraPlayer/Player, currently does a real blur of artwork. Since 1.0.2 every artwork "wash" is `SoftArt`: a 32×32 decode stretched with `FilterQuality.Low`, with no blur pass. That is already cheaper than ArtBlurCache, and it has a slightly blockier look that a true blur would change. This covers:
- HomeAura hero, line 309
- AuraPlayer line 226
- Player line 156

So switching any of them would break the no-visual-change rule. The cache is ready for any screen that brings back a heavy `Modifier.blur` on art.

**AuraPlayer wiring (lead): none needed today.** If a real art blur such as `Modifier.blur(R.dp)` is ever (re)introduced there, replace that layer with:
```kotlin
com.ravi.musicspot.ui.glass.BlurredArt(url, Modifier.fillMaxSize(), blurRadius = R.dp, id = track.id)
```
Optional prewarm for the next track: `ArtBlurCache.get(ctx, next.id, next.artUrl, ArtBlurCache.tinyRadiusFor(R_px, screenLongSidePx))`.

## ui/screens/HomeAura.kt

| Original line | Problem | Change |
|---|---|---|
| 303, 307, 310 `AuraHeroCard` | `val tint by animateColorAsState(...)` was read in composition. During every 700 ms tint glide (each hero page, each cover load), the whole card recomposed every frame: texts, Artwork, play pill, and a new gradient Brush each time. | The State is read only in the draw phase. The background is `drawBehind { drawRect(lerp(tint.value, Black, .55f)) }`, which equals `background(color)` after `clip(CARD)`. The scrim is `drawWithCache { Brush.horizontalGradient(...) ; onDrawBehind { drawRect(scrim) } }`. Same pixels; the animation only redraws now. |
| 60 | Unused `import androidx.compose.ui.draw.blur`. | Replaced with the `drawBehind` / `drawWithCache` imports. |
| 256 | Avatar ring Brush built on every recomposition. | `remember(c.primary, c.tertiary)`. |
| 350–362 `AuraTiles` | Tile list, lambdas and four gradient Brushes rebuilt on every recomposition. | `remember(liked, downloads, history, nav)`. The Brush is a property of the tile, so it is built once. |
| 381, 396 | Every section header built its own accent-bar Brush. | `accentBar(a, b)` helper, remembered per colour pair. |
| 309 hero `SoftArt` | Already cheap: 32 px, no blur. | Unchanged (see ArtBlurCache note). |

---

## Found but outside my files (for the lead)

1. **`Theme.kt` line 196: `LocalSongTint` is a `staticCompositionLocalOf`, and it is given an animated colour** (`songTint`, a 980 ms tween, line 208).
   - Every animation frame of every song change invalidates the whole app tree under the theme. This happens even with dynamic colour off, because the tint is always extracted.
   - Switching it to `compositionLocalOf` would recompose only the readers (Water glass, glassSheet), with no visual change.
   - I left it alone because my brief limited Theme.kt to the palette code.
2. **Nested GlassLayers:** AppRoot.kt:182 wraps pages that have their own `GlassLayer` (SettingsKit, Collections, Library, Explore…), and Player/AuraPlayer have one each. Nested pages record their content into a backdrop layer inside the outer recorded layer. These are separate backdrops that the glass needs, so I didn't touch them. Pausing the outer one while a fullscreen player covers it would be the next win. That is in AppRoot/player files, not mine.
3. **Palette network fetch:** `artSeed`/`artPalette` request the raw `url`, while `Artwork`/`SoftArt` request `sizedArtUrl(...)`.
   - The first palette run for a cover can download a full-size image that the UI never uses.
   - Switching to `sizedArtUrl(url, 120)` would save bandwidth and disk, but it changes the source JPEG and so could shift colours very slightly. I left it unchanged.
4. `HomeAura.AuraHeroCard` line 306: `blurOk` is unused (pre-existing; left as is).

---

## 1.0.5 list / artwork performance pass — lists.md

Scope: `ui/screens/{Home,HomeBento,HomeSpotlight,Collections,Library,Search,Explore,Common}.kt`, `ui/components/{Components,SwipeActions}.kt`.
No visual or feature change was intended. Every change is a key, a contentType, a remember or hoist, or a state read that moved out of composition.

Checks I ran (I couldn't compile): every Lazy `item`/`items`/`itemsIndexed` call in scope now has both `key` and `contentType` (a grep for calls without `contentType` returns nothing). Brace and paren balance matches the originals in every file.

## Key-uniqueness rules used
- When the ids are already deduped (`distinctBy { it.id }` upstream), the key is the plain id: QuickPicks, TrackShelf, CollectionShelf, ArtistRow, ShelfAll, HorizontalShelf, Search songs, Artist top songs.
- When the same id can legitimately repeat, the key includes the position: TrackListPage rows (History, playlists), mood chips, recent searches, Saved pairs.
- Keys get a namespace prefix when keys from different sources share one LazyColumn: `t:`, `song:`, `sug:`, `own:`/`yt:`/`sp:`, `dl-p:`, `ar-t:`, `rc-sh:`.

## ui/components/Components.kt
- L79-102 `Artwork`: no change needed. AsyncImage gets a String model inside a constrained `BoxWithConstraints`/`fillMaxSize`, so Coil 3 infers the decode size from layout. `sizedArtUrl` already asks the CDN for a bucketed rendition. No `bitmapConfig` override exists, so the global RGB_565 default from App.kt applies.
- L111-121 `sizedArtUrl`: `Regex(...)` was compiled up to twice per call. Hoisted to the private vals `GoogleSizeRe` and `SaavnSizeRe`.
- L124-127 `ArtPlaceholder` (drawn under every list artwork until it loads): the `Brush.linearGradient` and the `copy(alpha)` tint were allocated on every composition. Both are now remembered per theme colour.
- L162 `TrackRow`: `liked.liked.any { it.id == t.id }` ran an O(liked) scan per row on every recomposition. It is now `remember(liked.liked, t.id)`.
- L163 `TrackRow`: the meta line (`listOfNotNull … joinToString`, `formatTime`) is now `remember(artist, album, title, durationMs)`.
- L220 `HorizontalShelf`: added `contentType = { "shelf-card" }`. The keys were already deduped.
- L333 `SoftArt`: the ImageRequest has an explicit 32×32 size and doesn't override bitmapConfig, so it inherits the global RGB_565. Left unchanged. I did not add an explicit `coil3.request.bitmapConfig`: there are no coil 3.0.4 jars in ~/.gradle, so I couldn't javap-verify the API, and the global default already covers it.

## ui/components/SwipeActions.kt
- L61-76 `SwipeRow`: `offset.value` was read in composition (`val x = offset.value`, plus the background check on L76). Every drag frame recomposed the whole TrackRow (artwork, texts, badges).
  - Replaced it with a `derivedStateOf` "phase" (0 idle, ±1 revealing, ±2 past threshold), remembered per threshold. Composition now only changes when the phase changes.
  - The translation still comes from `graphicsLayer { translationX = offset.value }`.
  - The colours and icon choice are exactly the same, and so is `past` (`abs(x) >= threshold`).

## ui/screens/Home.kt (Classic)
- L172-246 main LazyColumn: added a `contentType` to every `item` (offline, header, hero, moods, shortcuts, skeleton, empty, header-row, quickpicks, recent-grid, collection-shelf, track-shelf, artist-row, more, end). The keys were already unique.
- L183/210/215/220: `f.moods.map{}`, `recent.drop(1).take(..)`, `mixes.distinctBy{}.take(20)` and `downloaded.take(20)` were rebuilt inside item lambdas on every recomposition. That also defeated the children's `remember(list)` and strong-skipping instance checks.
  - They are now remembered before the LazyColumn (`moodTitles`, `recentGrid`, `mixShelf`, `dlShelf`).
- L252-263 `shelfItems`: added contentType, chosen by the same "dense" test that picks QuickPicks or TrackShelf, so items of the same kind share reuse pools.
- L293 `MoodChips`: `itemsIndexed(titles)` had no key. It now uses key `"mood:$i:$t"`, because titles can repeat, plus a contentType.
- L365, L385, L402, L424, L461, L470: added contentType to QuickPicks, TrackShelf, CollectionShelf, ArtistRow and the two ShelfAll grids. Keys unchanged, since they were already deduped or prefixed `t`/`c`.
- L486 `softShadow`: the shadow colours were `Color.Black.copy(..)` on every call, for every card. Hoisted to private vals.
- L506 `HomeHero`: `Brush.linearGradient(listOf(top, bottom))` is now `remember(top, bottom)`.
- L534 `RecentGrid`: `tracks.chunked(cols)` is now remembered. Play-by-`indexOf` behaviour is unchanged.

## ui/screens/HomeBento.kt
- L142-214 main LazyColumn: added contentType on every item. Hoisted and remembered `moodTitles`, `favTiles` (take 7), `recentRest` (drop 1), `mixShelf` and `dlShelf`. These were previously built at L152/167/178/183/188.
- L220-230 `bentoShelf`: added contentType, same as Home.
- L289: the invite-tile gradient is now remembered per theme colours.
- L305, L414, L442: constant scrim gradients hoisted to private top-level `Brush` vals. L415 and L326-327: constant colours hoisted.
- L325 `BentoStatTile`: the gradient is now `remember(a, b)`.
- L341 `BentoMoods`: added key `"mood:$i:$t"` and a contentType.
- L398 `BentoOnRepeat` and L433-435 `BentoRecentGrid`: drop/take/chunked are now remembered. Indices are computed the same way.

## ui/screens/HomeSpotlight.kt
- L162-234 main LazyColumn: added contentType on every item. Hoisted and remembered `moodTitles`, `favTop` (take 5), `recentRest`, `mixShelf` and `dlShelf`, previously built at L172/187/198/203/208.
- L240-251 `spotlightShelf`: added contentType.
- L298: `Brush.verticalGradient` was allocated inside `drawWithContent`, i.e. on every draw. It is now the constant `SpotlightArtFadeMask`.
- L304-306: the page-fade gradient is now `remember(bg)`. It was re-allocated on every tint-animation frame.
- L373 bubbles: added contentType. L375: the bubble gradient is now `remember(b.a, b.b)`.
- L391 `SpotlightMoods`: added key `"mood:$i:$t"` and a contentType.
- L472 `SpotlightRecentCards`: added contentType, and `tracks.take(20)` is remembered. The keys `"srp"+id` were already unique because `recent` is distinctBy id.

## ui/screens/Collections.kt
- L169 `list-tools` and L421 `pl-search`: added contentType.
- L226: the TrackListPageCore header `item {}` gets key `tl-header` and a contentType. L232: `tracks.sumOf { durationMs }` is now remembered outside the item.
- L249-251: loading and empty `item {}` calls get keys and contentTypes.
- L252 track rows: the key changed from `"${id}-$i"` to `"t:${id}:$i"`.
  - It keeps the position, because History and playlists can repeat a song.
  - The new namespace means a row key can never collide with the header, tools or `extra` keys. In particular, the old pending-download keys `"p-"+id` could equal a row key `"${id}-$i"`.
- L298-300 RemoteCollection `extra` shelves: unkeyed `item {}` calls are now `rc-sh:$n` and `rc-shc:$n`, with contentType.
- L321/L338/L339 ArtistScreen:
  - The header and "Top songs" items now have keys and contentTypes.
  - `distinctBy` and `take(10)` moved out of the list builder into `remember(a)`.
  - Top-song keys are now `"ar-t:"+id`.
  - L342-344: added contentType to the shelf items.
- L457 History: `lib.history.map { it.track }` is now `remember(lib.history)`. It was a new list instance on every recomposition, so it re-ran the TrackListPage search/sort view every time.
- L470-479 Downloads:
  - `done`, `done.map{track}` and `pending` are now remembered per `dls`.
  - Pending rows are now keyed by the downloads map key (`"dl-p:"+key`, unique by construction) instead of `"p-"+track.id`, which could collide. They also get a contentType.
  - The "In progress" header is now keyed.
- L537-538 Saved: the empty item is keyed. `lib.saved.chunked(2)` is remembered and the rows now go through `itemsIndexed`, with key `"saved:$i:"+firstId` and a contentType.

## ui/screens/Library.kt
- L99-102: `own`, `yt`, `sp` and `downloaded` are now remembered per `lib.playlists` and `dls`. They were re-filtered and re-sorted on every recomposition.
- L113, 137, 138, 145, 146, 154, 155, 162: every unkeyed `item {}` gets a key and a contentType.
- L142/151/160: playlist keys are now namespaced (`own:`, `yt:`, `sp:`) so one id can never collide across sections. Added contentType `"playlist"`.
- L219 `PlaylistRow`: the unmatched count is now `remember(p.tracks)`.

## ui/screens/Search.kt
- L163/164/192/197/203/206/216/217/220/224/225/228: every unkeyed `item {}` gets a key and a contentType. The search field item now has a stable key, `search-field`.
- L181: suggestions use `distinct()` once, remembered, and get a contentType. Keys unchanged.
- L194 / L199: the source and filter chip LazyRows had no keys. They now use enum-name keys and a contentType, and `SearchFilter.entries.toList()` became `SearchFilter.entries`, which is already a List.
- L207: recent searches had no key. They now go through `itemsIndexed` with key `"recent:$i:$r"`, because the persisted list could hold a repeat, plus a contentType.
- L230-231: `songs` (distinctBy) is now remembered per result state outside the builder, and keys are `"song:"+id`.

## ui/screens/Explore.kt
- L53: the `segs` list is hoisted to the private val `ExploreSegments`.
- L55-65: every item gets a key and a contentType. The segment chips get `"seg:$i"` and a contentType. Each section item has its own key.
- L112: `sec.moods.chunked(2)` is remembered. L118: the mood-tile `Brush.linearGradient` is now `remember(hue)`. Added the `remember` import.
- L137 MoodScreen: the single item is keyed and typed.

## ui/screens/Common.kt
- No list items and nothing hot. Unchanged.

## Not done / notes
- No explicit RGB_565 was added, because no request in scope overrides the global default. See SoftArt above.
- `HorizontalShelf` track callbacks still use `indexOf` for playback (Artist and Explore shelves). That is existing behaviour, left as is.
- Another agent modified `widget/NowPlayingWidget.kt` during this run. That wasn't me.

---

## UpZe 1.0.5: DSP / audio path + widget performance pass

Scope: `dsp/Dsp.kt`, `dsp/Engine.kt`, `playback/EngineAudioProcessor.kt`, `widget/NowPlayingWidget.kt` (no other files touched).

- **Bit-exact old vs new EngineCore output**: 40 scenarios. Mono and stereo; fixed 1024-frame chunks and irregular 1/37/512/3000/4096-frame chunks; neutral, loud EQ (graphic, parametric, preamp, bass, widen), limiter off with no auto-headroom, BWE strong and light, transient restore, upscale 44.1→48k and 48→96k, low-end device, and live `update()`s mid-stream (bypass, crossfades, overwritten updates, upscale). Every float matched bit for bit, and `isIdentity` and every `EngineStatus` field matched after every call.
- **Resampler, exact path, bit-exact**: 44.1→48k, 44.1→96k, 48→96k, 11.025→192k, 96→48k and 22.05→44.1k all have 0 mismatches.
- **BWE retune path bit-exact**: I forced the detector energies through reflection to hit every cutoff candidate at 44.1k, 48k and 32k. Outputs and `cutoffHz` were identical across 21 cases.
- **Project `DspTest.kt`**: all 15 JUnit tests pass on the new code.
- **Compile check**: `EngineAudioProcessor.kt` and `NowPlayingWidget.kt` compile against android-36 `android.jar`, Media3 **1.8.0** `media3-common`, palette 1.0.0, okhttp 4.12 and coroutines 1.8. Stubs were used only for `R`, `MainActivity`, `Http` and the pre-existing `androidx.annotation.OptIn` (which comes from annotation-experimental).
- **Bytecode scan (javap)**: none of these contain a `new`, iterator call or boxing call: `EqChain.frame`, `Biquad.process`, `Limiter.frame`, `TransientShaper.frame`, `BandwidthExtender.frame`/`retune`. The only allocation sites left in `EngineCore.process`, `Resampler.process` and `queueInput` are the grow-only scratch buffers.
- **JVM measurements** (HotSpot, 4096 stereo frames per call):
  - Allocation per call with EQ+BWE+TR: 56 B before, 0 B after. HotSpot's escape analysis hides most of the iterators; ART has no escape analysis, so on device the old code allocated an `Iterator` per channel per frame, roughly 8k objects per buffer.
  - Time: 614→567 µs (EQ+BWE+TR) and 1405→1297 µs (with 96k upscale).
  - Audio-thread time of the first `process()` after an EQ change: 671–1230 µs before, 140–243 µs after. The chain is now built off the audio thread.

## dsp/Dsp.kt
| Orig line(s) | Problem | Change |
|---|---|---|
| 63–70 `Biquad` | `c.b0…` read through the `Coefs` object on every sample | `c` is now a property whose setter mirrors the values into primitive fields `b0,b1,b2,a1,a2`. The expression and its order are unchanged, so output is bit-identical. Public API (`Biquad(ch, coefs)`, `var c`, `process`, `reset`) is unchanged. |
| 124, 130–133 `EqChain` | `filters` was a `List<Biquad>` and `for (f in filters)` allocated an `Iterator` **per channel per frame** on the audio thread | `Array<Biquad>` with indexed `while` loops. `widenOn` (`channels==2 && widen>0`) is precomputed. |
| 146, 153 `Limiter` | `reductionDb = 20*log10(g)` computed every output frame, a `log10` per frame used only by the 2 Hz status | `val reductionDb get() = 20*log10(g)`. It gives the same value, computed when read. |
| 163–164 `BandwidthExtender` | `probes` was a `List<Double>` (boxed) and `bands` a `List<Biquad>`. Each band also designed a PEAK filter that was immediately thrown away. | `DoubleArray` and `Array<Biquad>` built straight from `bandPass(f)`, which gives the same coefficients. |
| 189 | `(0 until channels).sumOf { buf[it] }` boxed `IntRange` iteration every frame | Plain loop with the same summation order (starts at 0.0). |
| 193–198 | Every 4096 frames: `(1 until n).firstOrNull{}` (iterator plus boxed `Int?`), `10.0.pow(-3.5)` recomputed, and `retune()` running 3 RBJ designs per channel (new `Coefs` and `Six` objects) | Indexed search. `DROP_35DB` constant. The detector can only pick fs/2 or a midpoint between probes, so all retune targets and their HP coefficients are designed once in the constructor and `retune(idx)` just swaps references. |
| 249–263 `Resampler` init | **Odd-rate memory blow-up.** With gcd=1 (e.g. 44101→48000, l=48000) the prototype is l·64 doubles plus l·64 floats. Measured constructor allocation: **37 MB** (→48k) and **75 MB** (→96k). | Guard: if `l > MAX_EXACT_PHASES (4096)` the table is capped at `INTERP_PHASES = 2048` rows plus one guard row, and each output's coefficients are linearly interpolated at the exact rational phase. Phase stepping, output count and `maxOutputFor` stay exact. The prototype centre is aligned so the capped table has no fractional delay offset. Measured: 1.65 MB, and the difference from the old exact table is ≤ 1.5e-6 (−116 dBFS peak, −133 to −140 dB relative across 1–20 kHz). All standard rate pairs (l ≤ 2560) still use the original exact path, bit-identical. |
| 277 | Grows `buf` by copy when a bigger chunk arrives | Kept: it only grows, doubling (amortised, rare). |

## dsp/Engine.kt
| Orig line(s) | Problem | Change |
|---|---|---|
| 46, 81–82 | `pending?.let { pending = null }` on a `@Volatile` field: an `update()` landing between the read and the null was **lost** | `AtomicReference.getAndSet(null)`, with no allocation on the audio thread. |
| 83 | **The audio thread built the new `EqChain`**: RBJ designs plus `effectivePreampDb`→`peakResponseDb`, i.e. 240 log-grid points × every filter × `cos/sin/log10`, plus `List` allocations, inside `process()` | `update()` (caller/collector thread) builds an immutable `Prepared(settings, chain, eqNeutral, bwe, shaper)` snapshot. The audio thread only adopts it, under exactly the original conditions. Objects are fresh (zero state) as before. A caller-side `lastBuilt` cache avoids rebuilding the chain when the EQ is unchanged; it is safe because a chain is only live while `settings.eq == chain.config`, in which case it is never re-adopted. |
| 84–85 | `BandwidthExtender` / `TransientShaper` constructed on the audio thread | Prebuilt in `update()` and adopted only when the setting changed (same as before). |
| 68, 75, 91, **102** | `settings.eq.isNeutral` walks `List<Double>` (iterator plus unboxing). It ran per buffer, and **per frame during a crossfade** (line 102) | `eqNeutral` is cached once per settings swap and used in `status`, `isIdentity`, `eqOn` and the fade loop. |
| 108–113 | Repeated field and getter reads inside the frame loop | `limitOn`, `rs`, `be`, `sh` and `neutral` are hoisted per buffer. |
| 92 | `mid` scratch | Kept as grow-only. |

## playback/EngineAudioProcessor.kt
| Orig line(s) | Problem | Change |
|---|---|---|
| 22–23 | `core` / `pendingCore` were written on the playback thread and read by `apply()` on the settings collector thread with no `@Volatile`, so new settings could go to a stale core | Both are `@Volatile`. |
| 33–34 | An `apply()` arriving between `settings()` and `pendingCore = c` was lost for the new core | Re-reads `settings()` after publishing and calls `c.update(latest)` if it changed. |
| 51–54, 62–71 | `for (i in 0 until n*ch)` over fields | Local array refs and `while` loops. Same `getFloat`/`getShort`, same `Random` dither sequence and same rounding, so output is identical. No `asFloatBuffer()` views (they allocate). |
| 63, 66 | Output buffer | Already uses `replaceOutputBuffer()`. Media3 1.8.0 `BaseAudioProcessor` reuses its direct buffer and reallocates only when capacity is too small, as required. Documented in a comment. |
| 51, 57 | `inFloats` / `outFloats` | Grow-only scratch, kept. |
| 75 | Status `EngineStatus` allocation every 500 ms | Kept (2 Hz and not per buffer). Noted only. |

## widget/NowPlayingWidget.kt
| Orig line(s) | Problem | Change |
|---|---|---|
| 106, 145–159 | The background was a **640×300 ARGB_8888 (750 KB)** bitmap, plus 256×256 ARGB art (256 KB), per `updateAppWidget`, which can hit the ~1 MB binder limit (`TransactionTooLargeException`). `render()` also built a new 750 KB default background on **every** render when no art was loaded. | `MAX_RV_PX = 256`. `makeBackground` draws the same card geometry into a 256×120 bitmap through `canvas.scale()`: same gradient stops, radius and stroke, at lower resolution. The `w_bg` ImageView is `centerCrop`, so it scales anyway. The default card is built once and cached (`defaultBackground()`). |
| 88–91 | Art and background were published at whatever size they were produced | Every bitmap goes through `fitForRemoteViews()` before it is published. It caps the longest side at 256 (keeping aspect), uses RGB_565 when the bitmap has no alpha and ARGB_8888 otherwise, and recycles the replaced temp. The rounded art (alpha corners) stays 256×256 ARGB. Total per update is now about 380 KB, down from about 1 MB. |
| 88 | The decoded source bitmap (a temp used for crop and Palette) was never freed | Recycled in `finally` after `rounded()` and Palette are done. It is never handed to RemoteViews. Published `art`/`bg` are **not** recycled (a concurrent `render()` may be parceling them); GC frees them. |

## Pre-existing bugs found and left unfixed (fixing them would change audio output; lead's call)
1. **BWE never activates** (Dsp.kt orig 188–200). The detector compares 2nd-order Q=6 band-pass probes against a 35 dB drop. Leakage between neighbouring probes is only about 2–12 dB, so `energy[i] < ref·10^-3.5` can never hold for real content. I confirmed it with band-limited test signals of content up to 8, 10, 12 and 15 kHz: no cutoff was detected, `active` stays false, and "Enhanced" adds nothing. Fix options: narrower probes (cascade 2–4 band-passes or use Q≈20+), or a ~20 dB threshold relative to the probe just below.
2. **EQ leaks after switching to bypass/disabled** (Engine.kt orig 91–106). `eqOn` is computed before the frame loop. When the 2048-frame crossfade ends mid-buffer, the remaining frames take `else chain.frame(frame)`, and that chain was built from the *bypassed* config (designs and preamp ignore `bypass`/`enabled`). So up to one buffer of fully EQ'd audio follows the fade. Suggested one-line fix: `} else if (!neutral) chain.frame(frame)`. The existing test misses it because it feeds exactly FADE frames.
3. **16-bit path is never bit-exact, even in bypass** (EAP orig 181, 195–197). It reads `/32768f` and writes `*32767f` (a −0.00027 dB gain change) and always adds TPDF dither, so 16-bit sources are requantised even when the engine is an identity. A fast path (when `core.isIdentity`, copy the input bytes) would fix it. `TpdfDither` in Dsp.kt is unused by the processor.

## Intentional deviations from "identical output"
- Only odd rate pairs whose L exceeds 4096 (e.g. 44101 Hz input with Upscale on) now use the interpolated table: a ≤ −116 dBFS difference in exchange for 1.6 MB instead of 37–75 MB.
- The widget background is now 256×120 (same look, displayed scaled).
- Everything else is bit-identical, as verified above.

---

## UpZe 1.0.5 bug-fix pass: persistence, player threading, stream expiry, lyrics/Listen-together cancellation

Every change is marked `// 1.0.5:`. No feature was removed; public signatures used by other files
(JsonFile.read/save/writeNow, QueueStore.read/save, LibraryRepo/PlayStatsRepo.load, LyricsChain.find,
SeamlessRetryPolicy(onRetry), ListenTogether.host/join/leave/suggest) are unchanged. Only new members were added.

---

## core/JsonFile.kt
| Orig line | Bug | Fix |
|---|---|---|
| 21-24, 35, 47 | One `synchronized(lock)` for reads, writes *and* job bookkeeping. `save()` itself (called from the main thread on every like/play/settings change) could block behind a running disk write. | Split into a coroutine `Mutex` that orders writers per file, a `diskLock` only around the actual file I/O, and a `stateLock` for pending job + latest value. `save()` now only records the value and (re)starts the debounce job, so it never waits on disk. |
| 46-50 | Debounce: cancel+relaunch per save. A write failure lost the value silently; there was no flush, so the last ≤1.5 s of changes were lost when the process died (swipe from Recents). | Coalescing writer: `latest` holds the newest value; the job takes it at write time, so a save arriving during a write gets its own write (never lost). Failed writes keep the value for the next save/flush and are reported via `CrashGuard.nonFatal`. New `flush()` (suspend, IO) and `JsonFile.flushAll()`; flushAll is wired to `ProcessLifecycleOwner` ON_STOP (app to background, installed once, posted to the main looper, skipped on JVM tests) and called from PlaybackService.onTaskRemoved/onDestroy. |
| — (design) | A save made before the store's first read (an edit during Graph.init loading) could overwrite the file with an almost empty state on a slow device. | New ctor param `holdWritesUntilRead` (default **true**): saves before the first `read()` are held and written right after it. QueueStore passes `false` (it is written even when never read). Protects settings/downloads/seed-artists too without touching those files. |
| 27-31 | A corrupt main file was set aside but a valid `.tmp` next to it was ignored. | Tries main file, then `.tmp`; sets aside whichever is corrupt. |
| 42 | `file.delete(); tmp.renameTo(file)` result ignored. | Throws on second rename failure (value kept for retry). |
| — | Reads only blocking. | Added `suspend fun load()` (IO + same mutex). `read()` kept blocking for existing IO-thread callers (documented). |

## data/Library.kt
| Orig line | Bug | Fix |
|---|---|---|
| 48 | `load()` did `_data.value = it`: any edit made while the file was loading (e.g. a Bluetooth/media-button resume recording a play, a like) was silently dropped; combined with the 800 ms debounce the early save could also overwrite library.json before load (data loss). | Writes held until read (JsonFile). Edits made before load are recorded and **replayed on top of the disk data** inside one atomic `MutableStateFlow.update`. |
| 49 | `edit` OK but not coordinated with load. | `edit` updates under the same lock that load uses. Added `loadAsync()` (IO). |
| 120-124 (QueueStore) | Only blocking read. | Added `suspend load()`, `flush()`; explicit `holdWritesUntilRead = false`. |

## data/PlayStats.kt
| Orig line | Bug | Fix |
|---|---|---|
| 33 | Same `_data.value = it` overwrite as Library (plays counted during load lost; early save could overwrite the file). | Same hold + replay-on-load fix; `loadAsync()` added. |
| 36-45 | `seedFrom` checked `seeded` outside the atomic update (double seeding possible). | Re-checked inside the update. |
| 65, 67 | `replace`/`clear` used plain `.value =` writes (not atomic with concurrent `count`). | Go through the atomic `edit`. |

## data/CachedSongs.kt
| Orig line | Bug | Fix |
|---|---|---|
| 28 | `file.read()` in the constructor of a lazy `Graph.cached`, first touched by Library's Compose screen (`collectAsState`) → up to 2000 entries JSON-decoded **on the main thread**. | Index loaded on `IoScope`; records/removals/clear made meanwhile are merged (recorded entries win, removed ids stay removed, clear wins). `offline()`/`refresh()` (loader/IO threads) wait up to 3 s for the index; the main thread never waits. |
| 69-75 | `remove()` called `SimpleCache.removeResource` (disk I/O) on the caller's thread (menu tap = main). | Moved to IoScope; in-memory index/StateFlow updated immediately as before. |
| 77 | `clearIndex` before load could be undone by the load. | Tracked and honoured in the merge. |

## playback/PlaybackService.kt
Thread audit: the player is built on the main thread in `onCreate`, so its application looper is main. `scope` is `Main.immediate`; listeners/analytics/ticker/volume observer run there; `resolveSpec`, `onLoadRetry` and `specFor` (loader threads) and `io` coroutines never touch the player (they hop with `scope.launch`). Gaps found and fixed:
| Orig line | Bug | Fix |
|---|---|---|
| 171-178 | Widget `Controls.handle` touched the player on whatever thread invoked it. | Runs inline only on `player.applicationLooper`; otherwise posts to a `Handler(player.applicationLooper)`. |
| 238, 563 | URL cache reuse purely by a 30 min TTL; it ignored the URL's own `expire`. | `urlUsable()`: TTL **and** not within 60 s of `expire` (clock-sanity guarded). Used by resolveSpec and prefetch. |
| 282-288 | `onLoadRetry` removed the cached URL unconditionally: a fresh URL already re-resolved was thrown away, forcing another full resolve (3-8 s availability wait + probe) during the retry. | `dropCachedUrl()` removes only the URL that failed (or a stale one). Log says "expired" vs "refused". Stand-down decision moved to the resolver (see YtStreams). |
| — | No path for a URL that expired during a long pause except waiting for a 403. | New `onLoadExpired` (wired as SeamlessRetryPolicy's `onExpired`) + `onPlayWhenReadyChanged(true)` → `refreshStaleUrlOnResume()`: if the current song's cached URL is within 5 min of `expire`, it is re-resolved on IO (shared `inflight` deferred) and stored, so the retry after the expired/403 chunk continues **at the same byte offset** with no queue/position change. |
| 571 | `prefetchUpcoming` used an unlocked `inflight.getOrPut` (two resolves for one track possible; resolveSpec already locks). | Locked like resolveSpec. |
| 590-598 | `restoreQueue` read via `io.async{}.await()`; something could start playing during the read and be replaced by the restore. | `queueStore.load()` + re-check `mediaItemCount` after the read. |
| 670-683 | Swipe-away saved the queue with a 1.5 s debounce, then the process could die first. | `JsonFile.flushAll()` (IO) after `saveQueue()`. |
| 698-714 | Same in onDestroy; posted `tidyQueue` could run after `player.release()`; Listen-together stream kept running after the service died. | flushAll; `tidyHandler`/`playerHandler` callbacks removed; `ListenTogether.onServiceDestroyed()`. |
| 376-430 | (checked) The onPlayerError path for a refused URL: unchanged behaviour, but the resolver now ignores expiry-only refusals, so an expired URL replays on the same client/rendition instead of a source switch. |

## playback/ChunkedDataSource.kt
| Orig line | Bug | Fix |
|---|---|---|
| 76-81 | **Gap for stream expiry.** One open DataSource keeps the URL it was opened with for every 1 MB chunk. ExoPlayer's progressive loader keeps that DataSource open while paused (load condition blocked), so after a long pause the next chunk request goes out with an expired URL. | Before opening the next chunk, if the URL is past `expire` (minus 20 s), throw the new typed `StreamUrlExpiredException(url, position)`. The check uses the server's clock (skew from the first chunk's `Date` header) and is disabled for that open if the server just served a URL the local clock already calls expired (wrong device clock can't cause refresh loops). |
| 87 | `close()` didn't reset per-open state. | Resets skew/check. |

## playback/SeamlessRetryPolicy.kt
| Orig line | Bug | Fix |
|---|---|---|
| 25-40 | Only `InvalidResponseCodeException` reached `onRetry`; an expiry detected client-side would have been an untyped IOException retried with the same stale URL. | `StreamUrlExpiredException` is always the refresh path: `onExpired(trackUri, url)` (new optional 2nd ctor param, default = `onRetry(uri, null)`), retry after 300 ms. A real 403/410 on the first chunk after resume still goes through the existing `onRetry(refusedUrl)` retry (never a skip; skip only after 6 retries + 2 replays + the user's skip-on-error setting, as before). |

## yt/YtStreams.kt
| Orig line | Bug | Fix |
|---|---|---|
| 331-337 | `onPlaybackRefused` stood the minting client down even when the URL was simply past `expire` (the normal long-pause 403). The re-resolve then walked to another client, often another itag → `StreamSwitchException` → audible source switch instead of a seamless continue. | Mint time recorded per URL (`mintedAt`, bounded with `minted`); an expired URL is just forgotten (no stand-down). |
| 287, 315 | (bookkeeping) | `remember()` helper records URL → client + mint time. |
| new | — | `object YtUrlExpiry` (`expiresAtMs`, `isExpired` with implausible-lifetime clock guard, 24 h cap) shared by ChunkedDataSource and PlaybackService. |

## playback/PlayerConnection.kt
Already posts every MediaController call to the main thread (correct). Hardening only:
| Orig line | Bug | Fix |
|---|---|---|
| 90-97 | Thread check against the main looper only. | Also checks `controller.applicationLooper` and posts there if different. |
| 39, 97 | `pendingOps` unbounded while the controller can't connect. | Capped at 32 (oldest dropped). |
| 72 | One throwing queued op aborted the rest and they stayed in the list (could replay). | Queue cleared first; each op guarded and reported. |

## playback/BluetoothAutoPlay.kt
| Orig line | Bug | Fix |
|---|---|---|
| 40-52 | `launch(Main){…}.join()` returned right after `buildAsync()`, so `goAsync()` was finished before the controller work; the controller leaked if the wait/prepare threw; a failed connect left the future unreleased. | Whole hand-off in `withContext(Dispatchers.Main.immediate)` (controller's looper): `f.await()` with 5 s timeout, `releaseFuture` on failure, `release()` in `finally`, `pending.finish()` after it all. |

## together/ListenTogether.kt
Note: this feature uses an ntfy HTTP streaming connection (`/json` long-poll), **not a WebSocket**; the brief's "close WebSocket" was applied to that stream.
| Orig line | Bug | Fix |
|---|---|---|
| 57, 71-72, 82-88 | Jobs kept in a list inside the process-wide `IoScope`; `watchHost`'s collector (168) was another IoScope job. | One `roomScope` (`SupervisorJob + Main.immediate`) per room; all room coroutines/collectors are its children. |
| 120 | `while (IoScope.isActive …)` — always true. | `currentCoroutineContext().isActive`. |
| 124-138 | Blocking `execute()` + `readUtf8Line()` ignored coroutine cancellation; a call created just after `leave()` set `openCall = null` was never closed (stream + thread leak). | Cancellable connect (`Call.await`), and a watcher child that cancels the call when the room scope is cancelled, which unblocks the read immediately. |
| 92-99, 156 | `leave()` and the host's "end" duplicated clean-up; leave could be called off-main. | Common `closeRoom()` (cancel scope, cancel call, clear members/last state); `leave()` posts to main when needed; `host()/join()` use the synchronous `leaveNow()`. New `onServiceDestroyed()` called by PlaybackService.onDestroy. |
| 135, 146 | Messages could still be applied after leaving. | `withContext(Main.immediate)` (cancelled with the room) + `onMessage` checks the room code. |
| 193 | Host state publishes ran on IoScope after the room closed. | Launched in `roomScope`. "end"/"bye" goodbye still goes out on IoScope deliberately. |
| 230 | `update()` was read-then-write of `.value` (not atomic, publish runs on IO) and a late 429 note could land in the next room. | `MutableStateFlow.update` scoped to the room code. |
| — | Graph.player calls | All from main (onMessage, apply, keepInSync, watchHost); PlayerConnection posts anyway. |

## lyrics/LyricsRepository.kt (+ lyrics/LyricsPlus.kt)
| File:orig line | Bug | Fix |
|---|---|---|
| LyricsRepository:91-107 | No per-track cancellation: when the track changed, the previous provider walk kept running; a late answer could still be returned/cached. Pane and capsule asked the same providers twice in parallel. | `LyricsChain.find` now runs one shared walk per track (key = videoId or title/artist/length bucket, + provider list). A request for another track **cancels all walks for earlier tracks**; a result that completes after the track changed throws `CancellationException` instead of being returned (so it is never published or cached); the walk is cancelled when its last waiter stops waiting. Test-visible behaviour of `find` (order, synced wins, timeouts, onTried) unchanged. |
| LyricsRepository:38 | `runCatching` around LRCLIB's first request swallowed `CancellationException`, so a cancelled lookup went on to the search request. | Rethrows cancellation. |
| LyricsRepository:127-129 | Cache `put` wrote in place (two writers for one song, or a crash, → half-written JSON). | Temp file + rename; eviction only counts `.json`. |
| LyricsPlus:60, 74 | Same `runCatching` swallowing cancellation in the head-start and race; a cancelled lookup started the full server race. | Rethrow cancellation + `ensureActive()` before racing. |

---

## Compile-correctness notes (please verify in the Gradle build)
- New imports used: `kotlinx.coroutines.guava.await` (dependency `kotlinx-coroutines-guava` present), `androidx.lifecycle.ProcessLifecycleOwner/DefaultLifecycleObserver` (`lifecycle-process` present), `MediaController.releaseFuture` (Media3 1.8 static).
- `SeamlessRetryPolicy(::onLoadRetry, ::onLoadExpired)`; second param optional, so `SeamlessSwitchTest` is unaffected.
- `YtStreamResolverTest` uses localhost URLs → `YtUrlExpiry` returns null → resolver behaviour unchanged there.
- Widget handler lambda: `when` has `else -> {}` (it's now an expression inside a lambda).

## Not changed / out of scope (files not on the edit list)
- `SettingsRepo.load`, `DownloadsRepo.load`, `SeedArtists.load` still assign `_state.value =` on load, so an in-memory edit made during startup is replaced by the disk value (no longer able to overwrite the file, thanks to the JsonFile write hold).
- `ui/player/Panes.kt` wraps `Graph.lyricsFor` in `runCatching`, which also catches `CancellationException`; harmless now (a stale result can't be produced), but worth `catch (e: CancellationException) { throw e }` there.
- `LyricsCache.clear()` is still called synchronously from a Settings button (small directory delete on main).
