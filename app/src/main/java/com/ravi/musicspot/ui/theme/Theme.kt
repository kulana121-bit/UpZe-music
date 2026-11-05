package com.ravi.musicspot.ui.theme

import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Surface
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalView
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import com.ravi.musicspot.data.ThemeMode
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.palette.graphics.Palette
import coil3.BitmapImage
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import com.ravi.musicspot.logic.Contrast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Soft palette: lower saturation and contrast than v1's "Obsidian Redux" coral, warm-grey surfaces,
 * and every text colour checked against its surface for WCAG AA (4.5:1 body, 3:1 large).
 */
object Ms {
    val Canvas = Color(0xFF141317)
    val Surface1 = Color(0xFF1A191E)
    val Surface2 = Color(0xFF221F26)
    val Surface3 = Color(0xFF2B282F)
    val Accent = Color(0xFFE7A9B0)       // v2.0.8 soft rose (was #E59A9C coral)
    val OnAccent = Color(0xFF2B1314)
    val Text = Color(0xFFE9E5E7)
    val TextSecondary = Color(0xFFB7B2B9)
    val TextTertiary = Color(0xFF9A98A0)  // 6.3:1 on Canvas
    val Hairline = Color(0x1AFFFFFF)
    val Error = Color(0xFFF2B8B5)
    val Warning = Color(0xFFE8C88A)
    val Success = Color(0xFF9CD5AE)

    val r8 = RoundedCornerShape(8.dp)
    val r12 = RoundedCornerShape(12.dp)
    val r16 = RoundedCornerShape(16.dp)
    val r20 = RoundedCornerShape(20.dp)
    val r28 = RoundedCornerShape(28.dp)
    val pill = RoundedCornerShape(50)
}

/** Light-mode base colours (warm paper), same soft character as the dark palette. */
object MsLight {
    val Canvas = Color(0xFFF7F4F2)
    val Surface1 = Color(0xFFF1EDEA)
    val Surface2 = Color(0xFFE9E4E1)
    val Surface3 = Color(0xFFE0DAD7)
    val Accent = Color(0xFF9A4F5A)
    val OnAccent = Color(0xFFFFFFFF)
    val Text = Color(0xFF1C1B1F)
    val TextSecondary = Color(0xFF4A4850)
}

/** White or black, whichever reads better on [bg] (forced fallback for palette-derived colours). */
fun readableOn(bg: Color): Color =
    if (Contrast.ratio(0xFFFFFFFF.toInt(), bg.toArgb()) >= Contrast.ratio(0xFF000000.toInt(), bg.toArgb())) Color.White else Color(0xFF0B0B0D)

/** [fg] if it reaches [min] contrast on [bg]; otherwise adjusted, and finally white/black. Never returns an unreadable colour. */
fun readable(fg: Color, bg: Color, min: Double = 4.5): Color {
    val c = Color(Contrast.ensure(fg.copy(alpha = 1f).toArgb(), bg.copy(alpha = 1f).toArgb(), min))
    return if (Contrast.ratio(c.toArgb(), bg.copy(alpha = 1f).toArgb()) >= min) c else readableOn(bg)
}

/**
 * Builds the complete scheme from an accent seed (album art or the default). v2.0.2: every on-colour role is set and
 * checked against its container (WCAG AA 4.5:1; text 7:1 where possible) for dark, light and pure-black modes, so
 * nothing falls back to Material's defaults (or to LocalContentColor's black).
 */
fun softScheme(seed: Color?, dark: Boolean = true, pureBlack: Boolean = false): ColorScheme {
    if (!dark) return softLightScheme(seed)
    val accent = seed?.let { Color(Contrast.softAccent(it.toArgb())) } ?: Ms.Accent
    val bg = if (pureBlack) Color.Black else seed?.let { Color(Contrast.softSurface(it.toArgb(), 0.075f)) } ?: Ms.Canvas
    val s1 = if (pureBlack) Color(0xFF0B0B0D) else seed?.let { Color(Contrast.softSurface(it.toArgb(), 0.105f)) } ?: Ms.Surface1
    val s2 = if (pureBlack) Color(0xFF141417) else seed?.let { Color(Contrast.softSurface(it.toArgb(), 0.14f)) } ?: Ms.Surface2
    val s3 = if (pureBlack) Color(0xFF1C1C20) else seed?.let { Color(Contrast.softSurface(it.toArgb(), 0.18f)) } ?: Ms.Surface3
    val primary = readable(accent, s3, 4.5)
    val onPrimary = readable(Ms.OnAccent, primary, 4.5)
    val text = readable(Ms.Text, s3, 7.0)
    val text2 = readable(Ms.TextSecondary, s3, 4.5)
    val pc = Color(Contrast.softSurface(primary.toArgb(), 0.24f))
    return darkColorScheme(
        primary = primary, onPrimary = onPrimary,
        primaryContainer = pc, onPrimaryContainer = readable(primary, pc, 4.5),
        inversePrimary = Color(Contrast.ensure(primary.toArgb(), text.toArgb(), 3.0)),
        secondary = text2, onSecondary = readable(bg, text2, 4.5), secondaryContainer = s3, onSecondaryContainer = text,
        tertiary = readable(Ms.Success, s3, 4.5), onTertiary = Color(0xFF0E2416), tertiaryContainer = Color(0xFF1E3A28), onTertiaryContainer = Color(0xFFCDEFD6),
        background = bg, onBackground = text, surface = bg, onSurface = text,
        surfaceVariant = s2, onSurfaceVariant = text2, surfaceTint = primary,
        surfaceBright = s3, surfaceDim = bg,
        surfaceContainerLowest = bg, surfaceContainerLow = s1, surfaceContainer = s1, surfaceContainerHigh = s2, surfaceContainerHighest = s3,
        outline = Color(0xFF7A7680), outlineVariant = Color(0xFF312E36),
        error = readable(Ms.Error, s3, 4.5), onError = Color(0xFF3B0D0C), errorContainer = Color(0xFF5B1F1D), onErrorContainer = Color(0xFFF9DEDC),
        inverseSurface = text, inverseOnSurface = bg, scrim = Color.Black,
    )
}

