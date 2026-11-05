// v2.0.17: "Deck (cassette)" player – replaces the Wave design. File / entry name kept so AppRoot dispatch is unchanged.
package com.ravi.musicspot.ui.player

import android.content.res.Configuration
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ravi.musicspot.Graph
import com.ravi.musicspot.model.Track
import com.ravi.musicspot.playback.EngineAudioProcessor
import com.ravi.musicspot.playback.Playback
import com.ravi.musicspot.ui.Menus
import com.ravi.musicspot.ui.Route
import com.ravi.musicspot.ui.components.Artwork
import com.ravi.musicspot.ui.components.formatTime
import com.ravi.musicspot.ui.glass.LocalBackdrop
import com.ravi.musicspot.ui.glass.LocalHazeState
import com.ravi.musicspot.ui.glass.glass
import com.ravi.musicspot.ui.motion.MotionSpec
import com.ravi.musicspot.ui.motion.PlayPauseIcon
import com.ravi.musicspot.ui.motion.iconSwap
import com.ravi.musicspot.ui.motion.pressScale
import com.ravi.musicspot.ui.motion.reducedMotion
import com.ravi.musicspot.ui.motion.rememberHaptics
import com.ravi.musicspot.ui.motion.rememberLoopPhase
import com.ravi.musicspot.ui.theme.LocalSongTint
import com.ravi.musicspot.ui.theme.readable
import com.ravi.musicspot.ui.theme.readableOn
import kotlinx.coroutines.launch
import androidx.media3.common.Player as M3

// v2.0.17: Deck shapes.
private val DeckCard = RoundedCornerShape(24.dp)
private val DeckArt = RoundedCornerShape(16.dp)
private val DeckKey = RoundedCornerShape(18.dp)
private val DeckKeyBig = RoundedCornerShape(24.dp)
private val DeckKeySmall = RoundedCornerShape(14.dp)
private val DeckPill = RoundedCornerShape(percent = 50)

/**
 * v2.0.17: "Deck (cassette)" Now Playing (Settings → Appearance → Now Playing design). Same features as the other
 * players (panes, sleep, sound, like, shuffle/repeat, swipe-down collapse, cover morph):
 *  - soft vertical gradient from the song colour (LocalSongTint, fallback primary) down to the background;
 *  - a centred glass "deck" card (24dp corners): square artwork on the left half, two spinning reels and a tape
 *    window on the right (reels turn only while playing, frozen with Reduce motion); swipe the card for next/previous;
 *  - title / artist + like, a slim straight seek bar with a pill thumb, big rounded-square transport keys;
 *  - the bottom row of pane buttons (Up next, Lyrics, Related, Credits) plus Sleep and Sound.
 * Signature unchanged so AppRoot's dispatch compiles as before.
 */
