package com.ravi.musicspot.data

import android.content.Context
import android.net.Uri
import com.ravi.musicspot.BuildConfig
import com.ravi.musicspot.Graph
import com.ravi.musicspot.core.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/**
 * 1.0.2: one JSON file with everything the user built up in UpZe: settings (theme, glass, sources, EQ, lyrics…),
 * liked songs, playlists, saved albums/artists, listening history, play counts, "Your artists" and the last queue.
 * Not included on purpose: account sign-ins (tokens stay on the phone), downloaded audio files and caches.
 */
@Serializable
data class BackupFile(
    // No defaults: always written, so any other JSON file fails the check below.
    val app: String,
    val format: Int,
    val appVersion: String = "",
    val createdMs: Long = 0,
    val settings: AppSettings? = null,
    val library: LibraryData? = null,
    val playStats: PlayStatsData? = null,
    val seedArtists: List<SeedArtist>? = null,
    val queue: QueueSnapshot? = null,
) {
    companion object { const val APP = "UpZe"; const val FORMAT = 1 }
}

object Backup {
    data class Summary(val liked: Int, val playlists: Int, val history: Int)

    fun fileName(nowMs: Long = System.currentTimeMillis()): String =
        "UpZe-backup-" + java.text.SimpleDateFormat("yyyy-MM-dd-HHmm", java.util.Locale.US).format(java.util.Date(nowMs)) + ".json"

    fun snapshot(nowMs: Long = System.currentTimeMillis()): BackupFile = BackupFile(
        app = BackupFile.APP, format = BackupFile.FORMAT, appVersion = BuildConfig.VERSION_NAME, createdMs = nowMs,
        settings = Graph.settings.value, library = Graph.library.data.value, playStats = Graph.playStats.data.value,
        seedArtists = SeedArtists.state.value, queue = Graph.queueStore.read(),
    )

    fun encode(b: BackupFile): String = Http.json.encodeToString(BackupFile.serializer(), b)

    /** Throws [IllegalArgumentException] with a readable message when the file is not an UpZe backup. */
    fun decode(text: String): BackupFile {
        val b = runCatching { Http.json.decodeFromString(BackupFile.serializer(), text) }
            .getOrElse { throw IllegalArgumentException("This file isn't an UpZe backup.") }
        require(b.app == BackupFile.APP) { "This file isn't an UpZe backup." }
        require(b.format <= BackupFile.FORMAT) { "This backup is from a newer UpZe. Update the app, then restore." }
        return b
    }

    suspend fun export(ctx: Context, uri: Uri): Summary = withContext(Dispatchers.IO) {
        val b = snapshot()
        val out = ctx.contentResolver.openOutputStream(uri, "wt") ?: throw IllegalStateException("Couldn't open the file")
        out.use { it.write(encode(b).toByteArray()) }
        summary(b)
    }

    /** Replaces the current library, settings, play counts and artists with the backup's (sign-ins are kept). The queue is
     * stored in the file but not restored (it would interrupt what is playing). */
    suspend fun restore(ctx: Context, uri: Uri): Summary {
        val b = withContext(Dispatchers.IO) {
            val text = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } ?: throw IllegalStateException("Couldn't open the file")
            decode(text)
        }
        b.settings?.let { s -> Graph.settings.update { cur -> s.copy(setupDone = true, onboarded = true, guest = cur.guest) } }
        b.library?.let { Graph.library.replace(it) }
        b.playStats?.let { Graph.playStats.replace(it) }
        b.seedArtists?.let { SeedArtists.replace(it) }
        return summary(b)
    }

    private fun summary(b: BackupFile) = Summary(b.library?.liked?.size ?: 0, b.library?.playlists?.size ?: 0, b.library?.history?.size ?: 0)
}
