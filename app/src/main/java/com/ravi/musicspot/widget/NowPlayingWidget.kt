package com.ravi.musicspot.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.widget.RemoteViews
import androidx.palette.graphics.Palette
import com.ravi.musicspot.MainActivity
import com.ravi.musicspot.R
import com.ravi.musicspot.core.Http
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.Request

/**
 * v2.0.7 home-screen widget: rounded card tinted with the artwork's own colours (a diagonal gradient from the
 * Palette vibrant/dark-muted pair, like Now Playing), rounded artwork, title/artist, and prev / play-pause / next.
 * Plain RemoteViews (no extra library).
 *
 * Pack 5 Part 2: the card is rendered per widget at its real size by [WidgetCard] (fitXY), so the rounded corners
 * hold on Android 10 too; resizing re-renders it ([onAppWidgetOptionsChanged]). The same companion also renders the
 * 4×1 [NowPlayingCompactWidget] and, on every push, refreshes the [StatsWidget].
 */
class NowPlayingWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = push(context, last)

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, newOptions: android.os.Bundle?) {
        render(context.applicationContext, last) // resized: re-render the card at the new size
    }

    override fun onReceive(context: Context, intent: Intent) {
        // v2.0.14: null-safe action; handler/startActivity failures can't crash the receiver.
        val a = intent.action ?: return
        when (a) {
            ACTION_TOGGLE, ACTION_NEXT, ACTION_PREV -> {
                val handled = runCatching { Controls.handle?.invoke(a) == true }.getOrDefault(false)
                if (!handled) runCatching { context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(MainActivity.EXTRA_OPEN_PLAYER, true)) }
            }
            else -> super.onReceive(context, intent)
        }
    }

    data class State(val title: String?, val artist: String?, val artUrl: String?, val playing: Boolean)

    /** PlaybackService registers its player here so the widget buttons work without opening the app. */
    object Controls { @Volatile var handle: ((String) -> Boolean)? = null }

    companion object {
        const val ACTION_TOGGLE = "com.ravi.musicspot.widget.TOGGLE"
        const val ACTION_NEXT = "com.ravi.musicspot.widget.NEXT"
        const val ACTION_PREV = "com.ravi.musicspot.widget.PREV"
        @Volatile private var last = State(null, null, null, false)
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        // v2.0.14: shared between the caller thread and the IO art loader.
        @Volatile private var job: Job? = null
        @Volatile private var artFor: String? = null
        @Volatile private var art: Bitmap? = null
        /** Pack 5 Part 2: the artwork's two card colours (the card itself is rendered per widget size). */
        @Volatile private var colors: Pair<Int, Int>? = null

        /** Artwork thumbnail bitmap side: 64 dp at ~3× density. */
        private const val ART_PX = 192
        /** 1.0.5 cap for any bitmap handed to RemoteViews (binder transaction size). */
        private const val MAX_RV_PX = 256

        /** Both now-playing layouts: (provider, layout, has a Previous button, fallback size in dp). */
        private class Kind(val provider: Class<*>, val layout: Int, val prev: Boolean, val wdp: Int, val hdp: Int)
        private val KINDS = listOf(
            Kind(NowPlayingWidget::class.java, R.layout.widget_now_playing, true, 250, 92),
            Kind(NowPlayingCompactWidget::class.java, R.layout.widget_now_playing_compact, false, 250, 56),
        )

        /** Called by the playback service whenever the song or play state changes. */
        fun update(context: Context, state: State) {
            if (state == last) return
            last = state
            val app = context.applicationContext
            // Pack 5 Part 2: the stats card follows every now-playing push (a song change usually means a new count).
            runCatching { StatsWidget.refresh(app) }
            if (!hasAny(app)) return
            push(app, state)
        }

        /** Re-renders every now-playing widget with the last known state (used by the compact receiver). */
        internal fun refreshAll(context: Context) = push(context.applicationContext, last)

        private fun hasAny(app: Context): Boolean = runCatching {
            val m = AppWidgetManager.getInstance(app)
            KINDS.any { m.getAppWidgetIds(ComponentName(app, it.provider)).isNotEmpty() }
        }.getOrDefault(false)

        private fun push(context: Context, s: State) {
            val app = context.applicationContext
            // Text and buttons now; artwork and tint when the image has loaded (cached per URL).
            render(app, s)
            if (s.artUrl != artFor) {
                job?.cancel()
                job = scope.launch {
                    // v2.0.14: never let a decode/Palette/OOM failure escape; publish art/colours before artFor.
                    var bmp: Bitmap? = null
                    try {
                        bmp = s.artUrl?.let { loadBitmap(it) }
                        art = bmp?.let { fitForRemoteViews(rounded(it, ART_PX, ART_PX * 40f / 256f)) }
                        colors = bmp?.let { paletteColors(it) }
                        artFor = s.artUrl
                        render(app, last)
                    } catch (t: Throwable) {
                        if (t is kotlinx.coroutines.CancellationException) throw t
                    } finally {
                        // 1.0.5: the decoded source is a private temp (never given to RemoteViews or published): free it now.
                        bmp?.let { b -> if (!b.isRecycled) runCatching { b.recycle() } }
                    }
                }
            }
        }

        /** One RemoteViews per widget id (each gets a card rendered at its own size). */
        private fun render(app: Context, s: State) = runCatching {
            val m = AppWidgetManager.getInstance(app)
            val (c1, c2) = colors?.takeIf { artFor == s.artUrl } ?: (WidgetCard.DEFAULT_C1 to WidgetCard.DEFAULT_C2)
            val thumb = art?.takeIf { artFor == s.artUrl }
            for (k in KINDS) {
                val ids = runCatching { m.getAppWidgetIds(ComponentName(app, k.provider)) }.getOrNull() ?: continue
                for (id in ids) runCatching {
                    val v = RemoteViews(app.packageName, k.layout)
                    v.setTextViewText(R.id.w_title, s.title ?: "Nothing playing")
                    v.setTextViewText(R.id.w_artist, s.artist ?: "Tap to open UpZe")
                    v.setImageViewResource(R.id.w_play, if (s.playing) R.drawable.ic_w_pause else R.drawable.ic_w_play)
                    if (thumb != null) v.setImageViewBitmap(R.id.w_art, thumb) else v.setImageViewResource(R.id.w_art, R.mipmap.ic_launcher)
                    v.setImageViewBitmap(R.id.w_bg, WidgetCard.forWidget(app, m, id, k.wdp, k.hdp, c1, c2))
                    // 1.0.0: own request code; 0 collided with the media session's PendingIntent (same component) and FLAG_UPDATE_CURRENT overwrote its open_player extra.
                    val open = PendingIntent.getActivity(app, 10, Intent(app, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_PLAYER, s.title != null)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                    v.setOnClickPendingIntent(R.id.w_root, open)
                    v.setOnClickPendingIntent(R.id.w_play, action(app, ACTION_TOGGLE, 1))
                    v.setOnClickPendingIntent(R.id.w_next, action(app, ACTION_NEXT, 2))
                    if (k.prev) v.setOnClickPendingIntent(R.id.w_prev, action(app, ACTION_PREV, 3))
                    m.updateAppWidget(id, v)
                }
            }
        }

        /** Buttons of both layouts broadcast to this receiver (one handler for the player actions). */
        private fun action(app: Context, a: String, code: Int) = PendingIntent.getBroadcast(app, code,
            Intent(app, NowPlayingWidget::class.java).setAction(a), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        private fun loadBitmap(url: String): Bitmap? = runCatching {
            Http.client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) return@use null
                val bytes = r.body?.bytes() ?: return@use null
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                var sample = 1; while (o.outWidth / (sample * 2) >= 256) sample *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        }.getOrNull()

        /** Palette vibrant (else dominant) and dark-muted (else dark-vibrant) colours; defaults when Palette finds none. */
        private fun paletteColors(src: Bitmap): Pair<Int, Int> {
            val pal = runCatching { Palette.from(src).generate() }.getOrNull()
            val a = pal?.let { (it.vibrantSwatch ?: it.dominantSwatch)?.rgb }
            val b = pal?.let { (it.darkMutedSwatch ?: it.darkVibrantSwatch)?.rgb }
            return (a ?: WidgetCard.DEFAULT_C1) to (b ?: WidgetCard.DEFAULT_C2)
        }

        /**
         * 1.0.5: returns [src] if it is already within MAX_RV_PX and in its compact config; otherwise a downscaled copy
         * (aspect kept, RGB_565 when the bitmap has no alpha, ARGB_8888 otherwise) and recycles [src]. Only call this on
         * bitmaps this class just created and has not published yet, so recycling the temp is safe.
         */
        private fun fitForRemoteViews(src: Bitmap): Bitmap {
            val w = src.width; val h = src.height
            if (w <= 0 || h <= 0) return src
            val longest = maxOf(w, h)
            val opaque = !src.hasAlpha()
            val fits = longest <= MAX_RV_PX
            if (fits && (!opaque || src.config == Bitmap.Config.RGB_565)) return src
            val scale = if (fits) 1f else MAX_RV_PX.toFloat() / longest
            val tw = maxOf(1, Math.round(w * scale)); val th = maxOf(1, Math.round(h * scale))
            val out = Bitmap.createBitmap(tw, th, if (opaque) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888)
            Canvas(out).drawBitmap(src, null, android.graphics.Rect(0, 0, tw, th), Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG))
            if (out !== src && !src.isRecycled) runCatching { src.recycle() }
            return out
        }

        /** Rounded artwork thumbnail (unchanged treatment: square crop, rounded corners via SRC_IN). */
        private fun rounded(src: Bitmap, size: Int, radius: Float): Bitmap {
            val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            c.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), radius, radius, p)
            p.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
            val side = minOf(src.width, src.height)
            val sx = (src.width - side) / 2; val sy = (src.height - side) / 2
            c.drawBitmap(src, android.graphics.Rect(sx, sy, sx + side, sy + side), android.graphics.Rect(0, 0, size, size), p)
            return out
        }
    }
}

/**
 * Pack 5 Part 2: 4×1 now-playing widget (art, title/artist, play/pause, next). Thin: everything is rendered by
 * [NowPlayingWidget]'s companion; its buttons broadcast to [NowPlayingWidget], which drives the player.
 */
class NowPlayingCompactWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = NowPlayingWidget.refreshAll(context)
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, newOptions: android.os.Bundle?) = NowPlayingWidget.refreshAll(context)
}