@Composable
fun VinylPlayerScreen(
    progress: () -> Float,
    onCollapse: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: (Float) -> Unit,
    coverVisible: Boolean,
    onCoverBounds: (Rect) -> Unit,
    onOpenEq: () -> Unit,
    onNavigate: (Route) -> Unit,
) {
    val st by Graph.player.state.collectAsState()
    val t = st.current ?: return
    var pane by rememberSaveable { mutableStateOf<Pane?>(null) }
    // 1.0.1: last full-screen page (Queue/Lyrics), reopened by a swipe up.
    var lastFull by rememberSaveable { mutableStateOf(Pane.QUEUE) }
    androidx.compose.runtime.LaunchedEffect(pane) { pane?.let { if (it.isFull()) lastFull = it } }
    var sleepSheet by remember { mutableStateOf(false) }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val still = reducedMotion()
    androidx.activity.compose.BackHandler(enabled = true) { if (pane != null) pane = null else onCollapse() }

    // v2.0.17: song colour from the theme (fallback primary), made readable on the background for the accent.
    val base = MaterialTheme.colorScheme
    val songTint = LocalSongTint.current ?: base.primary
    val tint by animateColorAsState(songTint, if (still) snap() else tween(1000), label = "deckTint")
    val accent = readable(tint, base.background, 3.0)
    val scheme = base.copy(primary = accent, onPrimary = readableOn(accent))

    MaterialTheme(colorScheme = scheme) {
        // v2.0.17: no backdrop sampling inside the player (glass falls back to tinted film), as in v2.0.16.
        CompositionLocalProvider(LocalContentColor provides scheme.onBackground, LocalBackdrop provides null, LocalHazeState provides null) {
            Box(Modifier.fillMaxSize().background(scheme.background)
                .background(Brush.verticalGradient(0f to lerp(scheme.background, tint, 0.55f), 0.45f to lerp(scheme.background, tint, 0.18f), 0.8f to scheme.background))) {
                Column(
                    Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
                        .playerVerticalGestures(enabled = pane == null, onDrag = onDrag, onDragEnd = onDragEnd, onSwipeUp = { pane = lastFull }), // 1.0.1: swipe up = full-screen pane
                ) {
                    DeckTopBar(onCollapse, onMore = { Menus.track = t })
                    if (landscape) {
                        Row(Modifier.weight(1f).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                            DeckCassette(t, coverVisible, onCoverBounds, Modifier.fillMaxHeight().weight(1.1f))
                            Spacer(Modifier.width(20.dp))
                            AnimatedContent(pane.inPlayer(), transitionSpec = { MotionSpec.fadeThrough(still) }, label = "deckLand", modifier = Modifier.weight(1f).fillMaxHeight()) { p ->
                                if (p == null) Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
                                    DeckControls(t, onCollapse, onNavigate)
                                } else DeckPaneCard(p, t, onCollapse, onNavigate, Modifier.fillMaxSize())
                            }
                        }
                    } else {
                        AnimatedContent(pane.inPlayer(), transitionSpec = {
                            when {
                                initialState == null && targetState != null -> MotionSpec.containerRise(open = true, still = still)
                                initialState != null && targetState == null -> MotionSpec.containerRise(open = false, still = still)
                                else -> MotionSpec.fadeThrough(still)
                            }
                        }, label = "deckPane", modifier = Modifier.weight(1f)) { p ->
                            if (p == null) Column(Modifier.fillMaxSize()) {
                                DeckCassette(t, coverVisible, onCoverBounds, Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp))
                                DeckControls(t, onCollapse, onNavigate)
                                Spacer(Modifier.height(8.dp))
                            } else Column(Modifier.fillMaxSize()) {
                                DeckMiniHeader(onClose = { pane = null })
                                DeckPaneCard(p, t, onCollapse, onNavigate, Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp))
                            }
                        }
                    }
                    DeckPaneRow(pane, onSelect = { pane = if (pane == it) null else it }, onOpenSleep = { sleepSheet = true }, onOpenEq = onOpenEq)
                }
            }
            // 1.0.1: Queue / Lyrics open full screen over the player (pager + tabs, swipe down or Back to close).
            FullScreenPane(pane, onSelect = { pane = it }, onClose = { pane = null })
            SleepSheet(sleepSheet) { sleepSheet = false }
        }
    }
}

@Composable
private fun DeckTopBar(onCollapse: () -> Unit, onMore: () -> Unit) {
    val stream by Playback.stream.collectAsState()
    val still = reducedMotion()
    val ink = MaterialTheme.colorScheme.onBackground
    val colI = remember { MutableInteractionSource() }
    val moreI = remember { MutableInteractionSource() }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).pressScale(colI, 0.86f).clip(DeckKeySmall).background(ink.copy(alpha = 0.08f))
            .clickable(colI, null, onClickLabel = "Collapse player") { onCollapse() }, contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.KeyboardArrowDown, "Collapse player")
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("NOW PLAYING", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            AnimatedContent(stream?.info?.source?.label ?: "Finding the best source…", transitionSpec = { MotionSpec.fadeThrough(still) }, label = "deckSrc") {
                Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
        Box(Modifier.size(44.dp).pressScale(moreI, 0.86f).clip(DeckKeySmall).background(ink.copy(alpha = 0.08f))
            .clickable(moreI, null, onClickLabel = "More options") { onMore() }, contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.MoreHoriz, "More options")
        }
    }
}

