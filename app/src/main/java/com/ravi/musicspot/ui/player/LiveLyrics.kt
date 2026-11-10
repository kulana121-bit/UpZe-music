// 1.0.8: "Motion Lyrics" mode (internally liveLyrics) for Now Playing: kinetic, beat-reactive lyrics that move around the player screen.
package com.ravi.musicspot.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ravi.musicspot.Graph
import com.ravi.musicspot.lyrics.LyricLine
import com.ravi.musicspot.lyrics.Lyrics
import com.ravi.musicspot.lyrics.activeIndex
import com.ravi.musicspot.lyrics.instrumentalGaps
import com.ravi.musicspot.lyrics.introGap
import com.ravi.musicspot.playback.BeatMeter
import com.ravi.musicspot.ui.motion.MotionSpec
import com.ravi.musicspot.ui.motion.reducedMotion
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * 1.0.8 Live lyrics. Replaces the artwork on Now Playing (player ⋯ › Live lyrics) when the song has synced lyrics:
 *
 *  - each line lands somewhere new on the stage (centre, upper left, lower right, a tilted block...), zooming in as the
 *    previous one zooms through and fades, like a lyric video;
 *  - every word pops in exactly when it is sung (overshoot spring), the word being sung glows in the song colour;
 *  - the block breathes with the music: it kicks on each beat, rings ripple out from it, particles drift faster when
 *    the song is louder; the accent colour leans warm or bright with the song's tone (bass vs treble);
 *  - instrumental breaks show pulsing dots, and the next line waits faintly at the bottom.
 *
 * The beat comes from [BeatMeter] (the sound engine's own audio, no permission needed). In Pure hi-res mode, where
 * the engine is bypassed, the word onsets drive the pulse instead, so the stage still moves with the singing.
 *
 * Cost: everything that moves is read in graphicsLayer / draw lambdas from a handful of float states written once per
 * frame by one loop; composition only happens when the line changes. No blur, no shaders, ~40 primitives per frame.
 * Lite devices get fewer particles and no glow; Reduce motion keeps the words but drops the motion.
 *
 * [fallback] is shown while lyrics load and for songs without synced lyrics (the normal artwork).
 */
@Composable
internal fun LiveLyricsStage(modifier: Modifier, onOpenLyrics: () -> Unit, onActive: (Boolean) -> Unit = {}, onVideo: Boolean = false, fallback: @Composable () -> Unit) {
    val st by Graph.player.state.collectAsState()
    val t = st.current
    val lyrics by produceState<Lyrics?>(null, t?.id) { value = null; value = t?.let { runCatching { Graph.lyricsFor(it, false) }.getOrNull() } }
    val synced = (lyrics as? Lyrics.Synced)?.takeIf { it.lines.isNotEmpty() && t?.source?.isLive != true }
    // 1.0.8 (user): while Motion Lyrics play, the artwork moves BEHIND them: the player background becomes the
    // artwork full-screen, blurred ([onActive] tells the player), and the sharp cover fades out of the way. The cover
    // stays composed (invisible), so its swipe / double-tap gestures and the open/close hero still work. Songs
    // without synced lyrics keep the normal sharp cover. [onVideo] (a Spotify Canvas is playing behind): the video
    // stays clear, so the stage draws no scrim and no glow, fewer particles, and the words get a soft shadow instead.
    val active = synced != null
    DisposableEffect(active) { onActive(active); onDispose { onActive(false) } }
    val coverA by animateFloatAsState(if (active) 0f else 1f, tween(if (reducedMotion()) 0 else 420), label = "liveCoverA")
    Box(modifier) {
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = coverA; val s = 0.9f + 0.1f * coverA; scaleX = s; scaleY = s }) { fallback() }
        Crossfade(synced, Modifier.fillMaxSize(), animationSpec = tween(320), label = "liveStage") { l ->
            if (l != null) LiveStage(l, t?.title.orEmpty(), st.playing, onOpenLyrics, overArt = true, onVideo = onVideo)
            else Box(Modifier.fillMaxSize())
        }
    }
}

