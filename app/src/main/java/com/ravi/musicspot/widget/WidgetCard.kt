package com.ravi.musicspot.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Bundle
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pack 5 Part 2: the one rounded card background every UpZe widget uses as its outermost layer.
 *
 * The corner bug: the old Now Playing card was painted into one fixed 640×300 bitmap and shown with centerCrop, so on
 * any real widget size the pre-rounded corners were cropped away and the card looked square (Android 10 has no
 * launcher-side clipping, and clipToOutline does nothing in RemoteViews). Now the card is rendered for the widget's
 * ACTUAL size (from the AppWidgetManager options, re-rendered in onAppWidgetOptionsChanged) with the corner radius
 * scaled to that size, and shown with fitXY: the bitmap has the widget's aspect, so its transparent corners land
 * exactly on the widget's corners.
 *
 * The card is computed per pixel (anti-aliased rounded-rect coverage, diagonal artwork gradient, 1 dp glass edge) and
 * handed to Bitmap as an ARGB array: deterministic, no Canvas/xfermode, and testable under Robolectric.
 */
object WidgetCard {
    /** Corner radius of every UpZe widget card (clamped to half the shorter side for 1-row widgets). */
    const val RADIUS_DP = 22f
    /** Longest side of a card bitmap. RemoteViews parcels bitmaps through binder (~1 MB per transaction, shared). */
    const val MAX_PX = 400
    const val DEFAULT_C1 = 0xFF5A1E24.toInt()
    const val DEFAULT_C2 = 0xFF120B0C.toInt()
    private const val END = 0xFF0B0809.toInt()
    private const val EDGE = 0x33FFFFFF

    /** Widget size in dp from its options: portrait uses min width × max height, landscape max width × min height. */
    fun sizeDp(options: Bundle?, portrait: Boolean, fallbackWdp: Int, fallbackHdp: Int): Pair<Int, Int> {
        val minW = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) ?: 0
        val maxW = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH) ?: 0
        val minH = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT) ?: 0
        val maxH = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT) ?: 0
        val w = (if (portrait) minW else maxW).takeIf { it > 0 } ?: max(minW, maxW).takeIf { it > 0 } ?: fallbackWdp
        val h = (if (portrait) maxH else minH).takeIf { it > 0 } ?: max(minH, maxH).takeIf { it > 0 } ?: fallbackHdp
        return w to h
    }

    /** The card for widget [id] at its current size (dp → px with the device density). */
    fun forWidget(context: Context, manager: AppWidgetManager, id: Int, fallbackWdp: Int, fallbackHdp: Int, c1: Int = DEFAULT_C1, c2: Int = DEFAULT_C2): Bitmap {
        val res = context.resources
        val portrait = res.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
        val options = runCatching { manager.getAppWidgetOptions(id) }.getOrNull()
        val (wdp, hdp) = sizeDp(options, portrait, fallbackWdp, fallbackHdp)
        val d = res.displayMetrics.density
        return render((wdp * d).roundToInt(), (hdp * d).roundToInt(), d, c1, c2)
    }

    private val cache = object : LinkedHashMap<String, Bitmap>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?) = size > 8
    }

    /**
     * A rounded card for a widget of [widthPx] × [heightPx]: the bitmap keeps that aspect (scaled down so its longest
     * side is at most [MAX_PX]) and the radius ([RADIUS_DP] × [density]) is scaled with it. Cached per size + colours;
     * cached bitmaps are only handed to RemoteViews (parcelled copies), never mutated.
     */
    fun render(widthPx: Int, heightPx: Int, density: Float, c1: Int = DEFAULT_C1, c2: Int = DEFAULT_C2): Bitmap {
        val w0 = max(1, widthPx); val h0 = max(1, heightPx)
        val k = min(1f, MAX_PX.toFloat() / max(w0, h0))
        val bw = max(1, (w0 * k).roundToInt()); val bh = max(1, (h0 * k).roundToInt())
        val radius = min(RADIUS_DP * density * k, min(bw, bh) / 2f)
        val edge = max(1f, density * k)
        val key = "$bw:$bh:$radius:$c1:$c2"
        synchronized(cache) { cache[key] }?.let { return it }
        val px = pixels(bw, bh, radius, edge, c1, c2)
        val out = Bitmap.createBitmap(px, bw, bh, Bitmap.Config.ARGB_8888)
        synchronized(cache) { cache[key] = out }
        return out
    }

    /**
     * The card's ARGB pixels (unpremultiplied, row-major): rounded-rect coverage from a signed distance (anti-aliased
     * over one pixel), a diagonal gradient (artwork vibrant → dark muted → near black, darkened for legible white text)
     * and a translucent white edge just inside the outline. Corners outside the radius are fully transparent.
     */
    fun pixels(w: Int, h: Int, radius: Float, edge: Float, c1: Int, c2: Int): IntArray {
        val stops = intArrayOf(darken(c1, 0.55f), darken(c2, 0.8f), END)
        val pos = floatArrayOf(0f, 0.6f, 1f)
        val out = IntArray(w * h)
        val hx = w / 2f; val hy = h / 2f
        val r = radius.coerceIn(0f, min(hx, hy))
        val len2 = w.toFloat() * w + h.toFloat() * h
        for (y in 0 until h) {
            val py = y + 0.5f
            for (x in 0 until w) {
                val px = x + 0.5f
                val d = roundRectDistance(px - hx, py - hy, hx, hy, r)
                val cover = (0.5f - d).coerceIn(0f, 1f)
                if (cover <= 0f) { out[y * w + x] = 0; continue }
                var c = gradient(stops, pos, ((px * w + py * h) / len2).coerceIn(0f, 1f))
                // Glass edge: a 1 dp band just inside the outline.
                val band = ((d + edge + 0.5f).coerceIn(0f, 1f))
                if (band > 0f) c = blend(c, EDGE, band)
                val a = (cover * 255f).roundToInt().coerceIn(0, 255)
                out[y * w + x] = (a shl 24) or (c and 0xFFFFFF)
            }
        }
        return out
    }

    /** Signed distance from (x, y) (relative to the centre) to a rounded rect with half-size (hx, hy) and radius r. */
    private fun roundRectDistance(x: Float, y: Float, hx: Float, hy: Float, r: Float): Float {
        val qx = kotlin.math.abs(x) - (hx - r)
        val qy = kotlin.math.abs(y) - (hy - r)
        val outside = hypot(max(qx, 0f), max(qy, 0f))
        val inside = min(max(qx, qy), 0f)
        return outside + inside - r
    }

    private fun gradient(stops: IntArray, pos: FloatArray, t: Float): Int {
        for (i in 1 until stops.size) if (t <= pos[i]) {
            val f = (t - pos[i - 1]) / (pos[i] - pos[i - 1])
            return lerp(stops[i - 1], stops[i], f)
        }
        return stops.last()
    }

    private fun lerp(a: Int, b: Int, f: Float): Int {
        fun ch(s: Int) = (((a shr s) and 0xFF) + (((b shr s) and 0xFF) - ((a shr s) and 0xFF)) * f).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** [over]'s colour at its own alpha × [amount] over the opaque [base]. */
    private fun blend(base: Int, over: Int, amount: Float): Int {
        val a = ((over ushr 24) / 255f) * amount
        fun ch(s: Int) = (((base shr s) and 0xFF) * (1 - a) + ((over shr s) and 0xFF) * a).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    fun darken(c: Int, f: Float): Int {
        val r = ((c shr 16 and 0xFF) * f).toInt(); val g = ((c shr 8 and 0xFF) * f).toInt(); val b = ((c and 0xFF) * f).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}