/**
 * v2.0.17: the glass deck card – artwork (left half, square) + reels and tape window (right half). Swipe the card
 * left/right to skip, double-tap to like, long-press for options. The artwork reports its bounds for the cover
 * morph and hides while morphing. Only this card is glass; nothing inside it is.
 */
@Composable
private fun DeckCassette(t: Track, visible: Boolean, onBounds: (Rect) -> Unit, modifier: Modifier) {
    val still = reducedMotion()
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val dx = remember { Animatable(0f) }
    val heart = remember { Animatable(0f) }
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val pad = 12.dp
        val gap = 12.dp
        val artSide: Dp = minOf((maxWidth - pad * 2 - gap) / 2, maxHeight - pad * 2).coerceAtLeast(0.dp)
        val cardW = artSide * 2 + pad * 2 + gap
        val cardH = artSide + pad * 2
        val cardWPx = with(LocalDensity.current) { cardW.toPx() }
        Box(
            Modifier.size(cardW, cardH)
                .pointerInput(cardWPx) { // v2.0.17: keyed on size; guard /0
                    val w = cardWPx.coerceAtLeast(1f)
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            val v = dx.value
                            scope.launch {
                                if (kotlin.math.abs(v) > w * 0.25f) {
                                    haptics.click()
                                    if (v < 0) Graph.player.next() else Graph.player.previous()
                                }
                                if (still) dx.snapTo(0f) else dx.animateTo(0f, spring(dampingRatio = 0.75f, stiffness = 160f))
                            }
                        },
                        onDragCancel = { scope.launch { dx.snapTo(0f) } },
                    ) { ch, amount -> ch.consume(); scope.launch { dx.snapTo((dx.value + amount).coerceIn(-w, w)) } }
                }
                .pointerInput(t.id) {
                    detectTapGestures(
                        onDoubleTap = {
                            haptics.confirm()
                            if (Graph.library.data.value.liked.none { it.id == t.id }) Graph.library.setLiked(t, true)
                            scope.launch { if (!still) { heart.snapTo(0f); heart.animateTo(1f, tween(900)) }; heart.snapTo(0f) }
                        },
                        onLongPress = { haptics.confirm(); Menus.track = t },
                    )
                }
                .graphicsLayer {
                    val w = cardWPx.coerceAtLeast(1f)
                    val f = (dx.value / w).coerceIn(-1f, 1f)
                    translationX = dx.value * 0.6f
                    rotationZ = f * 3f
                    alpha = 1f - kotlin.math.abs(f) * 0.35f
                }
                .glass(DeckCard)
                .clip(DeckCard),
        ) {
            Row(Modifier.fillMaxSize().padding(pad), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(artSide).onGloballyPositioned { onBounds(it.boundsInRoot()) }.graphicsLayer { alpha = if (visible) 1f else 0f },
                    contentAlignment = Alignment.Center) {
                    Artwork(t.artUrl, Modifier.fillMaxSize(), DeckArt, "Album art. Swipe for next or previous, double-tap to like, long-press for options")
                    val h = heart.value
                    if (h > 0f) Icon(Icons.Rounded.Favorite, null, Modifier.size(artSide / 3).graphicsLayer {
                        val pop = if (h < 0.3f) h / 0.3f * 1.2f else 1.2f - 0.2f * ((h - 0.3f) / 0.7f)
                        scaleX = pop; scaleY = pop; alpha = if (h < 0.7f) 1f else 1f - (h - 0.7f) / 0.3f
                    }, tint = Color.White)
                }
                Spacer(Modifier.width(gap))
                Column(Modifier.size(artSide), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DeckReels(Modifier.fillMaxWidth().weight(1f))
                    DeckTapeWindow(Modifier.fillMaxWidth().height((artSide * 0.16f).coerceIn(10.dp, 22.dp)))
                }
            }
        }
    }
}