/** The frame-driven values the stage draws from (one writer: the loop in [rememberLiveFx]). */
@Stable
internal class LiveFx {
    /** Seconds since the stage appeared (ambient drift). */
    val time = mutableFloatStateOf(0f)
    /** Beat envelope 0..1: jumps to 1 on a beat (or a sung word), decays in ~0.2 s. */
    val beat = mutableFloatStateOf(0f)
    /** Loudness 0..1 relative to the song's recent peak. */
    val energy = mutableFloatStateOf(0.4f)
    /** Tone 0 (warm, bassy) .. 1 (bright, trebly), slowly smoothed. */
    val tone = mutableFloatStateOf(0.4f)
    /** Ring ripples: birth times (in [time] seconds) of the last few beats; -100 = empty slot. */
    val rings = FloatArray(6) { -100f }
    var ringHead = 0
}

@Composable
private fun rememberLiveFx(pos: State<Long>, l: Lyrics.Synced, offsetMs: Int, playing: Boolean, still: Boolean, light: Boolean = false): LiveFx {
    val fx = remember { LiveFx() }
    DisposableEffect(Unit) { BeatMeter.wanted = true; onDispose { BeatMeter.wanted = false } }
    LaunchedEffect(l, offsetMs, playing, still, light) {
        // 1.0.8 load: the stage animates at most ~60 fps (30 on lite devices / over a Canvas video) even on 90/120 Hz
        // screens; frames in between are skipped without touching any state, so nothing redraws for them.
        val minFrame = if (light) 32_000_000L else 15_500_000L
        val reading = BeatMeter.Reading()
        var last = System.nanoTime()
        var bassAvg = 0f
        var levelPeak = 0.05f
        var lastBeatNanos = 0L
        var lastWordStart = Long.MIN_VALUE
        while (true) {
            withFrameNanos { }
            val now = System.nanoTime()
            if (now - last < minFrame) continue
            val dt = ((now - last) / 1e9f).coerceIn(0f, 0.1f)
            last = now
            fx.time.floatValue += dt
            var kick = 0f
            if (playing && BeatMeter.live) {
                BeatMeter.at(now, reading)
                bassAvg += (reading.bass - bassAvg) * (1f - exp(-dt / 0.35f))
                levelPeak = maxOf(levelPeak * (1f - dt * 0.08f), reading.level, 0.02f)
                val e = (reading.level / levelPeak).coerceIn(0f, 1f)
                fx.energy.floatValue += (e - fx.energy.floatValue) * (1f - exp(-dt / 0.25f))
                val toneNow = (reading.treble / (reading.bass + reading.treble + 1e-4f)).coerceIn(0f, 1f)
                fx.tone.floatValue += (toneNow - fx.tone.floatValue) * (1f - exp(-dt / 2.5f))
                if (reading.bass > bassAvg * 1.32f + 0.012f && now - lastBeatNanos > 240_000_000L) {
                    lastBeatNanos = now; kick = 1f
                }
            } else if (playing) {
                // Pure hi-res (engine bypassed): a gentle synthetic energy, the words below give the kicks.
                val target = 0.45f + 0.15f * sin(fx.time.floatValue * 1.7f)
                fx.energy.floatValue += (target - fx.energy.floatValue) * (1f - exp(-dt / 0.5f))
            } else {
                fx.energy.floatValue *= exp(-dt / 0.6f)
            }
            if (playing) {
                // A sung word is an accent too (and the only one when there's no audio reading).
                val p = pos.value + offsetMs
                val li = l.lines.activeIndex(p)
                val line = l.lines.getOrNull(li)
                if (line != null) {
                    var ws = Long.MIN_VALUE
                    for (w in line.words) { if (w.startMs <= p) ws = w.startMs else break }
                    if (ws != Long.MIN_VALUE && ws != lastWordStart) {
                        lastWordStart = ws
                        val strength = if (BeatMeter.live) 0.45f else 0.85f
                        if (strength > kick) kick = maxOf(kick, strength)
                    }
                }
            }
            val decayed = fx.beat.floatValue * exp(-dt / 0.19f)
            fx.beat.floatValue = if (still) 0f else maxOf(decayed, kick)
            if (kick >= 0.85f && !still) { fx.rings[fx.ringHead] = fx.time.floatValue; fx.ringHead = (fx.ringHead + 1) % fx.rings.size }
            if (!playing && fx.beat.floatValue < 0.001f && fx.energy.floatValue < 0.02f) break // paused and settled: stop the loop
        }
    }
    return fx
}

