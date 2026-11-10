/*
 * Liquid glass, ported from OpenTune 1.0.4 app/ui/components/Glass.kt (GPL-3.0-or-later) and adapted:
 * three grades (Liquid lens on API 33+, Frosted blur on API 31+, translucent tinted fallback), driven by
 * UpZe's settings, with the fallback forced on low-RAM devices. Modified (C) 2026 Ravi, GPL-3.0-or-later.
 */
package com.ravi.musicspot.ui.glass

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import com.ravi.musicspot.Graph
import com.ravi.musicspot.data.GlassStyle
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

/** The content layer floating glass samples (provided by the app shell / a GlassPage). Null below API 31. */
val LocalBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }
val LocalHazeState = staticCompositionLocalOf<HazeState?> { null }

/*
 * API levels (v2.0.1): Kyant Backdrop and Haze are only ever touched on API 31+ (blurSupported). Their calls live
 * in the @RequiresApi objects GlassApi33 / GlassApi31 below, so on Android 8–11 (e.g. Ravi's Android 10 phone)
 * no RenderEffect/RuntimeShader code path runs and no Backdrop/Haze state is even created; glass there is the
 * translucent tinted film + edge highlight.
 */
private val liquidSupported get() = Build.VERSION.SDK_INT >= 33
private val blurSupported get() = Build.VERSION.SDK_INT >= 31
// v2.0.14: Haze's (experimental) RenderScript blur gives real blur on Android 8–11; "Reduce effects" turns it off.
private val legacyBlur get() = Build.VERSION.SDK_INT < 31

@Composable
fun glassStyle(): GlassStyle {
    val s by Graph.settings.state.collectAsState()
    return effectiveGlass(s.glass)
}

/**
 * 1.0.7: the glass grade a device actually renders. Low-end (Graph.lowEnd) stays Solid, as before. Lite-effects
 * devices (Graph.liteFx: <=96 MB heap class or <1.6 GB RAM) render Liquid and Frosted as Water: the same translucent,
 * song-tinted film with its sheen and rim, but no backdrop layer is recorded and no blur/lens shader runs, so lists
 * scrolling under the dock and the players never re-blur per frame. The user's setting is not changed (it still
 * reads Liquid/Frosted in Settings and applies in full on a capable device); Water and Solid pass through unchanged.
 * Graph.lowEnd / liteFx are fixed at startup, so the grade never flips while composed.
 */
internal fun effectiveGlass(chosen: GlassStyle, lowEnd: Boolean = Graph.lowEnd, liteFx: Boolean = Graph.liteFx): GlassStyle = when {
    lowEnd -> GlassStyle.SOLID
    liteFx && (chosen == GlassStyle.LIQUID || chosen == GlassStyle.FROSTED) -> GlassStyle.WATER
    else -> chosen
}

/**
 * 1.0.2: blur strength from Settings → Appearance → Blur intensity (0..1). 0 = no blur at all (tinted film only,
 * nothing is sampled, cheapest). The film gets a little more opaque as the blur weakens so text stays readable.
 */
@Composable
fun blurIntensity(): Float {
    val s by Graph.settings.state.collectAsState()
    return s.blurIntensity.coerceIn(0f, 1f)
}

/** Blur radius in dp for a 0..1 intensity, between [min] and [max]. */
private fun radius(k: Float, min: Float, max: Float) = min + (max - min) * k

private val edgeBrush = Brush.linearGradient(
    0f to Color.White.copy(alpha = 0.32f), 0.45f to Color.White.copy(alpha = 0.06f), 1f to Color.White.copy(alpha = 0.20f),
)

/**
 * Glass for floating controls. Text over it stays readable because the film under it is at least 55% of the
 * surface colour in the frosted/solid grades and the liquid grade darkens/lightens (vibrancy + film) toward it.
 */