/** v2.0.17: two reels with spokes; turn with rememberLoopPhase only while playing, frozen with Reduce motion. */
@Composable
private fun DeckReels(modifier: Modifier) {
    val st by Graph.player.state.collectAsState()
    val running = st.playing && !reducedMotion()
    val phase by rememberLoopPhase(running = running, periodMs = 2400, reverse = false) // v2.0.17: called unconditionally
    // v2.0.17: keep the reached angle when the loop stops so the reels freeze in place instead of snapping back.
    val acc = remember { floatArrayOf(0f, 0f) } // [base degrees, last running phase]
    if (running) acc[1] = phase else if (acc[1] != 0f) { acc[0] = (acc[0] + acc[1] * 360f) % 360f; acc[1] = 0f }
    val angle = acc[0] + (if (running) phase else 0f) * 360f
    val ink = MaterialTheme.colorScheme.onSurface
    val accent = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        if (size.width <= 0f || size.height <= 0f) return@Canvas
        val r = minOf(size.width / 4.4f, size.height / 2.2f)
        val cy = size.height / 2f
        val c1 = Offset(size.width * 0.27f, cy)
        val c2 = Offset(size.width * 0.73f, cy)
        drawLine(ink.copy(alpha = 0.35f), Offset(c1.x, cy + r * 0.92f), Offset(c2.x, cy + r * 0.92f), strokeWidth = 2.dp.toPx())
        reel(c1, r, angle, ink, accent)
        reel(c2, r, angle + 30f, ink, accent)
    }
}

private fun DrawScope.reel(c: Offset, r: Float, angle: Float, ink: Color, accent: Color) {
    drawCircle(ink.copy(alpha = 0.10f), r, c)
    drawCircle(ink.copy(alpha = 0.45f), r, c, style = Stroke(1.5.dp.toPx()))
    drawCircle(accent.copy(alpha = 0.85f), r * 0.42f, c)
    rotate(angle, c) {
        for (i in 0 until 6) {
            val a = Math.toRadians(i * 60.0)
            val inner = r * 0.16f; val outer = r * 0.40f
            drawLine(readableOn(accent), Offset(c.x + inner * kotlin.math.cos(a).toFloat(), c.y + inner * kotlin.math.sin(a).toFloat()),
                Offset(c.x + outer * kotlin.math.cos(a).toFloat(), c.y + outer * kotlin.math.sin(a).toFloat()), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
        }
    }
    drawCircle(ink.copy(alpha = 0.55f), r * 0.12f, c)
}

/** v2.0.17: tiny tape window – a dark rounded slot with the tape running across it. */
@Composable
private fun DeckTapeWindow(modifier: Modifier) {
    val ink = MaterialTheme.colorScheme.onSurface
    val accent = MaterialTheme.colorScheme.primary
    Canvas(modifier.clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.28f)).border(1.dp, ink.copy(alpha = 0.12f), RoundedCornerShape(6.dp))) {
        val y = size.height / 2f
        drawLine(accent.copy(alpha = 0.8f), Offset(size.width * 0.08f, y), Offset(size.width * 0.92f, y), strokeWidth = (size.height * 0.22f).coerceAtLeast(1f), cap = StrokeCap.Round)
    }
}

