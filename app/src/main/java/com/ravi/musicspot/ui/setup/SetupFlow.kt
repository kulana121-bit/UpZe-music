// v2.0.15: first-time design setup – theme, glass, player, dock & nav, home, artists, feel & sound – with a live phone preview.
package com.ravi.musicspot.ui.setup

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ravi.musicspot.Graph
import com.ravi.musicspot.data.AppSettings
import com.ravi.musicspot.data.DockStyle
import com.ravi.musicspot.data.GlassStyle
import com.ravi.musicspot.data.HomeStyle
import com.ravi.musicspot.data.NavBarMode
import com.ravi.musicspot.data.PlayerStyle
import com.ravi.musicspot.data.ThemeMode
import com.ravi.musicspot.ui.glass.GlassLayer
import com.ravi.musicspot.ui.glass.glass
import com.ravi.musicspot.ui.glass.liquidGlass
import com.ravi.musicspot.ui.motion.reducedMotion
import com.ravi.musicspot.ui.settings.ChoiceRow
import com.ravi.musicspot.ui.settings.Group
import com.ravi.musicspot.ui.settings.RowDivider
import com.ravi.musicspot.ui.settings.SwitchRow
import com.ravi.musicspot.ui.theme.Ms
import com.ravi.musicspot.ui.theme.MsLight
import com.ravi.musicspot.ui.theme.isDarkTheme
import com.ravi.musicspot.ui.theme.softScheme
import kotlinx.coroutines.launch

// v2.0.15: step order. The Artists step hides the phone preview; FEEL is the last page ("Start listening").
private const val S_THEME = 0
private const val S_GLASS = 1
private const val S_PLAYER = 2
private const val S_DOCK = 3
private const val S_HOME = 4
private const val S_ARTISTS = 5
private const val S_FEEL = 6
private const val SETUP_STEPS = 7

// v2.0.15: "album art" colours the preview's glass floats over, and the seed used to simulate dynamic colour.
private val ArtPink = Color(0xFFFF6B8B)
private val ArtViolet = Color(0xFF7C5CFF)
private val ArtTeal = Color(0xFF22C1C3)
private val ArtAmber = Color(0xFFFFB347)
private val ArtGradient = Brush.linearGradient(listOf(ArtPink, ArtViolet, ArtTeal))

private fun finishSetup() = Graph.settings.update { it.copy(setupDone = true) } // v2.0.15

/** v2.0.15: full-screen first-time setup. Every choice is applied live through Graph.settings. */
@Composable
fun SetupFlow() {
    val s by Graph.settings.state.collectAsState()
    val still = reducedMotion()
    val pager = rememberPagerState(pageCount = { SETUP_STEPS })
    val scope = rememberCoroutineScope()
    val page = pager.currentPage
    fun go(to: Int) {
        val t = to.coerceIn(0, SETUP_STEPS - 1)
        scope.launch { if (still) pager.scrollToPage(t) else pager.animateScrollToPage(t) }
    }
    BackHandler(enabled = page > 0) { go(page - 1) }

    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).windowInsetsPadding(WindowInsets.safeDrawing)) {
        val previewH = maxHeight * 0.42f
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                StepDots(page, still)
                Spacer(Modifier.weight(1f))
                if (page < SETUP_STEPS - 1) TextButton(onClick = { finishSetup() }) { Text("Skip setup") }
            }
            AnimatedVisibility(
                visible = page != S_ARTISTS,
                enter = if (still) EnterTransition.None else expandVertically() + fadeIn(),
                exit = if (still) ExitTransition.None else shrinkVertically() + fadeOut(),
            ) {
                Box(Modifier.fillMaxWidth().height(previewH).padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
                    PhonePreview(s, playerStep = page == S_PLAYER, still = still, modifier = Modifier.fillMaxHeight().aspectRatio(0.5f))
                }
            }
            HorizontalPager(state = pager, modifier = Modifier.weight(1f).fillMaxWidth()) { p ->
                if (p == S_ARTISTS) {
                    ArtistPickerStep(Modifier.fillMaxSize())
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                        Column(Modifier.widthIn(max = 560.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
                            when (p) {
                                S_THEME -> ThemeStep(s)
                                S_GLASS -> GlassStep(s)
                                S_PLAYER -> PlayerStep(s)
                                S_DOCK -> DockStep(s)
                                S_HOME -> HomeStep(s)
                                else -> FeelStep(s)
                            }
                            Spacer(Modifier.height(12.dp))
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (page > 0) OutlinedButton(onClick = { go(page - 1) }) { Text("Back") }
                Spacer(Modifier.weight(1f))
                if (page == S_ARTISTS) TextButton(onClick = { go(page + 1) }) { Text("Skip") }
                if (page == SETUP_STEPS - 1) Button(onClick = { finishSetup() }) { Text("Start listening") }
                else Button(onClick = { go(page + 1) }) { Text("Next") }
            }
        }
    }
}

