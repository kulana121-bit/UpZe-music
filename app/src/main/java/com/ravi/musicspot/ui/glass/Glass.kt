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
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.ravi.musicspot.Graph
import com.ravi.musicspot.data.GlassStyle
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
    return if (Graph.lowEnd) GlassStyle.SOLID else s.glass
}

private val edgeBrush = Brush.linearGradient(
    0f to Color.White.copy(alpha = 0.32f), 0.45f to Color.White.copy(alpha = 0.06f), 1f to Color.White.copy(alpha = 0.20f),
)

/**
 * Glass for floating controls. Text over it stays readable because the film under it is at least 55% of the
 * surface colour in the frosted/solid grades and the liquid grade darkens/lightens (vibrancy + film) toward it.
 */
@Composable
fun Modifier.glass(shape: Shape, tint: Color = MaterialTheme.colorScheme.surfaceContainerHigh, strong: Boolean = false): Modifier {
    val style = glassStyle()
    // v2.0.14: Water style – clear, see-through aqua film over a light blur, on every API level.
    if (style == GlassStyle.WATER) {
        // v2.0.17: no Haze sampling in Water (see HazeGlass.water) – one clipped film, so nothing can land off-shape.
        return HazeGlass.water(this, shape, tint, strong, MaterialTheme.colorScheme.background, com.ravi.musicspot.ui.theme.LocalSongTint.current)
    }
    if (blurSupported && style != GlassStyle.SOLID) {
        val backdrop = LocalBackdrop.current
        if (backdrop != null && style == GlassStyle.LIQUID && liquidSupported) return GlassApi33.liquid(this, backdrop, shape, tint, strong, edgeBrush)
        if (backdrop != null) return GlassApi31.frosted(this, backdrop, shape, tint, strong, edgeBrush)
        val haze = LocalHazeState.current
        if (haze != null) return HazeGlass.haze(this, haze, shape, tint, MaterialTheme.colorScheme.background, edgeBrush)
    }
    // v2.0.14: real (RenderScript) blur on Android 8–11 when a haze layer is available.
    if (legacyBlur && style != GlassStyle.SOLID) {
        val haze = LocalHazeState.current
        if (haze != null) return HazeGlass.haze(this, haze, shape, tint.copy(alpha = if (strong) 0.82f else 0.74f), MaterialTheme.colorScheme.background, edgeBrush)
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
    fun haze(m: Modifier, haze: HazeState, shape: Shape, tint: Color, background: Color, edge: Brush): Modifier =
        m.clip(shape).hazeEffect(
            state = haze,
            style = HazeStyle(backgroundColor = background, tint = HazeTint(tint.copy(alpha = minOf(tint.alpha, 0.7f).coerceAtLeast(0.55f))), blurRadius = 24.dp, noiseFactor = 0.04f),
        ) { blurEnabled = true }.border(0.75.dp, edge, shape)

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
        val film = lerp(background, wash, if (dark) 0.16f else 0.10f)
        val fill = Brush.verticalGradient(listOf(
            film.copy(alpha = if (strong) 0.70f else 0.58f),
            film.copy(alpha = if (strong) 0.62f else 0.50f),
        ))
        val rim = Brush.verticalGradient(listOf(Color.White.copy(alpha = if (dark) 0.40f else 0.85f), Color.White.copy(alpha = if (dark) 0.06f else 0.20f), wash.copy(alpha = 0.18f)))
        val sheen = Brush.verticalGradient(0f to Color.White.copy(alpha = if (dark) 0.08f else 0.22f), 0.4f to Color.Transparent, 1f to Color.Transparent)
        return m.clip(shape).background(fill).background(sheen).border(1.dp, rim, shape)
    }

}

@RequiresApi(33)
private object GlassApi33 {
    fun liquid(m: Modifier, backdrop: LayerBackdrop, shape: Shape, tint: Color, strong: Boolean, edge: Brush): Modifier {
        val film = tint.copy(alpha = if (strong) 0.62f else 0.38f)
        return m.drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                vibrancy()
                blur(18.dp.toPx())
                val depth = minOf(24.dp.toPx(), size.minDimension * 0.42f).coerceAtLeast(0f)
                lens(depth, depth * 1.6f, depthEffect = true, chromaticAberration = true)
            },
            highlight = { Highlight.Default },
            shadow = { Shadow.Default },
            innerShadow = { InnerShadow.Default },
            onDrawSurface = { drawRect(film) },
        ).border(1.dp, edge, shape)
    }
}

