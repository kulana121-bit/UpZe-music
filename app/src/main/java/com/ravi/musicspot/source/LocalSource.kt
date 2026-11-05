package com.ravi.musicspot.source

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.ravi.musicspot.model.Quality
import com.ravi.musicspot.model.SourceKind
import com.ravi.musicspot.model.StreamInfo
import com.ravi.musicspot.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/** Music on the device via MediaStore. Permission is requested only when the user turns this source on. */
class LocalSource(private val ctx: Context, private val enabled: () -> Boolean) : MusicSource {
    override val kind = SourceKind.LOCAL
    private val _tracks = MutableStateFlow<List<Track>>(emptyList())
    val tracks: StateFlow<List<Track>> = _tracks

    val permission: String get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    fun hasPermission() = ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED
    override fun available() = enabled() && hasPermission()

    suspend fun scan(): List<Track> = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext emptyList<Track>()
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val cols = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.MIME_TYPE, MediaStore.Audio.Media.YEAR,
        )
        val out = mutableListOf<Track>()
        // v2.0.16: permission revoked mid-scan / provider failure throws SecurityException etc.; treat as no tracks.
        val cursor = try { ctx.contentResolver.query(uri, cols, "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE") }
            catch (e: SecurityException) { null } catch (e: IllegalArgumentException) { null }
        cursor?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val albumId = c.getLong(5)
                val mime = c.getString(6).orEmpty()
                out += Track(
                    "local:$id", c.getString(1).orEmpty(), c.getString(2)?.takeIf { it != "<unknown>" }.orEmpty(), c.getString(3),
                    c.getLong(4), ContentUris.withAppendedId(android.net.Uri.parse("content://media/external/audio/albumart"), albumId).toString(),
                    albumId = "local:al:$albumId", year = c.getInt(7).takeIf { it > 0 }?.toString(), format = formatOf(mime),
                )
            }
        }
        _tracks.value = out
        out
    }

    override suspend fun search(query: String, limit: Int): List<Track> {
        if (!available()) return emptyList()
        val all = _tracks.value.ifEmpty { scan() }
        return com.ravi.musicspot.logic.SearchFilter.filter(query, all) { com.ravi.musicspot.logic.SearchFilter.Fields(it.title, it.artist, it.album.orEmpty()) }.take(limit)
    }

    override suspend fun resolve(track: Track, quality: Quality, metered: Boolean): Resolved {
        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, track.nativeId.toLongOrNull() ?: throw SourceUnavailableException("bad local id")).toString() // v2.0.16: no NumberFormatException
        // Codec/bitrate/sample rate are filled from the decoder's Format once ExoPlayer has read the file.
        return Resolved(uri, StreamInfo(kind, track.format?.uppercase(), null, null, track.id), cacheKey = track.id)
    }

    override suspend fun probe() = if (hasPermission()) "${scan().size} tracks on device" else throw SourceUnavailableException("permission not granted")

    companion object {
        fun formatOf(mime: String): String? = when {
            "flac" in mime -> "flac"
            "wav" in mime || "x-wav" in mime -> "wav"
            "alac" in mime -> "alac"
            "aiff" in mime -> "aiff"
            "mpeg" in mime || "mp3" in mime -> "mp3"
            "mp4" in mime || "m4a" in mime || "aac" in mime -> "m4a"
            "ogg" in mime || "opus" in mime -> "ogg"
            else -> mime.substringAfter('/').ifBlank { null }
        }
    }
}