@Composable
private fun StepDots(page: Int, still: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(SETUP_STEPS) { i ->
            val w by animateDpAsState(if (i == page) 22.dp else 8.dp, if (still) tween<Dp>(0) else tween<Dp>(250), label = "dot")
            Box(Modifier.height(8.dp).width(w).clip(CircleShape).background(
                if (i == page) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)))
        }
    }
}

// ---- steps ------------------------------------------------------------------------------------------------------

@Composable
private fun StepHeader(title: String, subtitle: String) {
    Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(4.dp))
    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(14.dp))
}

@Composable
private fun ThemeStep(s: AppSettings) {
    StepHeader("Pick your look", "Light, dark or pure black. The preview above changes as you tap.")
    OptionGrid(ThemeMode.entries, s.themeMode, { it.label }, { null }, { m -> Graph.settings.update { it.copy(themeMode = m) } }) { m -> ThemeSwatch(m) }
    Spacer(Modifier.height(8.dp))
    Group {
        SwitchRow("Dynamic colour", "Tint the app with soft colours from the playing song's artwork", s.dynamicColor) { v -> Graph.settings.update { it.copy(dynamicColor = v) } }
    }
}

@Composable
private fun GlassStep(s: AppSettings) {
    StepHeader("Choose your glass", if (Graph.lowEnd) "This device reports low memory, so solid surfaces are always used." else "How floating bars and cards look over your music.")
    OptionGrid(GlassStyle.entries, s.glass, { it.label }, { glassDetail(it) }, { g -> if (!Graph.lowEnd) Graph.settings.update { it.copy(glass = g) } }) { g -> GlassMini(g) }
    Spacer(Modifier.height(12.dp))
    // The real Modifier.glass over a colourful gradient, so the chosen grade is visible full size.
    GlassLayer(Modifier.fillMaxWidth().height(120.dp).clip(Ms.r20), background = Color.Transparent, overlay = {
        Box(Modifier.align(Alignment.Center).fillMaxWidth(0.8f).height(64.dp).glass(Ms.r20), contentAlignment = Alignment.Center) {
            Text("Sample card", style = MaterialTheme.typography.titleMedium)
        }
    }) { ArtBackdrop(strong = true) }
}

private fun glassDetail(g: GlassStyle): String = when (g) {
    GlassStyle.LIQUID -> "Lens & blur, Android 13+"
    GlassStyle.FROSTED -> "Soft blur, Android 12+"
    GlassStyle.WATER -> "Clear aqua, every phone"
    GlassStyle.SOLID -> "Fastest, easiest to read"
}

@Composable
private fun PlayerStep(s: AppSettings) {
    StepHeader("Now Playing", "The full-screen player. You can switch any time in Appearance.")
    OptionGrid(PlayerStyle.entries, s.playerStyle, { it.label }, { null }, { v -> Graph.settings.update { it.copy(playerStyle = v) } }) { v -> PlayerMini(v) }
}

@Composable
private fun DockStep(s: AppSettings) {
    StepHeader("Your dock", "The bar with Home, Search and Library at the bottom of the screen.")
    OptionGrid(DockStyle.entries, s.dockStyle, { it.label }, { null }, { v -> Graph.settings.update { it.copy(dockStyle = v) } }) { v -> DockMini(v) }
    Spacer(Modifier.height(8.dp))
    Group {
        ChoiceRow("While scrolling", NavBarMode.entries, s.navMode, { it.label }, subtitle = "Mini shrinks the bar to a pill, Hide slides it away; scrolling up brings it back.") { v ->
            Graph.settings.update { it.copy(navBarMode = v, hideNavOnScroll = v != NavBarMode.ALWAYS) }
        }
    }
}