/** v2.0.17: title / artist + like, error line, slim seek bar and the rounded-square transport keys. */
@Composable
private fun DeckControls(t: Track, onCollapse: () -> Unit, onNavigate: (Route) -> Unit) {
    val st by Graph.player.state.collectAsState()
    // position is collected inside DeckSeekBar only.
    val lib by Graph.library.data.collectAsState()
    val err by Playback.error.collectAsState()
    val haptics = rememberHaptics()
    val still = reducedMotion()
    val liked = lib.liked.any { it.id == t.id }
    val primary = MaterialTheme.colorScheme.primary
    val ink = MaterialTheme.colorScheme.onBackground
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AnimatedContent(st.index to t, modifier = Modifier.weight(1f), label = "deckTitle", contentKey = { it.second.id },
                transitionSpec = { MotionSpec.sharedAxisX(forward = targetState.first >= initialState.first, still = still) }) { (_, tt) ->
                Column(Modifier.fillMaxWidth()) {
                    Text(tt.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val aid = tt.artistId
                    Text(tt.artist, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = if (aid != null) Modifier.clickable(onClickLabel = "Go to artist") { onCollapse(); onNavigate(Route.Artist(aid, tt.artist)) } else Modifier)
                }
            }
            val likeI = remember { MutableInteractionSource() }
            val pop by animateFloatAsState(if (liked) 1.12f else 1f, if (still) snap() else spring(dampingRatio = 0.4f, stiffness = 200f), label = "deckLike")
            Box(Modifier.size(46.dp).graphicsLayer { scaleX = pop; scaleY = pop }.pressScale(likeI, 0.85f).clip(DeckKeySmall)
                .background(ink.copy(alpha = 0.06f))
                .clickable(likeI, null, onClickLabel = if (liked) "Unlike" else "Like") { haptics.confirm(); Graph.library.setLiked(t, !liked) }, contentAlignment = Alignment.Center) {
                AnimatedContent(liked, transitionSpec = { iconSwap(still) }, label = "deckLikeSwap") { on ->
                    Icon(if (on) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (on) "Unlike" else "Like", tint = if (on) primary else ink)
                }
            }
        }
        if (err != null) Text("Couldn't play · tap to retry", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 4.dp).clickable(onClickLabel = "Retry") { Graph.player.retry() }, maxLines = 1)
        Spacer(Modifier.height(12.dp))
        DeckSeekBar(st.durationMs)
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            DeckKeyButton(Icons.Rounded.Shuffle, if (st.shuffle) "Shuffle on" else "Shuffle off", active = st.shuffle, size = 44.dp, iconSize = 20.dp, shape = DeckKeySmall) {
                haptics.tick(); Graph.player.setShuffle(!st.shuffle)
            }
            DeckKeyButton(Icons.Rounded.SkipPrevious, "Previous", active = false, size = 60.dp, iconSize = 30.dp, shape = DeckKey) { haptics.click(); Graph.player.previous() }
            val playI = remember { MutableInteractionSource() }
            Box(contentAlignment = Alignment.Center) {
                Box(Modifier.size(78.dp).pressScale(playI, 0.9f)
                    .graphicsLayer { shadowElevation = (if (st.playing) 14 else 6).dp.toPx(); shape = DeckKeyBig; clip = true; spotShadowColor = primary; ambientShadowColor = primary }
                    .background(Brush.verticalGradient(listOf(lerp(primary, Color.White, 0.12f), primary)))
                    .clickable(playI, null, onClickLabel = if (st.playing) "Pause" else "Play") { haptics.click(); Graph.player.toggle() },
                    contentAlignment = Alignment.Center) {
                    PlayPauseIcon(st.playing, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onPrimary)
                }
                if (st.buffering) CircularProgressIndicator(Modifier.size(28.dp).align(Alignment.TopEnd).padding(4.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            }
            DeckKeyButton(Icons.Rounded.SkipNext, "Next", active = false, size = 60.dp, iconSize = 30.dp, shape = DeckKey) { haptics.click(); Graph.player.next() }
            DeckKeyButton(if (st.repeat == M3.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                when (st.repeat) { M3.REPEAT_MODE_ONE -> "Repeat one"; M3.REPEAT_MODE_ALL -> "Repeat all"; else -> "Repeat off" },
                active = st.repeat != M3.REPEAT_MODE_OFF, size = 44.dp, iconSize = 20.dp, shape = DeckKeySmall) { haptics.tick(); Graph.player.cycleRepeat() }
        }
    }
}

