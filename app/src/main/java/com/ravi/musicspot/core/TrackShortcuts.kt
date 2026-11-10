package com.ravi.musicspot.core

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import coil3.BitmapImage
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import com.ravi.musicspot.MainActivity
import com.ravi.musicspot.R
import com.ravi.musicspot.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 1.0.2: the player's ⋮ extras.
 * - [pin]: a home-screen (launcher) shortcut with the song's cover; tapping it opens UpZe and plays that song.
 * - [openOutputPicker]: the system's "play on" device picker (Bluetooth, speakers, Cast devices the phone offers).
 */
object TrackShortcuts {
    const val ACTION_PLAY_TRACK = "com.ravi.musicspot.action.PLAY_TRACK"
    const val EXTRA_TRACK = "track_json"

    fun supported(ctx: Context) = ShortcutManagerCompat.isRequestPinShortcutSupported(ctx)

    /** Returns false when the launcher doesn't accept pinned shortcuts. */
    suspend fun pin(ctx: Context, t: Track): Boolean {
        if (!supported(ctx)) return false
        val icon = cover(ctx, t.artUrl)?.let { IconCompat.createWithAdaptiveBitmap(padForAdaptive(it)) }
            ?: IconCompat.createWithResource(ctx, R.mipmap.ic_launcher)
        val intent = Intent(ctx, MainActivity::class.java).setAction(ACTION_PLAY_TRACK)
            .putExtra(EXTRA_TRACK, Http.json.encodeToString(Track.serializer(), t))
            .putExtra(MainActivity.EXTRA_OPEN_PLAYER, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val info = ShortcutInfoCompat.Builder(ctx, "track:${t.id}".take(64))
            .setShortLabel(t.title.ifBlank { "Song" }.take(24))
            .setLongLabel("${t.title} · ${t.artist}".take(48))
            .setIcon(icon).setIntent(intent).build()
        return runCatching { ShortcutManagerCompat.requestPinShortcut(ctx, info, null) }.getOrDefault(false)
    }

    const val ACTION_PLAY_PLAYLIST = "com.ravi.musicspot.action.PLAY_PLAYLIST"
    const val EXTRA_PLAYLIST = "playlist_id"

    /** 1.0.2: a home-screen shortcut for one of the user's playlists (its cover); tapping it plays the playlist. */
    suspend fun pinPlaylist(ctx: Context, p: com.ravi.musicspot.data.LocalPlaylist): Boolean {
        if (!supported(ctx)) return false
        val icon = cover(ctx, p.artUrl ?: p.tracks.firstOrNull()?.artUrl)?.let { IconCompat.createWithAdaptiveBitmap(padForAdaptive(it)) }
            ?: IconCompat.createWithResource(ctx, R.mipmap.ic_launcher)
        val intent = Intent(ctx, MainActivity::class.java).setAction(ACTION_PLAY_PLAYLIST).putExtra(EXTRA_PLAYLIST, p.id)
            .putExtra(MainActivity.EXTRA_OPEN_PLAYER, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val info = ShortcutInfoCompat.Builder(ctx, "playlist:${p.id}".take(64))
            .setShortLabel(p.name.ifBlank { "Playlist" }.take(24)).setLongLabel("${p.name} · playlist".take(48))
            .setIcon(icon).setIntent(intent).build()
        return runCatching { ShortcutManagerCompat.requestPinShortcut(ctx, info, null) }.getOrDefault(false)
    }

    /** The playlist id a pinned playlist shortcut carries, or null. */
    fun playlistFrom(intent: Intent?): String? = intent?.takeIf { it.action == ACTION_PLAY_PLAYLIST }?.getStringExtra(EXTRA_PLAYLIST)

    /** The song a pinned shortcut carries, or null for any other launch. */
    fun trackFrom(intent: Intent?): Track? {
        if (intent?.action != ACTION_PLAY_TRACK) return null
        val json = intent.getStringExtra(EXTRA_TRACK) ?: return null
        return runCatching { Http.json.decodeFromString(Track.serializer(), json) }.getOrNull()
    }

    private suspend fun cover(ctx: Context, url: String?): Bitmap? = withContext(Dispatchers.IO) {
        if (url.isNullOrBlank()) return@withContext null
        val req = ImageRequest.Builder(ctx).data(url).size(256).allowHardware(false).build()
        ((SingletonImageLoader.get(ctx).execute(req) as? SuccessResult)?.image as? BitmapImage)?.bitmap
    }

    /** Adaptive icons crop the outer third; centre-crop to a square and keep the cover inside the safe zone. */
    private fun padForAdaptive(src: Bitmap): Bitmap {
        val side = minOf(src.width, src.height)
        val square = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
        val out = Bitmap.createBitmap(side * 3 / 2, side * 3 / 2, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(out)
        c.drawColor(android.graphics.Color.BLACK)
        val inset = (out.width - side * 1.1f) / 2f
        c.drawBitmap(square, null, android.graphics.RectF(inset, inset, out.width - inset, out.height - inset), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
        return out
    }

    /**
     * Opens the system output picker for UpZe's audio: Android 14+ the media output switcher, Android 11–13 the
     * media output panel, older versions the Cast settings (then Bluetooth settings). Returns false if none opened.
     */
    fun openOutputPicker(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 34) {
            runCatching { if (android.media.MediaRouter2.getInstance(ctx).showSystemOutputSwitcher()) return true }
        }
        val pkg = ctx.packageName
        val tries = buildList {
            if (Build.VERSION.SDK_INT >= 30) add(Intent("android.settings.panel.action.MEDIA_OUTPUT")
                .putExtra("com.android.settings.panel.extra.PACKAGE_NAME", pkg))
            add(Intent(android.provider.Settings.ACTION_CAST_SETTINGS))
            add(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))
        }
        for (i in tries) {
            try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return true }
            catch (_: ActivityNotFoundException) {} catch (_: SecurityException) {}
        }
        return false
    }
}
