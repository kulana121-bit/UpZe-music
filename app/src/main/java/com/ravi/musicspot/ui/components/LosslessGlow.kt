package com.ravi.musicspot.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ravi.musicspot.playback.Playback
import com.ravi.musicspot.source.QualityEstimate
import com.ravi.musicspot.ui.motion.reducedMotion

/** 1.0.7: true while [trackId] is playing a lossless stream (decoder-reported, or a FLAC/ALAC/WAV-class stream). */
@Composable
fun rememberIsLossless(trackId: String): Boolean {
    val stream by Playback.stream.collectAsState()
    val s = stream?.takeIf { it.trackId == trackId } ?: return false
    return remember(s) { s.lossless || QualityEstimate.isLosslessStream(s.info) }
}

/**
 * 1.0.7 (Raj): song title with a small glowing "LOSSLESS" (or "HI-RES") label next to it while the stream is
 * lossless. The glow is a soft text shadow whose radius/alpha breathe slowly (one InfiniteTransition, drawn in
 * graphicsLayer, no recomposition per frame for the alpha); with reduced motion it is a steady glow.
 */
@Composable
fun TitleWithLossless(trackId: String, modifier: Modifier = Modifier, title: @Composable (Modifier) -> Unit) {
    val lossless = rememberIsLossless(trackId)
    val hiRes = Playback.stream.collectAsState().value?.takeIf { it.trackId == trackId }?.hiRes == true
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        title(Modifier.weight(1f, fill = false))
        AnimatedVisibility(lossless, enter = fadeIn() + scaleIn(initialScale = 0.8f), exit = fadeOut() + scaleOut(targetScale = 0.8f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(8.dp))
                LosslessGlowText(if (hiRes) "HI-RES" else "LOSSLESS")
            }
        }
    }
}

private val GlowGold = Color(0xFFFFD27A)

@Composable
fun LosslessGlowText(label: String = "LOSSLESS", modifier: Modifier = Modifier) {
    val still = reducedMotion()
    val pulse = if (still) null else rememberInfiniteTransition(label = "losslessGlow")
        .animateFloat(0.55f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Reverse), label = "losslessGlowA")
    // Two fixed styles (soft and bright shadow); the breathing is alpha in graphicsLayer, so text layout is stable.
    val style = remember {
        TextStyle(fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.2.sp, color = GlowGold,
            shadow = Shadow(GlowGold.copy(alpha = 0.9f), Offset.Zero, blurRadius = 8f)) /* 1.0.8 lighter: was 14 */
    }
    Text(label, modifier.graphicsLayer { alpha = pulse?.value ?: 1f }, style = MaterialTheme.typography.labelSmall.merge(style),
        maxLines = 1, overflow = TextOverflow.Clip)
}
