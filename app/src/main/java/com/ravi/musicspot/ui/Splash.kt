package com.ravi.musicspot.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.ravi.musicspot.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val SplashBg = Color(0xFF141317)
private val SplashGlow = Color(0xFF5A2E3A)
private val SplashRose = Color(0xFFE7A9B0)
private val SplashText = Color(0xFFF1ECEE)
private const val WORD = "UpZe"

/**
 * v2.0.8 animated splash (~1.5 s, cold start only), in the soft palette: a warm glow blooms, the logo rises in on a
 * gentle spring with a small turn, three soft ripples spread out from it, "UpZe" writes itself in letter by
 * letter with the tagline after it, then the splash melts away while the app (already composed underneath) settles
 * into place. [onExit] fires as the melt starts, so the app's own entrance runs in step with it.
 */
@Composable
fun AnimatedSplash(onExit: () -> Unit = {}, onDone: () -> Unit) {
    val bg = remember { Animatable(0f) }
    val logo = remember { Animatable(0f) }
    val ripple = remember { Animatable(0f) }
    val letters = remember { List(WORD.length) { Animatable(0f) } }
    val tag = remember { Animatable(0f) }
    val out = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        launch { bg.animateTo(1f, tween(280, easing = FastOutSlowInEasing)) }
        launch { logo.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = 320f)) }
        launch { delay(40); ripple.animateTo(1f, tween(700, easing = LinearEasing)) }
        letters.forEachIndexed { i, a -> launch { delay(150L + 22L * i); a.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = 420f)) } }
        launch { delay(330); tag.animateTo(1f, tween(220, easing = FastOutSlowInEasing)) }
        delay(560)
        onExit()
        out.animateTo(1f, tween(280, easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)))
        onDone()
    }
    val o = out.value
    Box(
        Modifier.fillMaxSize().graphicsLayer { alpha = 1f - o }.drawBehind {
            drawRect(SplashBg)
            val r = size.maxDimension * (0.45f + 0.15f * bg.value)
            drawCircle(Brush.radialGradient(listOf(SplashGlow.copy(alpha = 0.75f * bg.value), SplashBg.copy(alpha = 0f)), center, r), r, center)
        },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.graphicsLayer {
            val s = 1f + 0.08f * o; scaleX = s; scaleY = s; translationY = -24.dp.toPx() * o
        }) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(260.dp)) {
                // Three soft ripples, staggered, each spreading out and fading.
                Canvas(Modifier.size(260.dp)) {
                    val maxR = size.minDimension / 2
                    for (i in 0 until 3) {
                        val p = ((ripple.value * 1.6f) - i * 0.28f).coerceIn(0f, 1f)
                        if (p <= 0f || p >= 1f) continue
                        val rr = maxR * (0.38f + 0.62f * p)
                        drawCircle(SplashRose.copy(alpha = 0.32f * (1f - p)), rr, center, style = Stroke(width = (2.5f - 1.5f * p).dp.toPx()))
                    }
                    // Breathing glow directly behind the logo.
                    val g = maxR * (0.55f + 0.06f * kotlin.math.sin(ripple.value * Math.PI * 2).toFloat())
                    drawCircle(Brush.radialGradient(listOf(SplashRose.copy(alpha = 0.22f * logo.value), Color.Transparent), center, g), g, center)
                }
                Image(painterResource(R.drawable.splash_logo), "UpZe", Modifier.size(132.dp).graphicsLayer {
                    val l = logo.value
                    val s = 0.6f + 0.4f * l
                    scaleX = s; scaleY = s; alpha = l.coerceIn(0f, 1f); rotationZ = -10f * (1f - l)
                    shadowElevation = 18.dp.toPx() * l; shape = androidx.compose.foundation.shape.RoundedCornerShape(30.dp); clip = true
                })
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.Center) {
                WORD.forEachIndexed { i, ch ->
                    val a = letters[i].value
                    Text(ch.toString(), style = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.01.em, color = SplashText),
                        modifier = Modifier.graphicsLayer { alpha = a.coerceIn(0f, 1f); translationY = 16.dp.toPx() * (1f - a); val s = 0.85f + 0.15f * a; scaleX = s; scaleY = s })
                }
            }
            Spacer(Modifier.height(6.dp))
            Text("your music, softly everywhere", style = TextStyle(fontSize = 13.sp, letterSpacing = 0.08.em, color = SplashRose),
                modifier = Modifier.graphicsLayer { alpha = tag.value * 0.9f; translationY = 8.dp.toPx() * (1f - tag.value) })
        }
    }
}