@Composable
private fun DeckKeyButton(icon: ImageVector, label: String, active: Boolean, size: Dp, iconSize: Dp, shape: RoundedCornerShape, onClick: () -> Unit) {
    val still = reducedMotion()
    val i = remember { MutableInteractionSource() }
    val primary = MaterialTheme.colorScheme.primary
    val ink = MaterialTheme.colorScheme.onBackground
    val tint by animateColorAsState(if (active) primary else ink, if (still) snap() else tween(MotionSpec.MEDIUM), label = "deckKeyTint")
    val fill by animateColorAsState(if (active) primary.copy(alpha = 0.16f) else ink.copy(alpha = 0.08f), if (still) snap() else tween(MotionSpec.MEDIUM), label = "deckKeyFill")
    Box(Modifier.size(size).pressScale(i, 0.88f).clip(shape).background(fill).clickable(i, null, onClickLabel = label) { onClick() }, contentAlignment = Alignment.Center) {
        AnimatedContent(icon, transitionSpec = { iconSwap(still) }, label = "deckKeyIcon") { ic -> Icon(ic, label, Modifier.size(iconSize), tint = tint) }
    }
}

/**
 * v2.0.17: slim straight seek bar with a pill thumb; tap or drag to seek. Position is collected here only.
 * Same dragging-flag / durationMs > 0 guard as the earlier seek bars.
 */