@RequiresApi(31)
private object GlassApi31 {
    fun frosted(m: Modifier, backdrop: LayerBackdrop, shape: Shape, tint: Color, strong: Boolean, edge: Brush): Modifier {
        val film = tint.copy(alpha = if (strong) 0.72f else 0.58f)
        return m.drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = { colorControls(saturation = 1.4f); blur(26.dp.toPx()) },
            shadow = { Shadow.Default },
            onDrawSurface = { drawRect(film) },
        ).border(0.75.dp, edge, shape)
    }

    fun source(m: Modifier, backdrop: LayerBackdrop?, haze: HazeState): Modifier =
        (if (backdrop != null) m.layerBackdrop(backdrop) else m)

}

/** Glass for surfaces that live in their own window (sheets, dialogs): translucent tinted film + edge highlight. */
@Composable
fun Modifier.glassSheet(shape: Shape): Modifier {
    val c = MaterialTheme.colorScheme
    if (glassStyle() == GlassStyle.WATER) { // v2.0.14: clearer aqua sheets in Water style
        val film = lerp(c.surfaceContainerHigh, com.ravi.musicspot.ui.theme.LocalSongTint.current ?: c.primary, 0.10f) // v2.0.16: song wash, no blue
        return this.clip(shape)
            .background(Brush.verticalGradient(listOf(film.copy(alpha = 0.84f), c.surfaceContainerHigh.copy(alpha = 0.88f)))) // v2.0.14b: clearer
            .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.35f), Color.White.copy(alpha = 0.05f))), shape)
    }
    return this.clip(shape)
        .background(Brush.verticalGradient(listOf(lerp(c.surfaceContainerHighest, Color.White, 0.04f).copy(alpha = 0.96f), c.surfaceContainerHigh.copy(alpha = 0.97f))))
        .border(0.75.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.22f), Color.Transparent)), shape)
}

/** Records [content] as the layer that the [overlay]'s glass samples (API 31+ only; plain layering below). */
@Composable
fun GlassLayer(modifier: Modifier = Modifier, background: Color = MaterialTheme.colorScheme.background, overlay: @Composable BoxScope.() -> Unit, content: @Composable BoxScope.() -> Unit) {
    if (!blurSupported) {
        // v2.0.14: on Android 8–11 record the content for Haze so glass gets a real (RenderScript) blur.
        if (glassStyle().let { it != GlassStyle.SOLID && it != GlassStyle.WATER }) { GlassLayerLegacy(modifier, background, overlay, content); return }
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

@Composable
private fun GlassLayer31(modifier: Modifier, background: Color, overlay: @Composable BoxScope.() -> Unit, content: @Composable BoxScope.() -> Unit) {
    val style = glassStyle()
    val backdrop = rememberLayerBackdrop()
    val haze = rememberHazeState()
    val sampled = style != GlassStyle.SOLID && style != GlassStyle.WATER // v2.0.17: Water samples nothing, so record no layer
    Box(modifier) {
        val src = if (Build.VERSION.SDK_INT >= 31) {
            val withBackdrop = if (sampled) GlassApi31.source(Modifier.fillMaxSize(), backdrop, haze) else Modifier.fillMaxSize()
            withBackdrop.background(background).let { if (sampled) it.hazeSource(haze) else it }
        } else Modifier.fillMaxSize().background(background)
        Box(src, content = content)
        CompositionLocalProvider(LocalBackdrop provides backdrop.takeIf { sampled }, LocalHazeState provides haze.takeIf { sampled }) { overlay() }
    }
}

/**
 * v2.0.10 iOS-style liquid glass: the app's glass (lens/blur where supported) plus a bright specular rim that fades
 * from the top edge, and a soft sheen across the upper half. Used by the Aura player and the Island dock.
 */
@Composable
fun Modifier.liquidGlass(shape: Shape, tint: Color = MaterialTheme.colorScheme.surfaceContainerHigh): Modifier {
    val dark = MaterialTheme.colorScheme.background.let { (it.red + it.green + it.blue) / 3f < 0.5f }
    val rimTop = Color.White.copy(alpha = if (dark) 0.42f else 0.75f)
    val rimMid = Color.White.copy(alpha = if (dark) 0.06f else 0.18f)
    val rimBot = Color.White.copy(alpha = if (dark) 0.16f else 0.35f)
    if (glassStyle() == GlassStyle.WATER) return this.glass(shape, tint = tint, strong = true) // v2.0.17: no second sheen/rim on Water
    return this.glass(shape, tint = tint, strong = true)
        .clip(shape)
        .background(Brush.verticalGradient(0f to Color.White.copy(alpha = if (dark) 0.07f else 0.14f), 0.45f to Color.Transparent, 1f to Color.Transparent))
        .border(1.dp, Brush.verticalGradient(listOf(rimTop, rimMid, rimBot)), shape)
}
