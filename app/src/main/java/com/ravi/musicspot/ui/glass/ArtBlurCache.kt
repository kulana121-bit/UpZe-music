/*
 * 1.0.5: blurred-artwork cache. Modified (C) 2026 Ravi, GPL-3.0-or-later.
 *
 * A heavy blur of album art never needs full resolution: blur removes all detail above a few dozen pixels anyway.
 * So the art is decoded at <= 96 px (RGB_565, software), blurred ONCE on Dispatchers.Default with a pure-Kotlin
 * three-pass box blur (a close Gaussian approximation; no RenderEffect/RenderScript, so it runs on API 26+), cached in
 * an LruCache capped by bytes (~4 MB, i.e. well over a hundred covers), and drawn scaled up with bilinear filtering
 * (FilterQuality.Low). Drawing costs one textured quad per frame instead of a full-screen blur pass.
 *
 * Use it only where the screen today shows a REAL heavy blur of the artwork (a Modifier.blur / RenderEffect on art):
 * at those radii the 96 px result scaled up is indistinguishable. Do NOT use it to replace SoftArt (a tiny 32 px decode
 * stretched without blur) – that is a different, slightly blockier look.
 */
package com.ravi.musicspot.ui.glass

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import coil3.BitmapImage
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.request.bitmapConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

object ArtBlurCache {
    /** Longest side of the decoded art, in px. */
    const val TINY = 96
    private const val MAX_BYTES = 4 * 1024 * 1024

    private val cache = object : LruCache<String, Bitmap>(MAX_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    private fun key(id: String, radius: Int) = "$id#$radius"

    /** The cached blurred bitmap, if it is ready (main-thread safe, no work). */
    fun peek(id: String, tinyRadius: Int): ImageBitmap? = cache.get(key(id, tinyRadius))?.asImageBitmap()

    /**
     * Blurred art for [url], cached under [id] (track id, or the url itself). [tinyRadius] is the blur radius in px of
     * the 96 px bitmap: use [tinyRadiusFor] to scale an on-screen radius. Suspends; decode + blur never touch the main
     * thread. Returns null when the art can't be loaded (nothing is cached then, so it retries next time).
     */
    suspend fun get(context: Context, id: String, url: String?, tinyRadius: Int): ImageBitmap? {
        if (url == null) return null
        val k = key(id, tinyRadius)
        cache.get(k)?.let { return it.asImageBitmap() }
        return withContext(Dispatchers.IO) {
            val req = ImageRequest.Builder(context).data(url).size(TINY)
                .allowHardware(false)                    // pixels must be readable
                .bitmapConfig(Bitmap.Config.RGB_565)     // half the bytes; art with alpha stays ARGB_8888
                .memoryCachePolicy(CachePolicy.READ_ONLY) // our own cache holds the result; don't fill Coil's
                .build()
            val res = SingletonImageLoader.get(context).execute(req) as? SuccessResult ?: return@withContext null
            val src = (res.image as? BitmapImage)?.bitmap ?: return@withContext null
            withContext(Dispatchers.Default) {
                cache.get(k)?.let { return@withContext it.asImageBitmap() } // a concurrent caller finished first
                val out = blurred(src, tinyRadius)
                cache.put(k, out)
                out.asImageBitmap()
            }
        }
    }

    /**
     * Scales an on-screen blur radius ([radiusPx] over a surface [surfacePx] wide/high, longest side) to the tiny
     * bitmap, so the blurred result covers the same fraction of the picture.
     */
    fun tinyRadiusFor(radiusPx: Float, surfacePx: Float): Int =
        if (surfacePx <= 0f) 1 else (radiusPx * TINY / surfacePx).roundToInt().coerceIn(1, TINY / 2)

    fun clear() = cache.evictAll()
    /** 1.0.7: memory-pressure trim (MusicSpotApp.onTrimMemory): keep the most recent half; nothing on screen is lost. */
    fun trimHalf() = cache.trimToSize(cache.maxSize() / 2)

    /** Box blur ×3 (≈ Gaussian with sigma ≈ radius·0.8), separable, edge-clamped. Never mutates [src]. */
    private fun blurred(src: Bitmap, radius: Int): Bitmap {
        val w = src.width; val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        if (radius >= 1 && w > 1 && h > 1) {
            val tmp = IntArray(px.size)
            repeat(3) {
                boxPass(px, tmp, w, h, radius, horizontal = true)
                boxPass(tmp, px, w, h, radius, horizontal = false)
            }
        }
        val cfg = if (src.config == Bitmap.Config.RGB_565) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
        return Bitmap.createBitmap(w, h, cfg).apply { setPixels(px, 0, w, 0, 0, w, h) }
    }

    /** One sliding-window box pass of width 2r+1 along rows (or columns), clamping at the edges. */
    private fun boxPass(input: IntArray, output: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val len = if (horizontal) w else h
        val lines = if (horizontal) h else w
        val step = if (horizontal) 1 else w
        val div = 2 * r + 1
        for (line in 0 until lines) {
            val base = if (horizontal) line * w else line
            var sa = 0; var sr = 0; var sg = 0; var sb = 0
            for (i in -r..r) {
                val c = input[base + i.coerceIn(0, len - 1) * step]
                sa += c ushr 24; sr += (c shr 16) and 0xFF; sg += (c shr 8) and 0xFF; sb += c and 0xFF
            }
            for (i in 0 until len) {
                output[base + i * step] = ((sa / div) shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val add = input[base + (i + r + 1).coerceAtMost(len - 1) * step]
                val rem = input[base + (i - r).coerceAtLeast(0) * step]
                sa += (add ushr 24) - (rem ushr 24)
                sr += ((add shr 16) and 0xFF) - ((rem shr 16) and 0xFF)
                sg += ((add shr 8) and 0xFF) - ((rem shr 8) and 0xFF)
                sb += (add and 0xFF) - (rem and 0xFF)
            }
        }
    }
}

/**
 * 1.0.5: a blurred artwork backdrop from [ArtBlurCache]: fills [modifier], crops like ContentScale.Crop, and draws
 * the cached 96 px blur scaled up with bilinear filtering. [blurRadius] is the radius the screen used to blur with
 * (e.g. the value passed to Modifier.blur); it is scaled to the tiny bitmap from the drawn size. Draws nothing until
 * the blur is ready (first time per cover only), then it is synchronous from the cache.
 */
@Composable
fun BlurredArt(url: String?, modifier: Modifier = Modifier, blurRadius: Dp, id: String? = url, alpha: Float = 1f) {
    val ctx = LocalContext.current
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val surface = with(density) { maxOf(maxWidth.toPx(), maxHeight.toPx()) }
        val r = ArtBlurCache.tinyRadiusFor(with(density) { blurRadius.toPx() }, surface)
        val key = id ?: url
        val bmp by produceState(initialValue = key?.let { ArtBlurCache.peek(it, r) }, key, url, r) {
            // On a new cover the previous blur stays up until the new one is ready (no flash to empty).
            if (key != null) (ArtBlurCache.peek(key, r) ?: runCatching { ArtBlurCache.get(ctx, key, url, r) }.getOrNull())?.let { value = it }
        }
        bmp?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = alpha, filterQuality = FilterQuality.Low) }
    }
}