/** Where (and how) a line sits on the stage; chosen per line so consecutive lines land in different places. */
private enum class LiveLayout(val bx: Float, val by: Float, val tilt: Float, val align: Int, val enterDx: Float, val enterDy: Float) {
    CENTER(0f, -0.08f, 0f, 0, 0f, 34f),
    UPPER_LEFT(-0.7f, -0.62f, -6f, -1, -40f, 0f),
    LOWER_RIGHT(0.7f, 0.55f, 5f, 1, 40f, 0f),
    STAGE(0f, 0.12f, 0f, 0, 0f, -30f),
    UPPER_RIGHT(0.6f, -0.5f, 4f, 1, 0f, 30f),
    LOWER_LEFT(-0.6f, 0.45f, -4f, -1, 0f, -30f),
}

private fun layoutFor(index: Int, seed: Int): LiveLayout {
    val all = LiveLayout.entries
    // Never the same spot twice in a row: step by a seed-dependent stride that is coprime with the count.
    val stride = intArrayOf(1, 5)[abs(seed) % 2]
    return all[((index * stride) + abs(seed)).mod(all.size)]
}

@Composable
private fun LiveStage(l: Lyrics.Synced, title: String, playing: Boolean, onOpenLyrics: () -> Unit, overArt: Boolean = false, onVideo: Boolean = false) {
    val settings by Graph.settings.state.collectAsState()
    val offsetMs = settings.lyricsOffsetMs
    val still = reducedMotion()
    val lite = Graph.liteFx
    val pos = rememberSmoothPosition()
    val fx = rememberLiveFx(pos, l, offsetMs, playing, still, lite || onVideo)
    val c = MaterialTheme.colorScheme
    val seed = remember(title) { title.hashCode() }
    val gaps = remember(l) { listOfNotNull(l.lines.introGap()) + l.lines.instrumentalGaps() }
    // -2 = instrumental break; otherwise the active line (-1 before the first line). Changes once per line.
    val shown by remember(l, offsetMs) {
        derivedStateOf {
            val p = pos.value + offsetMs + LYRIC_LINE_LEAD_MS // 1.0.8: the line pops in on the voice, not after it
            if (gaps.any { p in it }) -2 else l.lines.activeIndex(p)
        }
    }
    val next by remember(l, offsetMs) { derivedStateOf { (if (shown >= 0) shown + 1 else l.lines.indexOfFirst { it.startMs > pos.value + offsetMs }).takeIf { it in l.lines.indices } } }
    val accentA = c.primary
    val accentB = c.tertiary
    // Over the artwork the words are always white on a soft scrim, so they read on any cover and in light theme.
    val ink = if (overArt) Color.White else c.onSurface
    val tap = remember { MutableInteractionSource() }
    val artScrim = remember { Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.16f), Color.Transparent)) }
    Box(
        Modifier.fillMaxSize()
            .then(if (overArt) Modifier else Modifier.clickable(tap, null, onClickLabel = "Open lyrics") { onOpenLyrics() })
            .drawBehind {
                if (overArt && !onVideo) drawRect(artScrim) // light: the full-screen blurred art behind already carries its own scrim
                drawLiveBackdrop(fx, accentA, accentB, lite || onVideo, still, glow = if (onVideo) 0f else if (overArt) 0.6f else 1f)
            },
    ) {
        AnimatedContent(shown, label = "liveLine", transitionSpec = {
            if (still) fadeIn(tween(160)) togetherWith fadeOut(tween(120))
            else (fadeIn(tween(240, delayMillis = 40)) + scaleIn(tween(420, easing = MotionSpec.emphasizedDecelerate), initialScale = 0.72f)) togetherWith
                (fadeOut(tween(200)) + scaleOut(tween(320, easing = MotionSpec.emphasizedAccelerate), targetScale = 1.28f))
        }, modifier = Modifier.fillMaxSize()) { idx ->
            val line = l.lines.getOrNull(idx)
            when {
                idx == -2 -> BreakDots(fx, accentA, still)
                line == null || line.text.isBlank() -> BreakDots(fx, accentA, still)
                else -> LiveLine(line, idx, layoutFor(idx, seed), pos, offsetMs, fx, ink, accentA, accentB, still, lite, shadowed = onVideo)
            }
        }
        // The next line waits faintly at the bottom.
        val upcoming = next?.let { l.lines.getOrNull(it)?.text }?.takeIf { it.isNotBlank() }
        if (upcoming != null) Crossfade(upcoming, Modifier.align(Alignment.BottomCenter).padding(horizontal = 28.dp, vertical = 6.dp), animationSpec = tween(260), label = "liveNext") { s ->
            Text(s, style = if (onVideo) MaterialTheme.typography.titleSmall.copy(shadow = WordShadow) else MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold, color = ink.copy(alpha = if (onVideo) 0.6f else 0.38f),
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** Glow, beat rings and drifting particles behind the lyrics (draw phase only). */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLiveBackdrop(fx: LiveFx, a: Color, b: Color, lite: Boolean, still: Boolean, glow: Float = 1f) {
    val w = size.width; val h = size.height
    if (w <= 0f || h <= 0f) return
    val time = fx.time.floatValue
    val beat = fx.beat.floatValue
    val energy = fx.energy.floatValue
    val accent = lerp(a, b, fx.tone.floatValue)
    val centre = Offset(w * (0.5f + 0.06f * sin(time * 0.31f)), h * (0.46f + 0.05f * cos(time * 0.27f)))
    // Soft glow that swells with the beat.
    val r = minOf(w, h) * (0.42f + 0.10f * beat + 0.08f * energy)
    drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = (0.22f + 0.22f * beat) * glow), Color.Transparent), centre, r), r, centre)
    if (still) return
    // Ripples on each beat.
    val maxR = maxOf(w, h) * 0.6f
    for (born in fx.rings) {
        val age = time - born
        if (age < 0f || age > 1.1f) continue
        val k = age / 1.1f
        drawCircle(accent.copy(alpha = 0.32f * (1f - k)), maxR * (0.12f + 0.88f * k), centre, style = Stroke(width = (3f - 2f * k) * density))
    }
    // Drifting particles: speed and twinkle follow the music.
    val n = if (lite) 10 else 26
    val speed = 0.018f + 0.06f * energy
    for (i in 0 until n) {
        val fxr = fract(sin(i * 12.9898f) * 43758.547f)
        val fyr = fract(sin(i * 78.233f) * 12543.123f)
        val y = fract(fyr - time * speed * (0.6f + fxr)) * h
        val x = (fxr * w + sin(time * (0.4f + fxr) + i) * 18f * density).let { ((it % w) + w) % w }
        val tw = 0.35f + 0.65f * fract(fxr * 7.3f + time * 0.5f)
        val rad = (1.2f + 2.4f * fyr) * density * (1f + 0.6f * beat)
        drawCircle((if (i % 3 == 0) accent else Color.White).copy(alpha = (0.10f + 0.35f * tw) * (0.6f + 0.4f * energy)), rad, Offset(x, y))
    }
}