@Composable
fun Modifier.glass(shape: Shape, tint: Color = MaterialTheme.colorScheme.surfaceContainerHigh, strong: Boolean = false): Modifier {
    // 1.0.5: one settings subscription per glass element (glassStyle() + blurIntensity() each started their own
    // collectAsState, i.e. two collectors per chip/pill/badge; Home alone has dozens of glass elements).
    val s by Graph.settings.state.collectAsState()
    val style = effectiveGlass(s.glass) // 1.0.7: lite devices get Water instead of a sampled blur
    // v2.0.14: Water style – clear, see-through aqua film over a light blur, on every API level.
    if (style == GlassStyle.WATER) {
        // v2.0.17: no Haze sampling in Water (see HazeGlass.water) – one clipped film, so nothing can land off-shape.
        return HazeGlass.water(this, shape, tint, strong, MaterialTheme.colorScheme.background, com.ravi.musicspot.ui.theme.LocalSongTint.current)
    }
    val k = s.blurIntensity.coerceIn(0f, 1f)
    // 1.0.2: intensity 0 = no sampling at all, just the film (the cheapest glass).
    if (k > 0.02f && blurSupported && style != GlassStyle.SOLID) {
        val backdrop = LocalBackdrop.current
        if (backdrop != null && style == GlassStyle.LIQUID && liquidSupported) return GlassApi33.liquid(this, backdrop, shape, tint, strong, edgeBrush, k)
        if (backdrop != null) return GlassApi31.frosted(this, backdrop, shape, tint, strong, edgeBrush, k)
        val haze = LocalHazeState.current
        if (haze != null) return HazeGlass.haze(this, haze, shape, tint, MaterialTheme.colorScheme.background, edgeBrush, k)
    }
    // v2.0.14: real blur on Android 8–11 when a haze layer is available. 1.0.2: sampled at ¼ size (see HazeGlass).
    if (k > 0.02f && legacyBlur && style != GlassStyle.SOLID) {
        val haze = LocalHazeState.current
        if (haze != null) return HazeGlass.haze(this, haze, shape, tint.copy(alpha = if (strong) 0.82f else 0.74f), MaterialTheme.colorScheme.background, edgeBrush, k)
    }
    return this.clip(shape).background(tint.copy(alpha = 0.94f)).border(0.75.dp, edgeBrush, shape)
}

/**
 * v2.0.17: elevation shadow for a glass control. Android draws an elevation shadow under the whole outline and it
 * shows THROUGH a translucent surface (spot shadow offset down/away from the light), which on Water read as an
 * off-white patch in the search orb's lower-right and a light band over the mini player's lower half. Water's film
 * is translucent, so it gets no elevation shadow; the rim gives the edge instead.
 */
@Composable
fun Modifier.glassShadow(elevation: androidx.compose.ui.unit.Dp, shape: Shape): Modifier =
    if (glassStyle() == GlassStyle.WATER) this
    else this.then(Modifier.shadow(elevation, shape, clip = false, ambientColor = Color.Black.copy(alpha = 0.30f), spotColor = Color.Black.copy(alpha = 0.40f)))

/** v2.0.17: true when glass films are translucent enough that elevation shadows would show through them. */
@Composable
fun glassIsClear(): Boolean = glassStyle() == GlassStyle.WATER

// v2.0.14: Haze calls work on API 23+, so they live outside the @RequiresApi(31) object.
private object HazeGlass {
    /**
     * 1.0.2: the dock / mini-player freeze fix. Haze used to blur a full-resolution snapshot of everything behind the
     * glass on every frame the content moved (scrolling a list under the dock = a full-size blur per frame, which is
     * what froze Android 10). It now samples the content at a fraction of its size and blurs that (blur hides the
     * lower resolution), which is several times less work, so the blur keeps up with scrolling in real time.
     * Noise is off: it was an extra full-size pass.
     */
    @OptIn(ExperimentalHazeApi::class)
    @Composable
    fun haze(m: Modifier, haze: HazeState, shape: Shape, tint: Color, background: Color, edge: Brush, k: Float): Modifier {
        val film = tint.copy(alpha = (minOf(tint.alpha, 0.7f).coerceAtLeast(0.55f) + (1f - k) * 0.2f).coerceAtMost(0.9f))
        // 1.0.8 lighter: samples at 1/5 (old Android, lite) or 1/4 (was 1/4 and 1/3). Blur hides the lower resolution.
        val scale = if (legacyBlur || Graph.liteFx) 0.2f else 0.25f
        // 1.0.5: the HazeStyle/HazeTint are kept across recompositions (same values -> same objects).
        val style = remember(background, film, k) { HazeStyle(backgroundColor = background, tint = HazeTint(film), blurRadius = radius(k, 6f, 20f).dp, noiseFactor = 0f) /* 1.0.8: was 8..32 dp */ }
        return m.clip(shape).hazeEffect(state = haze, style = style) { blurEnabled = true; inputScale = HazeInputScale.Fixed(scale) }.border(0.75.dp, edge, shape)
    }

