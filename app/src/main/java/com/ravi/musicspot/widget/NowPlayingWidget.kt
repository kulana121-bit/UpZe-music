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
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
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
 * Plain RemoteViews (no extra library), drawn as bitmaps so the rounded corners hold on Android 8–11 too.
 */
class NowPlayingWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = push(context, last)

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
        @Volatile private var bg: Bitmap? = null

        /** Called by the playback service whenever the song or play state changes. */
        fun update(context: Context, state: State) {
            if (state == last) return
            last = state
            val app = context.applicationContext
            val ids = runCatching { AppWidgetManager.getInstance(app).getAppWidgetIds(ComponentName(app, NowPlayingWidget::class.java)) }.getOrNull()
            if (ids == null || ids.isEmpty()) return
            push(app, state)
        }

        private fun push(context: Context, s: State) {
            val app = context.applicationContext
            // Text and buttons now; artwork and tint when the image has loaded (cached per URL).
            render(app, s)
            if (s.artUrl != artFor) {
                job?.cancel()
                job = scope.launch {
                    // v2.0.14: never let a decode/Palette/OOM failure escape; publish art/bg before artFor.
                    try {
                        val bmp = s.artUrl?.let { loadBitmap(it) }
                        art = bmp?.let { rounded(it, 256, 40f) }
                        bg = makeBackground(bmp)
                        artFor = s.artUrl
                        render(app, last)
                    } catch (t: Throwable) {
                        if (t is kotlinx.coroutines.CancellationException) throw t
                    }
                }
            }
        }

        private fun render(app: Context, s: State) = runCatching {
            val v = RemoteViews(app.packageName, R.layout.widget_now_playing)
            v.setTextViewText(R.id.w_title, s.title ?: "Nothing playing")
            v.setTextViewText(R.id.w_artist, s.artist ?: "Tap to open UpZe")
            v.setImageViewResource(R.id.w_play, if (s.playing) R.drawable.ic_w_pause else R.drawable.ic_w_play)
            art?.takeIf { artFor == s.artUrl }?.let { v.setImageViewBitmap(R.id.w_art, it) } ?: v.setImageViewResource(R.id.w_art, R.mipmap.ic_launcher)
            (bg ?: makeBackground(null)).let { v.setImageViewBitmap(R.id.w_bg, it) }
            // 1.0.0: own request code; 0 collided with the media session's PendingIntent (same component) and FLAG_UPDATE_CURRENT overwrote its open_player extra.
            val open = PendingIntent.getActivity(app, 10, Intent(app, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_PLAYER, s.title != null)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            v.setOnClickPendingIntent(R.id.w_root, open)
            v.setOnClickPendingIntent(R.id.w_play, action(app, ACTION_TOGGLE, 1))
            v.setOnClickPendingIntent(R.id.w_next, action(app, ACTION_NEXT, 2))
            v.setOnClickPendingIntent(R.id.w_prev, action(app, ACTION_PREV, 3))
            val m = AppWidgetManager.getInstance(app)
            m.updateAppWidget(ComponentName(app, NowPlayingWidget::class.java), v)
        }

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

        /** 4:2 rounded card: artwork-coloured diagonal gradient with a soft dark vignette for legible text. */
        private fun makeBackground(src: Bitmap?): Bitmap {
            val w = 640; val h = 300; val r = 64f
            val (a, b) = src?.let { runCatching { Palette.from(it).generate() }.getOrNull() }?.let { pal ->
                (pal.vibrantSwatch ?: pal.dominantSwatch)?.rgb to (pal.darkMutedSwatch ?: pal.darkVibrantSwatch)?.rgb
            } ?: (null to null)
            val c1 = a ?: 0xFF5A1E24.toInt(); val c2 = b ?: 0xFF120B0C.toInt()
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), intArrayOf(darken(c1, 0.55f), darken(c2, 0.8f), 0xFF0B0809.toInt()), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
            c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), r, r, p)
            // Glass edge highlight.
            val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f; color = 0x33FFFFFF }
            c.drawRoundRect(RectF(1f, 1f, w - 1f, h - 1f), r, r, edge)
            return out
        }

        private fun darken(c: Int, f: Float): Int {
            val r = ((c shr 16 and 0xFF) * f).toInt(); val g = ((c shr 8 and 0xFF) * f).toInt(); val b = ((c and 0xFF) * f).toInt()
            return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
}