@Composable
private fun HomeStep(s: AppSettings) {
    StepHeader("Your Home", "Same sections in every design, just laid out differently.")
    OptionGrid(HomeStyle.entries, s.homeStyle, { it.label }, { null }, { v -> Graph.settings.update { it.copy(homeStyle = v) } }) { v -> HomeMini(v) }
}

@Composable
private fun FeelStep(s: AppSettings) {
    StepHeader("Motion & sound", "Last touches. Everything here is in Settings too.")
    Group("Motion & touch") {
        SwitchRow("Reduce motion", "Fades instead of slides and springs", s.reduceMotion) { v -> Graph.settings.update { it.copy(reduceMotion = v) } }
        RowDivider()
        SwitchRow("Haptics", "Light taps on controls and gestures", s.haptics) { v -> Graph.settings.update { it.copy(haptics = v) } }
    }
    Group("Playback", footer = "Crossfade isn't applied while gapless is on.") {
        SwitchRow("Gapless", "No silence between tracks of an album", s.gapless) { v -> Graph.settings.update { it.copy(gapless = v) } }
        RowDivider()
        SwitchRow("Autoplay", "Keep playing similar songs when the queue ends", s.autoplay) { v -> Graph.settings.update { it.copy(autoplay = v) } }
        RowDivider()
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("Crossfade", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0, 3, 6, 9).forEach { sec ->
                    FilterChip(selected = s.crossfadeSec == sec, onClick = { Graph.settings.update { it.copy(crossfadeSec = sec) } },
                        label = { Text(if (sec == 0) "Off" else "${sec}s") }, shape = Ms.pill)
                }
            }
        }
    }
}

// ---- option cards -----------------------------------------------------------------------------------------------

@Composable
private fun <T> OptionGrid(
    options: List<T>, selected: T, label: (T) -> String, detail: (T) -> String?, onSelect: (T) -> Unit,
    illustration: @Composable BoxScope.(T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        options.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { o ->
                    OptionCard(o == selected, label(o), detail(o), Modifier.weight(1f), { onSelect(o) }) { illustration(o) }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun OptionCard(selected: Boolean, title: String, detail: String?, modifier: Modifier, onClick: () -> Unit, illustration: @Composable BoxScope.() -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(20.dp)
    val haptic = LocalHapticFeedback.current
    val s by Graph.settings.state.collectAsState()
    Box(modifier
        .clip(shape)
        .background(if (selected) cs.primary.copy(alpha = 0.10f) else cs.surfaceContainerLow)
        .border(if (selected) 2.dp else 1.dp, if (selected) cs.primary else cs.outlineVariant.copy(alpha = 0.5f), shape)
        .clickable { if (s.haptics) haptic.performHapticFeedback(HapticFeedbackType.LongPress); onClick() }
        .padding(10.dp)) {
        Column {
            Box(Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(12.dp)).background(cs.surfaceContainerHigh), content = illustration)
            Spacer(Modifier.height(8.dp))
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 2)
        }
        if (selected) Icon(Icons.Rounded.CheckCircle, "Selected", Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp), tint = cs.primary)
    }
}

// ---- mini illustrations (drawn shapes, no images) ---------------------------------------------------------------

private fun DrawScope.rr(c: Color, x: Float, y: Float, w: Float, h: Float, r: Float) =
    drawRoundRect(c, Offset(size.width * x, size.height * y), Size(size.width * w, size.height * h), CornerRadius(r, r))

@Composable
private fun BoxScope.ThemeSwatch(m: ThemeMode) {
    val (bg, card, ink) = when (m) {
        ThemeMode.LIGHT -> Triple(MsLight.Canvas, MsLight.Surface3, MsLight.Accent)
        ThemeMode.PURE_BLACK -> Triple(Color.Black, Color(0xFF1C1C20), Ms.Accent)
        else -> Triple(Ms.Canvas, Ms.Surface3, Ms.Accent)
    }
    Canvas(Modifier.fillMaxSize()) {
        drawRect(bg)
        if (m == ThemeMode.SYSTEM) drawRect(MsLight.Canvas, Offset(size.width / 2f, 0f), Size(size.width / 2f, size.height))
        rr(card, 0.12f, 0.2f, 0.76f, 0.24f, 12f)
        rr(card, 0.12f, 0.56f, 0.5f, 0.16f, 10f)
        drawCircle(ink, size.height * 0.1f, Offset(size.width * 0.8f, size.height * 0.64f))
    }
}