    @Composable
    fun water(m: Modifier, shape: Shape, tint: Color, strong: Boolean, background: Color, song: Color?): Modifier {
        // v2.0.16: clean, clear water – no blue. A faint wash of the playing song's colour, a white rim and sheen.
        // v2.0.17: the hazeEffect (6dp blur under a ~16% film) was dropped. With so little blur and film it showed the
        // sampled snapshot almost verbatim, and Haze tracks the effect's position only on layout, not through the
        // graphicsLayer scale/translation/alpha of the dock morph, orb press, mini-player swipe or page transitions –
        // so the snapshot drew offset: the off-white blob in the search orb, the white band over the mini player's
        // lower half, page content echoed inside the header pill, and round shapes ghosting near the status bar.
        // Water is now one translucent film + sheen + rim, all clipped to the same shape at the same size.
        val dark = (background.red + background.green + background.blue) / 3f < 0.5f
        val wash = song ?: tint
        // 1.0.5: brushes and the fill+sheen draw are built once per (background, wash, strong) instead of on every
        // recomposition, and fill + sheen are one draw node (was two background() nodes). Same gradients, same order.
        val rim = remember(dark, wash) { Brush.verticalGradient(listOf(Color.White.copy(alpha = if (dark) 0.40f else 0.85f), Color.White.copy(alpha = if (dark) 0.06f else 0.20f), wash.copy(alpha = 0.18f))) }
        val films = remember(background, wash, strong) {
            val film = lerp(background, wash, if (dark) 0.16f else 0.10f)
            val fill = Brush.verticalGradient(listOf(
                film.copy(alpha = if (strong) 0.70f else 0.58f),
                film.copy(alpha = if (strong) 0.62f else 0.50f),
            ))
            val sheen = if (dark) waterSheenDark else waterSheenLight
            Modifier.drawBehind { drawRect(fill); drawRect(sheen) }
        }
        return m.clip(shape).then(films).border(1.dp, rim, shape)
    }

    // 1.0.5: these only depend on dark/light, so they are allocated once.
    private val waterSheenDark = Brush.verticalGradient(0f to Color.White.copy(alpha = 0.08f), 0.4f to Color.Transparent, 1f to Color.Transparent)
    private val waterSheenLight = Brush.verticalGradient(0f to Color.White.copy(alpha = 0.22f), 0.4f to Color.Transparent, 1f to Color.Transparent)

}