fun softLightScheme(seed: Color?): ColorScheme {
    fun tone(l: Float, base: Color) = seed?.let { Color(Contrast.softSurface(it.toArgb(), l)) }?.let { lerp(base, it, 0.35f) } ?: base
    val bg = tone(0.965f, MsLight.Canvas); val s1 = tone(0.945f, MsLight.Surface1); val s2 = tone(0.915f, MsLight.Surface2); val s3 = tone(0.88f, MsLight.Surface3)
    val accent = seed?.let { val h = Contrast.toHsl(it.toArgb()); Color(Contrast.fromHsl(h[0], minOf(h[1], 0.55f), 0.36f)) } ?: MsLight.Accent
    val primary = readable(accent, s3, 4.5)
    val onPrimary = readable(MsLight.OnAccent, primary, 4.5)
    val text = readable(MsLight.Text, s3, 7.0)
    val text2 = readable(MsLight.TextSecondary, s3, 4.5)
    val pc = Color(Contrast.fromHsl(Contrast.toHsl(primary.toArgb())[0], 0.35f, 0.88f))
    return lightColorScheme(
        primary = primary, onPrimary = onPrimary,
        primaryContainer = pc, onPrimaryContainer = readable(primary, pc, 4.5),
        inversePrimary = Color(Contrast.softAccent(primary.toArgb())),
        secondary = text2, onSecondary = readable(bg, text2, 4.5), secondaryContainer = s3, onSecondaryContainer = text,
        tertiary = Color(0xFF2E6B45), onTertiary = Color.White, tertiaryContainer = Color(0xFFCDEFD6), onTertiaryContainer = Color(0xFF0E2416),
        background = bg, onBackground = text, surface = bg, onSurface = text,
        surfaceVariant = s2, onSurfaceVariant = text2, surfaceTint = primary,
        surfaceBright = bg, surfaceDim = s3,
        surfaceContainerLowest = Color.White, surfaceContainerLow = s1, surfaceContainer = s1, surfaceContainerHigh = s2, surfaceContainerHighest = s3,
        outline = Color(0xFF79767D), outlineVariant = Color(0xFFCAC4C2),
        error = Color(0xFFB3261E), onError = Color.White, errorContainer = Color(0xFFF9DEDC), onErrorContainer = Color(0xFF410E0B),
        inverseSurface = Color(0xFF313033), inverseOnSurface = Color(0xFFF4EFF4), scrim = Color.Black,
    )
}

private fun s(size: Int, weight: Int, line: Int, track: Double) =
    TextStyle(fontSize = size.sp, fontWeight = FontWeight(weight), lineHeight = line.sp, letterSpacing = track.em)

val MsTypography = Typography(
    displayLarge = s(46, 750, 52, -0.025), displayMedium = s(34, 750, 40, -0.02), displaySmall = s(28, 750, 34, -0.018),
    headlineLarge = s(30, 700, 36, -0.015), headlineMedium = s(23, 700, 29, -0.012), headlineSmall = s(20, 650, 26, -0.008),
    titleLarge = s(18, 600, 24, -0.004), titleMedium = s(16, 600, 22, 0.0), titleSmall = s(14, 600, 20, 0.0),
    bodyLarge = s(16, 400, 24, 0.0), bodyMedium = s(14, 400, 20, 0.004), bodySmall = s(12, 400, 16, 0.01),
    labelLarge = s(14, 600, 18, 0.008), labelMedium = s(12, 600, 16, 0.015), labelSmall = s(11, 650, 14, 0.03),
)