@Composable
private fun BoxScope.GlassMini(g: GlassStyle) {
    Canvas(Modifier.fillMaxSize()) {
        drawRect(ArtGradient)
        drawCircle(ArtAmber, size.height * 0.35f, Offset(size.width * 0.25f, size.height * 0.7f))
        val film = when (g) {
            GlassStyle.LIQUID -> Color.White.copy(alpha = 0.28f)
            GlassStyle.FROSTED -> Color.White.copy(alpha = 0.5f)
            GlassStyle.WATER -> Color(0xFF7FD6F5).copy(alpha = 0.22f)
            GlassStyle.SOLID -> Color(0xFF2B282F)
        }
        rr(film, 0.15f, 0.28f, 0.7f, 0.44f, 22f)
        if (g != GlassStyle.SOLID) drawRoundRect(Color.White.copy(alpha = if (g == GlassStyle.LIQUID) 0.8f else 0.45f),
            Offset(size.width * 0.15f, size.height * 0.28f), Size(size.width * 0.7f, size.height * 0.44f), CornerRadius(22f, 22f), style = Stroke(2.5f))
    }
}

@Composable
private fun BoxScope.PlayerMini(p: PlayerStyle) {
    val accent = MaterialTheme.colorScheme.primary
    val ink = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    Canvas(Modifier.fillMaxSize()) {
        when (p) {
            PlayerStyle.CLASSIC -> { rr(ArtPink, 0.36f, 0.1f, 0.28f, 0.5f, 10f); rr(ink, 0.25f, 0.7f, 0.5f, 0.06f, 4f); drawCircle(accent, size.height * 0.08f, Offset(size.width / 2f, size.height * 0.87f)) }
            PlayerStyle.AURA -> { drawRect(Brush.verticalGradient(listOf(ArtViolet, ArtPink))); rr(Color.White.copy(alpha = 0.35f), 0.15f, 0.58f, 0.7f, 0.32f, 18f) }
            PlayerStyle.VINYL -> { // v2.0.17: Deck – mini cassette card (art left, two reels right)
                rr(ink.copy(alpha = 0.25f), 0.08f, 0.22f, 0.84f, 0.5f, 14f); rr(ArtPink, 0.13f, 0.28f, 0.34f, 0.38f, 8f)
                val r = size.height * 0.09f; val cy = size.height * 0.44f
                drawCircle(accent, r, Offset(size.width * 0.62f, cy), style = Stroke(2f)); drawCircle(accent, r, Offset(size.width * 0.80f, cy), style = Stroke(2f))
                rr(ink, 0.25f, 0.82f, 0.5f, 0.05f, 4f)
            }
            PlayerStyle.MINIMAL -> { // v2.0.16: poster mini (big art fading down, title, ringed play)
                drawRect(Brush.verticalGradient(listOf(ArtPink, ArtViolet, Color.Transparent)), size = Size(size.width, size.height * 0.58f))
                rr(Color.White.copy(alpha = 0.85f), 0.1f, 0.44f, 0.6f, 0.08f, 3f)
                val c = Offset(size.width / 2f, size.height * 0.8f)
                drawCircle(ink, size.height * 0.12f, c, style = Stroke(2f)); drawCircle(accent, size.height * 0.08f, c)
            }
        }
    }
}

@Composable
private fun BoxScope.DockMini(d: DockStyle) {
    val bar = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    val accent = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxSize()) {
        when (d) {
            DockStyle.CLASSIC -> { rr(bar, 0.06f, 0.42f, 0.88f, 0.18f, 10f); rr(bar, 0f, 0.7f, 1f, 0.3f, 0f) }
            DockStyle.ISLAND -> rr(bar, 0.18f, 0.56f, 0.64f, 0.3f, 60f)
            DockStyle.CAPSULE -> { rr(bar, 0.06f, 0.6f, 0.62f, 0.26f, 60f); drawCircle(accent, size.height * 0.19f, Offset(size.width * 0.84f, size.height * 0.73f)) } // v2.0.16: Orb
            DockStyle.EDGE -> { rr(bar, 0.04f, 0.56f, 0.54f, 0.32f, 60f); rr(bar, 0.62f, 0.56f, 0.34f, 0.32f, 60f); drawCircle(accent, size.height * 0.1f, Offset(size.width * 0.70f, size.height * 0.72f)) } // v2.0.17: Split
        }
    }
}