@Composable
private fun DeckSeekBar(durationMs: Long) {
    val pos by Graph.player.position.collectAsState()
    val dur = durationMs.coerceAtLeast(1)
    var scrub by remember { mutableFloatStateOf(-1f) }
    var widthPx by remember { mutableFloatStateOf(1f) }
    val active = scrub >= 0f
    val frac = if (active) scrub else (pos.toFloat() / dur).coerceIn(0f, 1f)
    var dragging by remember { mutableStateOf(false) }
    val fracNow by androidx.compose.runtime.rememberUpdatedState(frac)
    val durNow by androidx.compose.runtime.rememberUpdatedState(durationMs)
    fun seekTo(f: Float) { val d = durNow; if (d > 0) Graph.player.seek((f * d).toLong().coerceIn(0L, d)) }
    val primary = MaterialTheme.colorScheme.primary
    val ink = MaterialTheme.colorScheme.onBackground
    val grow by animateFloatAsState(if (active) 1f else 0f, if (reducedMotion()) snap() else spring(stiffness = 300f), label = "deckThumb")
    Column {
        Box(Modifier.fillMaxWidth().height(28.dp)
            .onGloballyPositioned { widthPx = it.size.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(Unit) {
                detectTapGestures(onPress = { o -> scrub = (o.x / widthPx).coerceIn(0f, 1f); val ok = tryAwaitRelease(); if (!dragging) { if (ok && scrub >= 0f) seekTo(scrub); scrub = -1f } })
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> dragging = true; scrub = (o.x / widthPx).coerceIn(0f, 1f) },
                    onDragEnd = { if (scrub >= 0f) seekTo(scrub); scrub = -1f; dragging = false },
                    onDragCancel = { scrub = -1f; dragging = false },
                ) { ch, amount -> ch.consume(); scrub = ((if (scrub < 0f) fracNow else scrub) + amount / widthPx).coerceIn(0f, 1f) }
            }) {
            Canvas(Modifier.fillMaxSize()) {
                if (size.width <= 0f || size.height <= 0f) return@Canvas
                val cy = size.height / 2f
                val th = (4.dp.toPx() + 2.dp.toPx() * grow)
                val tw = 22.dp.toPx() + 6.dp.toPx() * grow
                val tH = 12.dp.toPx() + 4.dp.toPx() * grow
                val usable = (size.width - tw).coerceAtLeast(0f)
                val x = tw / 2f + usable * frac
                drawRoundRect(ink.copy(alpha = 0.18f), Offset(0f, cy - th / 2f), Size(size.width, th), CornerRadius(th / 2f, th / 2f))
                drawRoundRect(primary, Offset(0f, cy - th / 2f), Size(x, th), CornerRadius(th / 2f, th / 2f))
                drawRoundRect(primary, Offset(x - tw / 2f, cy - tH / 2f), Size(tw, tH), CornerRadius(tH / 2f, tH / 2f))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Text(formatTime(if (active) (scrub * dur).toLong() else pos), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text(formatTime(durationMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** v2.0.17: bottom row – the four panes (toggle) plus Sleep and Sound, on glass (its children are not glass). */
@Composable
private fun DeckPaneRow(open: Pane?, onSelect: (Pane) -> Unit, onOpenSleep: () -> Unit, onOpenEq: () -> Unit) {
    val haptics = rememberHaptics()
    val engine by EngineAudioProcessor.status.collectAsState()
    val sleepTick by Playback.sleepTick.collectAsState()
    val sleepOn = sleepTick >= 0 && Graph.player.sleepActive
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).height(56.dp).glass(DeckCard).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Pane.entries.forEach { p -> DeckRowButton(p.icon, p.label, p == open, Modifier.weight(1f)) { haptics.tick(); onSelect(p) } }
        DeckRowButton(Icons.Rounded.Bedtime, if (sleepOn) "Sleep on" else "Sleep", sleepOn, Modifier.weight(1f)) { onOpenSleep() }
        DeckRowButton(Icons.Rounded.Equalizer, if (engine.eqActive) "EQ on" else "Sound", engine.eqActive, Modifier.weight(1f)) { onOpenEq() }
    }
}

@Composable
private fun DeckRowButton(icon: ImageVector, label: String, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val still = reducedMotion()
    val i = remember { MutableInteractionSource() }
    val primary = MaterialTheme.colorScheme.primary
    val soft = MaterialTheme.colorScheme.onSurfaceVariant
    val tint by animateColorAsState(if (active) primary else soft, if (still) snap() else tween(MotionSpec.MEDIUM), label = "deckRowTint")
    Column(modifier.fillMaxHeight().pressScale(i, 0.9f).clip(RoundedCornerShape(14.dp)).clickable(i, null, onClickLabel = label) { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(width = 44.dp, height = 24.dp).clip(DeckPill).background(if (active) primary.copy(alpha = 0.18f) else Color.Transparent), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(18.dp), tint = tint)
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun DeckMiniHeader(onClose: () -> Unit) {
    val st by Graph.player.state.collectAsState()
    val t = st.current ?: return
    val still = reducedMotion()
    val haptics = rememberHaptics()
    val primary = MaterialTheme.colorScheme.primary
    Row(Modifier.fillMaxWidth().clickable(onClickLabel = "Back to deck") { onClose() }.padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Artwork(t.artUrl, Modifier.size(48.dp), RoundedCornerShape(12.dp))
        Spacer(Modifier.width(12.dp))
        AnimatedContent(t, contentKey = { it.id }, transitionSpec = { MotionSpec.sharedAxisX(true, still) }, label = "deckHdr", modifier = Modifier.weight(1f)) { tt ->
            Column {
                Text(tt.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(tt.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        val playI = remember { MutableInteractionSource() }
        Box(Modifier.size(42.dp).pressScale(playI, 0.86f).clip(DeckKeySmall).background(primary)
            .clickable(playI, null, onClickLabel = if (st.playing) "Pause" else "Play") { haptics.click(); Graph.player.toggle() }, contentAlignment = Alignment.Center) {
            PlayPauseIcon(st.playing, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onPrimary)
        }
    }
}

/** v2.0.17: pane container – a plain tinted card (not glass), so no glass nests inside glass. */
@Composable
private fun DeckPaneCard(p: Pane, t: Track, onCollapse: () -> Unit, onNavigate: (Route) -> Unit, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    Box(modifier.padding(vertical = 6.dp).clip(DeckCard).background(c.surfaceContainerHigh.copy(alpha = 0.72f)).border(1.dp, c.onSurface.copy(alpha = 0.08f), DeckCard)) {
        when (p) {
            Pane.QUEUE -> QueuePane()
            Pane.LYRICS -> LyricsPane()
            Pane.RELATED -> RelatedPane(onNavigate = { onCollapse(); onNavigate(it) })
            Pane.CREDITS -> CreditsPane(t)
        }
    }
}