/** Extracts a seed colour from artwork (Coil + Palette, off the main thread). */
suspend fun artSeed(context: android.content.Context, url: String?): Color? = withContext(Dispatchers.IO) {
    if (url == null) return@withContext null
    val req = ImageRequest.Builder(context).data(url).size(96).allowHardware(false).build()
    val res = SingletonImageLoader.get(context).execute(req) as? SuccessResult ?: return@withContext null
    val bmp: Bitmap = (res.image as? BitmapImage)?.bitmap ?: return@withContext null
    val p = Palette.from(bmp).maximumColorCount(12).generate()
    (p.vibrantSwatch ?: p.mutedSwatch ?: p.dominantSwatch)?.rgb?.let { Color(it) }
}

/** v2.0.5: two artwork colours for the player background (vibrant/dominant, then dark muted/dark vibrant). */
suspend fun artPalette(context: android.content.Context, url: String?): Pair<Color, Color>? = withContext(Dispatchers.IO) {
    if (url == null) return@withContext null
    val req = ImageRequest.Builder(context).data(url).size(96).allowHardware(false).build()
    val res = SingletonImageLoader.get(context).execute(req) as? SuccessResult ?: return@withContext null
    val bmp: Bitmap = (res.image as? BitmapImage)?.bitmap ?: return@withContext null
    val p = Palette.from(bmp).maximumColorCount(16).generate()
    val a = (p.vibrantSwatch ?: p.dominantSwatch ?: p.mutedSwatch)?.rgb ?: return@withContext null
    val b = (p.darkMutedSwatch ?: p.darkVibrantSwatch ?: p.mutedSwatch ?: p.dominantSwatch)?.rgb ?: a
    Color(a) to Color(b)
}

/** Resolves the Theme setting to dark/light (System follows the device's dark mode). */
@Composable
fun isDarkTheme(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK, ThemeMode.PURE_BLACK -> true
}

/** v2.0.16: the playing song's artwork colour (always extracted, even with dynamic colour off) – used by Water glass. */
val LocalSongTint = androidx.compose.runtime.staticCompositionLocalOf<Color?> { null }

@Composable
fun MusicSpotTheme(artUrl: String?, dynamic: Boolean, mode: ThemeMode = ThemeMode.DARK, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val dark = isDarkTheme(mode)
    val pureBlack = mode == ThemeMode.PURE_BLACK
    // v2.0.16: extract the art colour once; the scheme uses it only when dynamic colour is on, Water glass always.
    var art by remember { mutableStateOf<Color?>(null) }
    LaunchedEffect(artUrl) { art = runCatching { artSeed(ctx, artUrl) }.getOrNull() }
    val seed = if (dynamic) art else null
    val target = remember(seed, dark, pureBlack) { softScheme(seed, dark, pureBlack) }
    val songTint by animateColorAsState(art ?: target.primary, tween(980), label = "song")
    // Colours glide when the song changes instead of snapping.
    val primary by animateColorAsState(target.primary, tween(980), label = "p")
    val bg by animateColorAsState(target.background, tween(980), label = "bg")
    val s1 by animateColorAsState(target.surfaceContainerLow, tween(980), label = "s1")
    val s2 by animateColorAsState(target.surfaceContainerHigh, tween(980), label = "s2")
    val s3 by animateColorAsState(target.surfaceContainerHighest, tween(980), label = "s3")
    val scheme = target.copy(primary = primary, background = bg, surface = bg, surfaceContainerLowest = if (dark) bg else target.surfaceContainerLowest, surfaceContainerLow = s1,
        surfaceContainer = s1, surfaceContainerHigh = s2, surfaceContainerHighest = s3, surfaceVariant = s2)
    // System bar icons follow the theme (light icons on dark, dark icons on light).
    val view = LocalView.current
    if (!view.isInEditMode) DisposableEffect(dark) {
        (view.context as? androidx.activity.ComponentActivity)?.let { a ->
            runCatching {
                if (dark) a.enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
                else a.enableEdgeToEdge(SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT), SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT))
            }
        }
        onDispose { }
    }
    MaterialTheme(
        colorScheme = scheme, typography = MsTypography,
        shapes = Shapes(extraSmall = Ms.r8, small = Ms.r12, medium = Ms.r16, large = Ms.r20, extraLarge = Ms.r28),
    ) {
        // v2.0.2 fix for "black text on black": Material's LocalContentColor defaults to Color.Black and nothing in the
        // main shell provided one, so every Text()/Icon() without an explicit colour drew black. The root now provides
        // onBackground, and a Surface sets it for everything below.
        CompositionLocalProvider(LocalContentColor provides scheme.onBackground, LocalSongTint provides songTint) {
            Surface(color = scheme.background, contentColor = scheme.onBackground, content = content)
        }
    }
}