@Composable
private fun BoxScope.HomeMini(h: HomeStyle) {
    val tile = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    Canvas(Modifier.fillMaxSize()) {
        when (h) {
            HomeStyle.CLASSIC -> for (i in 0..2) { rr(ArtTeal, 0.1f, 0.12f + i * 0.28f, 0.14f, 0.2f, 6f); rr(tile, 0.3f, 0.16f + i * 0.28f, 0.55f, 0.1f, 4f) }
            HomeStyle.AURA -> { rr(ArtViolet, 0.08f, 0.1f, 0.84f, 0.46f, 14f); for (i in 0..2) rr(tile, 0.08f + i * 0.29f, 0.64f, 0.26f, 0.26f, 10f) }
            HomeStyle.BENTO -> { rr(ArtPink, 0.08f, 0.1f, 0.4f, 0.8f, 10f); rr(tile, 0.52f, 0.1f, 0.4f, 0.37f, 10f); rr(ArtAmber, 0.52f, 0.53f, 0.4f, 0.37f, 10f) }
            HomeStyle.SPOTLIGHT -> { rr(ArtGradient, 0.08f, 0.08f, 0.84f, 0.62f, 12f); rr(tile, 0.08f, 0.78f, 0.5f, 0.1f, 4f) }
        }
    }
}

private fun DrawScope.rr(b: Brush, x: Float, y: Float, w: Float, h: Float, r: Float) =
    drawRoundRect(b, Offset(size.width * x, size.height * y), Size(size.width * w, size.height * h), CornerRadius(r, r))

// ---- live phone preview -----------------------------------------------------------------------------------------

/** Colourful "album art" blobs on the theme background: what the preview's (and sample card's) glass samples. */
@Composable
private fun ArtBackdrop(strong: Boolean) {
    val bg = MaterialTheme.colorScheme.background
    Canvas(Modifier.fillMaxSize()) {
        drawRect(if (strong) Brush.linearGradient(listOf(ArtPink, ArtViolet, ArtTeal)) else Brush.verticalGradient(listOf(bg, bg)))
        val a = if (strong) 1f else 0.85f
        drawCircle(ArtPink.copy(alpha = a), size.width * 0.42f, Offset(size.width * 0.15f, size.height * 0.2f))
        drawCircle(ArtViolet.copy(alpha = a), size.width * 0.38f, Offset(size.width * 0.95f, size.height * 0.45f))
        drawCircle(ArtTeal.copy(alpha = a), size.width * 0.4f, Offset(size.width * 0.2f, size.height * 0.78f))
        drawCircle(ArtAmber.copy(alpha = a), size.width * 0.22f, Offset(size.width * 0.75f, size.height * 0.95f))
    }
}

/**
 * v2.0.15: a mini phone that redraws from the current settings. Theme colours come from MaterialTheme (with
 * dynamic colour simulated from a sample artwork seed); glass uses the real Modifier.glass / liquidGlass.
 */
@Composable
private fun PhonePreview(s: AppSettings, playerStep: Boolean, still: Boolean, modifier: Modifier) {
    val dark = isDarkTheme(s.themeMode)
    val base = MaterialTheme.colorScheme
    val scheme = if (s.dynamicColor) softScheme(ArtViolet, dark, s.themeMode == ThemeMode.PURE_BLACK) else base
    MaterialTheme(colorScheme = scheme, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes) {
        val frame = RoundedCornerShape(28.dp)
        Box(modifier.clip(frame).border(3.dp, scheme.outlineVariant, frame).background(scheme.background)) {
            GlassLayer(Modifier.fillMaxSize(), background = scheme.background, overlay = {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val u = maxWidth / 100f
                    val key = if (playerStep) "p:${s.playerStyle}" else "h:${s.homeStyle}:${s.dockStyle}"
                    Crossfade(targetState = key, animationSpec = if (still) tween<Float>(0) else tween<Float>(280), label = "preview") { k ->
                        Box(Modifier.fillMaxSize()) {
                            if (k.startsWith("p:")) PlayerSketch(s.playerStyle, u, still)
                            else { HomeSketch(s.homeStyle, u); DockSketch(s.dockStyle, u) }
                        }
                    }
                }
            }) { ArtBackdrop(strong = playerStep) }
            // speaker notch
            Box(Modifier.align(Alignment.TopCenter).padding(top = 6.dp).size(28.dp, 5.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.6f)))
        }
    }
}