private fun fract(v: Float): Float = v - kotlin.math.floor(v)

/** 1.0.8: soft shadow that keeps white words readable over a clear Canvas video (one shadow layer, no blur pass). */
private val WordShadow = androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = 0.55f), Offset(0f, 3f), blurRadius = 10f)

/** The instrumental-break pose: three dots that bounce with the beat. */
@Composable
private fun BreakDots(fx: LiveFx, accent: Color, still: Boolean) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(Modifier.padding(24.dp).widthIn(min = 120.dp).graphicsLayer {
            val s = 1f + 0.12f * fx.beat.floatValue; scaleX = s; scaleY = s
        }.drawBehind {
            val gap = 30.dp.toPx()
            val t = fx.time.floatValue
            for (k in 0 until 3) {
                val hop = if (still) 0f else abs(sin(t * 3.2f - k * 0.7f)) * 10.dp.toPx() * (0.4f + 0.6f * fx.energy.floatValue)
                drawCircle(accent.copy(alpha = 0.55f + 0.45f * fx.beat.floatValue), 7.dp.toPx(), Offset(size.width / 2f + (k - 1) * gap, size.height / 2f - hop))
            }
        }.padding(vertical = 30.dp)) { }
    }
}

/**
 * One lyric line on the stage: its words wrap into a block that sits where [layout] says, tilted, gently floating
 * and kicking with the beat; each word pops in on its own start time.
 */