@RequiresApi(33)
private object GlassApi33 {
    fun liquid(m: Modifier, backdrop: LayerBackdrop, shape: Shape, tint: Color, strong: Boolean, edge: Brush, k: Float): Modifier {
        val film = tint.copy(alpha = (if (strong) 0.62f else 0.38f) + (1f - k) * 0.18f)
        // 1.0.2: lighter lens. Chromatic aberration (three lens samples per pixel) is off, and lite devices skip the
        // inner shadow (another full pass), so the lens keeps up with scrolling instead of stuttering under the dock.
        // 1.0.8 lighter: 4..16 dp blur (was 6..26). The lens + vibrancy carry the glass look; the smaller radius is
        // much cheaper per frame under scrolling content.
        val blurPx = radius(k, 4f, 16f)
        val glassed = if (Graph.liteFx) m.drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                vibrancy()
                blur(blurPx.dp.toPx())
                val depth = minOf(24.dp.toPx(), size.minDimension * 0.42f).coerceAtLeast(0f)
                lens(depth, depth * 1.6f, depthEffect = true, chromaticAberration = false)
            },
            highlight = { Highlight.Default },
            shadow = { Shadow.Default },
            onDrawSurface = { drawRect(film) },
        ) else m.drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                vibrancy()
                blur(blurPx.dp.toPx())
                val depth = minOf(24.dp.toPx(), size.minDimension * 0.42f).coerceAtLeast(0f)
                lens(depth, depth * 1.6f, depthEffect = true, chromaticAberration = false)
            },
            highlight = { Highlight.Default },
            shadow = { Shadow.Default },
            // 1.0.8 lighter: no inner shadow on any device now (it was a second full shader pass per glass element).
            onDrawSurface = { drawRect(film) },
        )
        return glassed.border(1.dp, edge, shape)
    }
}

@RequiresApi(31)
private object GlassApi31 {
    fun frosted(m: Modifier, backdrop: LayerBackdrop, shape: Shape, tint: Color, strong: Boolean, edge: Brush, k: Float): Modifier {
        val film = tint.copy(alpha = ((if (strong) 0.72f else 0.58f) + (1f - k) * 0.18f).coerceAtMost(0.92f))
        return m.drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = { colorControls(saturation = 1.4f); blur(radius(k, 6f, 20f).dp.toPx()) }, // 1.0.8 lighter: was 8..34 dp
            shadow = { Shadow.Default },
            onDrawSurface = { drawRect(film) },
        ).border(0.75.dp, edge, shape)
    }

    fun source(m: Modifier, backdrop: LayerBackdrop?): Modifier =
        (if (backdrop != null) m.layerBackdrop(backdrop) else m)

}

/** Glass for surfaces that live in their own window (sheets, dialogs): translucent tinted film + edge highlight. */
@Composable
fun Modifier.glassSheet(shape: Shape): Modifier {
    val c = MaterialTheme.colorScheme
    // 1.0.5: fills remembered per colour (they glide during a song change), rims are constants.
    if (glassStyle() == GlassStyle.WATER) { // v2.0.14: clearer aqua sheets in Water style
        val wash = com.ravi.musicspot.ui.theme.LocalSongTint.current ?: c.primary
        val fill = remember(c.surfaceContainerHigh, wash) {
            val film = lerp(c.surfaceContainerHigh, wash, 0.10f) // v2.0.16: song wash, no blue
            Brush.verticalGradient(listOf(film.copy(alpha = 0.84f), c.surfaceContainerHigh.copy(alpha = 0.88f))) // v2.0.14b: clearer
        }
        return this.clip(shape).background(fill).border(1.dp, sheetRimWater, shape)
    }
    val fill = remember(c.surfaceContainerHighest, c.surfaceContainerHigh) {
        Brush.verticalGradient(listOf(lerp(c.surfaceContainerHighest, Color.White, 0.04f).copy(alpha = 0.96f), c.surfaceContainerHigh.copy(alpha = 0.97f)))
    }
    return this.clip(shape).background(fill).border(0.75.dp, sheetRim, shape)
}

private val sheetRimWater = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.35f), Color.White.copy(alpha = 0.05f))) // 1.0.5: constant
private val sheetRim = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.22f), Color.Transparent)) // 1.0.5: constant

/** Records [content] as the layer that the [overlay]'s glass samples (API 31+ only; plain layering below). */
@Composable
fun GlassLayer(modifier: Modifier = Modifier, background: Color = MaterialTheme.colorScheme.background, overlay: @Composable BoxScope.() -> Unit, content: @Composable BoxScope.() -> Unit) {
    if (!blurSupported) {
        // v2.0.14: on Android 8–11 record the content for Haze so glass gets a real (RenderScript) blur.
        // 1.0.2: Blur intensity "Off" records nothing (no Haze source layer, no per-frame snapshot).
        if (blurIntensity() > 0.02f && glassStyle().let { it != GlassStyle.SOLID && it != GlassStyle.WATER }) { GlassLayerLegacy(modifier, background, overlay, content); return }
        Box(modifier) {
            Box(Modifier.fillMaxSize().background(background), content = content)
            CompositionLocalProvider(LocalBackdrop provides null, LocalHazeState provides null) { overlay() }
        }
        return
    }
    GlassLayer31(modifier, background, overlay, content)
}