@Composable
private fun Line(u: Dp, w: Float, h: Float = 3f, alpha: Float = 0.7f) =
    Box(Modifier.width(u * w).height(u * h).clip(CircleShape).background(MaterialTheme.colorScheme.onBackground.copy(alpha = alpha)))

@Composable
private fun Art(u: Dp, w: Float, h: Float = w, r: Float = 4f, brush: Brush = ArtGradient) =
    Box(Modifier.width(u * w).height(u * h).clip(RoundedCornerShape(u * r)).background(brush))

@Composable
private fun BoxScope.HomeSketch(h: HomeStyle, u: Dp) {
    val glassTint = MaterialTheme.colorScheme.surfaceContainerHigh
    Column(Modifier.fillMaxSize().padding(start = u * 6, end = u * 6, top = u * 12), verticalArrangement = Arrangement.spacedBy(u * 4)) {
        Line(u, 40f, 5f, 0.9f)
        when (h) {
            HomeStyle.CLASSIC -> repeat(5) { i ->
                Row(Modifier.fillMaxWidth().glass(RoundedCornerShape(u * 4), glassTint).padding(u * 2.5f), verticalAlignment = Alignment.CenterVertically) {
                    Art(u, 14f, brush = Brush.linearGradient(listOf(listOf(ArtPink, ArtViolet, ArtTeal, ArtAmber, ArtPink)[i], ArtViolet)))
                    Spacer(Modifier.width(u * 4))
                    Column(verticalArrangement = Arrangement.spacedBy(u * 2)) { Line(u, 40f); Line(u, 26f, alpha = 0.4f) }
                }
            }
            HomeStyle.AURA -> {
                Box(Modifier.fillMaxWidth().height(u * 60).liquidGlass(RoundedCornerShape(u * 8)).padding(u * 5)) {
                    Art(u, 40f, r = 6f)
                    Column(Modifier.align(Alignment.BottomStart), verticalArrangement = Arrangement.spacedBy(u * 2)) { Line(u, 50f, 4f); Line(u, 30f, alpha = 0.4f) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(u * 3)) {
                    repeat(3) { Box(Modifier.weight(1f).height(u * 34).glass(RoundedCornerShape(u * 6), glassTint).padding(u * 3)) { Art(u, 18f, r = 4f) } }
                }
            }
            HomeStyle.BENTO -> Row(horizontalArrangement = Arrangement.spacedBy(u * 3)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(u * 3)) {
                    Box(Modifier.fillMaxWidth().height(u * 60).glass(RoundedCornerShape(u * 6), glassTint).padding(u * 4)) { Art(u, 30f, r = 5f) }
                    Box(Modifier.fillMaxWidth().height(u * 30).glass(RoundedCornerShape(u * 6), glassTint))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(u * 3)) {
                    Box(Modifier.fillMaxWidth().height(u * 30).glass(RoundedCornerShape(u * 6), glassTint))
                    Box(Modifier.fillMaxWidth().height(u * 60).glass(RoundedCornerShape(u * 6), glassTint).padding(u * 4)) {
                        Art(u, 30f, r = 5f, brush = Brush.linearGradient(listOf(ArtAmber, ArtPink)))
                    }
                }
            }
            HomeStyle.SPOTLIGHT -> {
                Art(u, 88f, 88f, r = 8f)
                Line(u, 60f, 6f, 0.9f)
                Line(u, 36f, alpha = 0.4f)
                Box(Modifier.width(u * 34).height(u * 12).glass(CircleShape, glassTint))
            }
        }
    }
}