@Composable
private fun LiveLine(
    line: LyricLine, index: Int, layout: LiveLayout, pos: State<Long>, offsetMs: Int, fx: LiveFx,
    ink: Color, accentA: Color, accentB: Color, still: Boolean, lite: Boolean, shadowed: Boolean = false,
) {
    val len = line.text.length
    val fontSize: TextUnit = when {
        len <= 12 -> 46.sp
        len <= 24 -> 38.sp
        len <= 40 -> 31.sp
        len <= 60 -> 26.sp
        else -> 22.sp
    }
    val style = remember(fontSize, shadowed) { TextStyle(fontSize = fontSize, lineHeight = fontSize * 1.12f, fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp, shadow = if (shadowed) WordShadow else null) }
    // Words from the timing (the spread timing of line-synced sources works the same); never an empty block.
    val words = remember(line) {
        val ws = line.words.filter { it.text.isNotBlank() }
        if (ws.isNotEmpty()) ws.map { Triple(it.text.trim(), it.startMs, it.endMs) }
        else listOf(Triple(line.text, line.startMs, line.endMs))
    }
    Box(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 28.dp)) {
        Box(
            Modifier.align(BiasAlignment(if (still) 0f else layout.bx, if (still) 0f else layout.by))
                .fillMaxWidth(0.9f)
                .graphicsLayer {
                    if (still) return@graphicsLayer
                    val t = fx.time.floatValue
                    val beat = fx.beat.floatValue
                    translationX = sin(t * 0.55f + index) * 10.dp.toPx()
                    translationY = cos(t * 0.43f + index * 1.3f) * 8.dp.toPx() - beat * 4.dp.toPx()
                    rotationZ = layout.tilt + sin(t * 0.37f + index) * 1.2f
                    val s = 1f + 0.05f * beat
                    scaleX = s; scaleY = s
                },
        ) {
            WordWrap(align = if (still) 0 else layout.align, hGap = 10.dp, vGap = 2.dp) {
                words.forEach { (text, start, end) ->
                    LiveWord(text, start, end, style, pos, offsetMs, fx, ink, accentA, accentB, layout, still, lite)
                }
            }
        }
    }
}