@Composable
private fun GlassLayerLegacy(modifier: Modifier, background: Color, overlay: @Composable BoxScope.() -> Unit, content: @Composable BoxScope.() -> Unit) {
    val haze = rememberHazeState()
    Box(modifier) {
        Box(Modifier.fillMaxSize().background(background).hazeSource(haze), content = content)
        CompositionLocalProvider(LocalBackdrop provides null, LocalHazeState provides haze) { overlay() }
    }
}

/*
 * 1.0.5: the content used to be recorded TWICE every frame on API 31+: once by layerBackdrop (Kyant) and once by
 * hazeSource (a second full-screen GraphicsLayer, plus Haze's position tracking on every layout/scroll). On API 31+
 * glass() always takes the Backdrop path whenever this layer samples (LocalBackdrop and LocalHazeState were provided
 * under the same `sampled` condition, and Backdrop is checked first), so the Haze recording was never read. Only the
 * Backdrop layer is recorded now and LocalHazeState is null here; API < 31 keeps Haze via GlassLayerLegacy.
 */
@Composable
private fun GlassLayer31(modifier: Modifier, background: Color, overlay: @Composable BoxScope.() -> Unit, content: @Composable BoxScope.() -> Unit) {
    val style = glassStyle()
    val backdrop = rememberLayerBackdrop()
    val sampled = style != GlassStyle.SOLID && style != GlassStyle.WATER && blurIntensity() > 0.02f // v2.0.17 Water / 1.0.2 blur Off: record no layer
    Box(modifier) {
        val src = if (Build.VERSION.SDK_INT >= 31) {
            val withBackdrop = if (sampled) GlassApi31.source(Modifier.fillMaxSize(), backdrop) else Modifier.fillMaxSize()
            withBackdrop.background(background)
        } else Modifier.fillMaxSize().background(background)
        Box(src, content = content)
        CompositionLocalProvider(LocalBackdrop provides backdrop.takeIf { sampled }, LocalHazeState provides null) { overlay() }
    }
}

/**
 * v2.0.10 iOS-style liquid glass: the app's glass (lens/blur where supported) plus a bright specular rim that fades
 * from the top edge, and a soft sheen across the upper half. Used by the Aura player and the Orb dock.
 */
@Composable
fun Modifier.liquidGlass(shape: Shape, tint: Color = MaterialTheme.colorScheme.surfaceContainerHigh): Modifier {
    val dark = MaterialTheme.colorScheme.background.let { (it.red + it.green + it.blue) / 3f < 0.5f }
    if (glassStyle() == GlassStyle.WATER) return this.glass(shape, tint = tint, strong = true) // v2.0.17: no second sheen/rim on Water
    // 1.0.5: sheen and rim only depend on dark/light: preallocated, no Brush/List per recomposition.
    return this.glass(shape, tint = tint, strong = true)
        .clip(shape)
        .background(if (dark) liquidSheenDark else liquidSheenLight)
        .border(1.dp, if (dark) liquidRimDark else liquidRimLight, shape)
}

private val liquidSheenDark = Brush.verticalGradient(0f to Color.White.copy(alpha = 0.07f), 0.45f to Color.Transparent, 1f to Color.Transparent)
private val liquidSheenLight = Brush.verticalGradient(0f to Color.White.copy(alpha = 0.14f), 0.45f to Color.Transparent, 1f to Color.Transparent)
private val liquidRimDark = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.42f), Color.White.copy(alpha = 0.06f), Color.White.copy(alpha = 0.16f)))
private val liquidRimLight = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.75f), Color.White.copy(alpha = 0.18f), Color.White.copy(alpha = 0.35f)))