@Composable
private fun BoxScope.DockSketch(d: DockStyle, u: Dp) {
    val tint = MaterialTheme.colorScheme.surfaceContainerHigh
    val icons = listOf(Icons.Rounded.Home, Icons.Rounded.Search, Icons.Rounded.LibraryMusic, Icons.Rounded.Settings)
    @Composable fun IconsRow(m: Modifier, list: List<ImageVector>) = Row(m, horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        list.forEachIndexed { i, ic -> Icon(ic, null, Modifier.size(u * 8), tint = if (i == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    @Composable fun Mini(m: Modifier) = Row(m.glass(RoundedCornerShape(u * 5), tint, strong = true).padding(u * 2.5f), verticalAlignment = Alignment.CenterVertically) {
        Art(u, 12f, r = 3f); Spacer(Modifier.width(u * 3)); Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(u * 1.5f)) { Line(u, 30f); Line(u, 18f, alpha = 0.4f) }
        Icon(Icons.Rounded.PlayArrow, null, Modifier.size(u * 9))
    }
    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
        when (d) {
            DockStyle.CLASSIC -> {
                Mini(Modifier.padding(horizontal = u * 4).fillMaxWidth())
                Spacer(Modifier.height(u * 2))
                IconsRow(Modifier.fillMaxWidth().height(u * 16).glass(RoundedCornerShape(0.dp), tint, strong = true), icons)
            }
            DockStyle.ISLAND -> {
                Row(Modifier.padding(start = u * 10, end = u * 10, bottom = u * 6).fillMaxWidth().height(u * 16).liquidGlass(CircleShape).padding(horizontal = u * 3),
                    verticalAlignment = Alignment.CenterVertically) {
                    Art(u, 10f, r = 5f)
                    IconsRow(Modifier.weight(1f), icons.take(3))
                    Icon(Icons.Rounded.PlayArrow, null, Modifier.size(u * 8))
                }
            }
            DockStyle.CAPSULE -> { // v2.0.16: Orb — glass tab pill + floating cover orb at bottom-right
                Row(Modifier.padding(start = u * 6, end = u * 6, bottom = u * 6).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(u * 3), verticalAlignment = Alignment.CenterVertically) {
                    IconsRow(Modifier.weight(1f).height(u * 15).glass(CircleShape, tint, strong = true), icons.take(3))
                    Box(Modifier.size(u * 18).glass(CircleShape, tint, strong = true), contentAlignment = Alignment.Center) {
                        Art(u, 14f, r = 7f)
                    }
                }
            }
            DockStyle.EDGE -> {
                // v2.0.17: Split — two glass islands: now playing (ringed round cover) + icon-only nav pill.
                Row(Modifier.padding(start = u * 6, end = u * 6, bottom = u * 6).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(u * 4), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(0.6f).height(u * 16).glass(CircleShape, tint, strong = true).padding(horizontal = u * 2.5f), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(u * 12).border(u * 0.8f, MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) { Art(u, 9f, r = 4.5f) }
                        Spacer(Modifier.width(u * 2)); Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(u * 1.5f)) { Line(u, 20f); Line(u, 12f, alpha = 0.4f) }
                        Icon(Icons.Rounded.PlayArrow, null, Modifier.size(u * 7))
                    }
                    IconsRow(Modifier.weight(0.4f).height(u * 16).glass(CircleShape, tint, strong = true), icons.take(3))
                }
            }
        }
    }
}