@Composable
private fun LiveWord(
    text: String, startMs: Long, endMs: Long, style: TextStyle, pos: State<Long>, offsetMs: Int, fx: LiveFx,
    ink: Color, accentA: Color, accentB: Color, layout: LiveLayout, still: Boolean, lite: Boolean,
) {
    val pop = (endMs - startMs).coerceIn(160L, 420L).toFloat()
    Text(
        text, style = style, color = ink, maxLines = 1, softWrap = false,
        modifier = Modifier
            .drawBehind {
                // The word being sung glows in the song's colour (a soft pill behind it, pulsing with the beat).
                if (lite) return@drawBehind
                val p = pos.value + offsetMs
                if (p < startMs || p > endMs + 120) return@drawBehind
                val k = if (p <= endMs) 1f else 1f - (p - endMs) / 120f
                val accent = lerp(accentA, accentB, fx.tone.floatValue)
                val padX = 6.dp.toPx(); val padY = 2.dp.toPx()
                drawRoundRect(
                    accent.copy(alpha = (0.20f + 0.25f * fx.beat.floatValue) * k),
                    topLeft = Offset(-padX, size.height * 0.18f - padY),
                    size = androidx.compose.ui.geometry.Size(size.width + padX * 2, size.height * 0.68f + padY * 2),
                    cornerRadius = CornerRadius(size.height * 0.3f),
                )
            }
            .graphicsLayer {
                val p = pos.value + offsetMs
                val prog = ((p - startMs) / pop).coerceIn(0f, 1f)
                if (still) { alpha = if (p >= startMs) 1f else 0.35f; return@graphicsLayer }
                val e = easeOutBack(prog)
                alpha = 0.16f + 0.84f * (prog * 1.6f).coerceAtMost(1f)
                val s = 0.55f + 0.45f * e + (if (p in startMs..endMs) 0.07f * fx.beat.floatValue else 0f)
                scaleX = s; scaleY = s
                translationX = (1f - e) * layout.enterDx.dp.toPx()
                translationY = (1f - e) * layout.enterDy.dp.toPx()
                rotationZ = (1f - e) * (if (layout.enterDx < 0f) -8f else 8f)
            },
    )
}

/** Overshoot ease (the word lands a little past full size and settles), like a soft spring. */
private fun easeOutBack(x: Float): Float {
    val c1 = 1.70158f; val c3 = c1 + 1f
    val t = x - 1f
    return 1f + c3 * t * t * t + c1 * t * t
}

/** Lays words out in rows that wrap, each row aligned left (-1), centred (0) or right (1). */
@Composable
private fun WordWrap(align: Int, hGap: androidx.compose.ui.unit.Dp, vGap: androidx.compose.ui.unit.Dp, content: @Composable () -> Unit) {
    Layout(content) { measurables, constraints ->
        val maxW = if (constraints.hasBoundedWidth) constraints.maxWidth else Int.MAX_VALUE
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val gapX = hGap.roundToPx(); val gapY = vGap.roundToPx()
        val rows = ArrayList<IntRange>()
        var rowStart = 0; var rowW = 0
        placeables.forEachIndexed { i, pl ->
            val add = if (i == rowStart) pl.width else gapX + pl.width
            if (i > rowStart && rowW + add > maxW) { rows += rowStart until i; rowStart = i; rowW = pl.width }
            else rowW += add
        }
        if (placeables.isNotEmpty()) rows += rowStart until placeables.size
        val rowWidths = rows.map { r -> r.sumOf { placeables[it].width } + gapX * (r.count() - 1).coerceAtLeast(0) }
        val rowHeights = rows.map { r -> r.maxOfOrNull { placeables[it].height } ?: 0 }
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else (rowWidths.maxOrNull() ?: 0)
        val height = (rowHeights.sum() + gapY * (rows.size - 1).coerceAtLeast(0)).coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(width, height) {
            var y = 0
            rows.forEachIndexed { ri, r ->
                var x = when (align) { -1 -> 0; 1 -> width - rowWidths[ri]; else -> (width - rowWidths[ri]) / 2 }.coerceAtLeast(0)
                for (i in r) {
                    val pl = placeables[i]
                    pl.place(x, y + (rowHeights[ri] - pl.height) / 2)
                    x += pl.width + gapX
                }
                y += rowHeights[ri] + gapY
            }
        }
    }
}

@Suppress("unused") private val TAU = (2 * PI).toFloat()