@Composable
private fun BoxScope.PlayerSketch(p: PlayerStyle, u: Dp, still: Boolean) {
    val accent = MaterialTheme.colorScheme.primary
    val tint = MaterialTheme.colorScheme.surfaceContainerHigh
    @Composable fun Controls(m: Modifier) = Row(m.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(u * 8).clip(CircleShape).background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)))
        Box(Modifier.size(u * 16).clip(CircleShape).background(accent), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.PlayArrow, null, Modifier.size(u * 10), tint = MaterialTheme.colorScheme.onPrimary)
        }
        Box(Modifier.size(u * 8).clip(CircleShape).background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)))
    }
    @Composable fun Progress() = Box(Modifier.fillMaxWidth().height(u * 2).clip(CircleShape).background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.25f))) {
        Box(Modifier.fillMaxWidth(0.4f).fillMaxHeight().background(accent))
    }
    when (p) {
        PlayerStyle.CLASSIC -> Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background.copy(alpha = 0.82f)).padding(start = u * 8, end = u * 8, top = u * 18),
            verticalArrangement = Arrangement.spacedBy(u * 5), horizontalAlignment = Alignment.CenterHorizontally) {
            Art(u, 84f, r = 6f)
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(u * 2)) { Line(u, 56f, 5f, 0.9f); Line(u, 34f, alpha = 0.45f) }
            Progress()
            Controls(Modifier)
        }
        PlayerStyle.AURA -> Column(Modifier.fillMaxSize().padding(start = u * 7, end = u * 7, top = u * 18, bottom = u * 8), horizontalAlignment = Alignment.CenterHorizontally) {
            Art(u, 70f, r = 10f, brush = Brush.linearGradient(listOf(ArtAmber, ArtPink, ArtViolet)))
            Spacer(Modifier.weight(1f))
            Column(Modifier.fillMaxWidth().liquidGlass(RoundedCornerShape(u * 10), tint).padding(u * 6), verticalArrangement = Arrangement.spacedBy(u * 4)) {
                Line(u, 52f, 5f, 0.9f); Line(u, 30f, alpha = 0.45f); Progress(); Controls(Modifier)
            }
        }
        PlayerStyle.VINYL -> { // v2.0.17: Deck – song-tint gradient, mini cassette card, slim bar
            Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(ArtPink.copy(alpha = 0.45f), MaterialTheme.colorScheme.background))).padding(start = u * 8, end = u * 8, top = u * 18),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(u * 5)) {
                val ink = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f)
                Canvas(Modifier.fillMaxWidth().height(u * 34)) {
                    drawRoundRect(ink.copy(alpha = 0.2f), Offset.Zero, size, CornerRadius(u.toPx() * 6, u.toPx() * 6))
                    val p = size.height * 0.1f; val side = size.height - p * 2
                    drawRoundRect(Brush.linearGradient(listOf(ArtPink, ArtViolet)), Offset(p, p), Size(side, side), CornerRadius(u.toPx() * 4, u.toPx() * 4))
                    val r = side * 0.2f; val cy = size.height * 0.45f
                    drawCircle(accent, r, Offset(size.width * 0.64f, cy), style = Stroke(2f)); drawCircle(accent, r, Offset(size.width * 0.84f, cy), style = Stroke(2f))
                    drawRoundRect(ink, Offset(size.width * 0.58f, size.height * 0.74f), Size(size.width * 0.32f, size.height * 0.1f), CornerRadius(4f, 4f))
                }
                Line(u, 50f, 5f, 0.9f)
                Progress()
                Controls(Modifier)
            }
        }
        PlayerStyle.MINIMAL -> Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { // v2.0.16: poster sketch
            Box(Modifier.fillMaxWidth().weight(0.58f).background(Brush.verticalGradient(listOf(ArtAmber, ArtPink, MaterialTheme.colorScheme.background))),
                contentAlignment = Alignment.BottomStart) {
                Column(Modifier.padding(start = u * 8, bottom = u * 3), verticalArrangement = Arrangement.spacedBy(u * 2)) {
                    val big = with(LocalDensity.current) { (u * 14).toSp() }
                    Text("Big title", fontSize = big, lineHeight = big, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onBackground, textAlign = TextAlign.Start)
                    Line(u, 30f, alpha = 0.45f)
                }
            }
            Column(Modifier.fillMaxWidth().weight(0.42f).padding(horizontal = u * 8), verticalArrangement = Arrangement.SpaceEvenly, horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(u * 26), contentAlignment = Alignment.Center) {
                    val track = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.2f)
                    Canvas(Modifier.fillMaxSize()) {
                        val sw = size.minDimension * 0.07f
                        val tl = Offset(sw / 2f, sw / 2f); val sz = Size(size.width - sw, size.height - sw)
                        drawArc(track, 0f, 360f, false, tl, sz, style = Stroke(sw))
                        drawArc(accent, -90f, 140f, false, tl, sz, style = Stroke(sw, cap = androidx.compose.ui.graphics.StrokeCap.Round))
                    }
                    Box(Modifier.size(u * 17).clip(CircleShape).background(accent), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.PlayArrow, null, Modifier.size(u * 10), tint = MaterialTheme.colorScheme.onPrimary)
                    }
                }
                Line(u, 50f, alpha = 0.3f)
            }
        }
    }
}
