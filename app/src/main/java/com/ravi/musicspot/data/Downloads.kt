package com.ravi.musicspot.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.ravi.musicspot.Graph
import com.ravi.musicspot.R
import com.ravi.musicspot.core.Http
import com.ravi.musicspot.core.Http.await
import com.ravi.musicspot.core.JsonFile
import com.ravi.musicspot.model.StreamInfo
import com.ravi.musicspot.model.Track
import com.ravi.musicspot.source.Resolved
import kotlinx.coroutines.Dispatchers // v2.0.14:
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext // v2.0.14:
import kotlinx.serialization.Serializable
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit // v2.0.14:
import kotlin.math.abs

@Serializable
data class DownloadEntry(
    val track: Track,
    val state: State = State.QUEUED,
    val path: String? = null,
    val bytes: Long = 0,
    val total: Long = 0,
    val info: StreamInfo? = null,
    val error: String? = null,
    val atMs: Long = System.currentTimeMillis(),
) {
    enum class State { QUEUED, RUNNING, DONE, FAILED }
    val progress: Float get() = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else 0f
}

@Serializable
data class DownloadIndex(val entries: Map<String, DownloadEntry> = emptyMap())

/** Real downloads: actual audio files in filesDir/downloads, one WorkManager job and one notification per song. */
class DownloadsRepo(private val ctx: Context) {
    val dir = File(ctx.filesDir, "downloads")
    private val file = JsonFile(File(ctx.filesDir, "downloads/index.json"), DownloadIndex.serializer(), 500)
    private val _items = MutableStateFlow<Map<String, DownloadEntry>>(emptyMap())
    val items: StateFlow<Map<String, DownloadEntry>> = _items

    fun load() {
        val idx = file.read() ?: return
        // A file deleted outside the app (or a half-written one) is not a download.
        _items.value = idx.entries.mapValues { (_, e) ->
            when {
                e.state == DownloadEntry.State.DONE && e.path?.let { File(it).isFile } != true -> e.copy(state = DownloadEntry.State.FAILED, error = "File missing")
                e.state == DownloadEntry.State.RUNNING -> e.copy(state = DownloadEntry.State.QUEUED)
                else -> e
            }
        }
    }

    private fun edit(f: (Map<String, DownloadEntry>) -> Map<String, DownloadEntry>) { _items.update(f); file.save { DownloadIndex(_items.value) } }
    fun update(id: String, f: (DownloadEntry) -> DownloadEntry) = edit { m -> m[id]?.let { m + (id to f(it)) } ?: m }

    fun isDownloaded(id: String) = _items.value[id]?.state == DownloadEntry.State.DONE

    /** A finished download as a playable file:// source. */
    fun resolvedFor(id: String): Resolved? {
        val e = _items.value[id] ?: return null
        if (e.state != DownloadEntry.State.DONE) return null
        val f = File(e.path ?: return null).takeIf { it.isFile } ?: return null
        val info = (e.info ?: StreamInfo(e.track.source)).let { it.copy(codec = it.codec, sourceTrackId = id) }
        return Resolved(Uri.fromFile(f).toString(), info, cacheKey = "file:$id")
    }

    fun enqueue(t: Track, wifiOnly: Boolean) {
        if (_items.value[t.id]?.state == DownloadEntry.State.DONE) return
        edit { it + (t.id to DownloadEntry(t)) }
        val req = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf(KEY_ID to t.id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
            .addTag(TAG).build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(workName(t.id), ExistingWorkPolicy.KEEP, req)
    }

    fun cancel(id: String) { WorkManager.getInstance(ctx).cancelUniqueWork(workName(id)); delete(id) }
    fun retry(id: String, wifiOnly: Boolean) { _items.value[id]?.let { edit { m -> m - id }; enqueue(it.track, wifiOnly) } }

    fun delete(id: String) {
        _items.value[id]?.path?.let { File(it).delete() }
        File(dir, safe(id) + ".part").delete()
        edit { it - id }
    }

    fun deleteAll() { _items.value.keys.toList().forEach { cancel(it) } }

    fun usedBytes(): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    companion object {
        const val KEY_ID = "id"
        const val TAG = "musicspot-download"
        fun workName(id: String) = "dl:$id"
        fun safe(id: String) = id.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120)
        fun notificationId(id: String) = 20_000 + abs(id.hashCode() % 50_000)
        const val CHANNEL = "downloads"
    }
}

/** Downloads one song: resolves it through the normal source router, then fetches it in 1 MiB ranges. */
class DownloadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    private val repo get() = Graph.downloads

    override suspend fun doWork(): Result {
        Graph.awaitReady()
        val id = inputData.getString(DownloadsRepo.KEY_ID) ?: return Result.failure()
        val entry = repo.items.value[id] ?: return Result.failure()
        val t = entry.track
        runCatching { setForeground(foreground(t, 0f)) }
        repo.update(id) { it.copy(state = DownloadEntry.State.RUNNING, error = null) }
        return try {
            val settings = Graph.settings.value
            val r = Graph.sources.resolve(t, settings.downloadQuality, metered = false)
            if (!r.uri.startsWith("https://")) throw IOException("This source can't be downloaded (${r.info.source.label})")
            val ext = when (r.info.codec?.lowercase()) { "opus" -> "webm"; "aac" -> "m4a"; "flac" -> "flac"; "mp3" -> "mp3"; else -> "audio" }
            repo.dir.mkdirs()
            val part = File(repo.dir, DownloadsRepo.safe(id) + ".part")
            val out = File(repo.dir, DownloadsRepo.safe(id) + "." + ext)
            withContext(Dispatchers.IO) { // v2.0.14: blocking fetch off the worker's default dispatcher
                fetchRanged(r, part) { done, total ->
                    repo.update(id) { it.copy(bytes = done, total = total) }
                    runCatching { setForeground(foreground(t, if (total > 0) done.toFloat() / total else 0f)) }
                }
            }
            if (part.length() < 1024) throw IOException("Download was empty")
            if (!part.renameTo(out)) throw IOException("Couldn't save the file")
            repo.update(id) { it.copy(state = DownloadEntry.State.DONE, path = out.absolutePath, bytes = out.length(), total = out.length(), info = r.info) }
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            repo.update(id) { it.copy(state = DownloadEntry.State.QUEUED) } // v2.0.14: cancelled/stopped → back to queued
            throw e
        } catch (e: Exception) {
            repo.update(id) { it.copy(state = DownloadEntry.State.FAILED, error = e.message ?: e.javaClass.simpleName) }
            if (runAttemptCount < 2 && e is IOException) Result.retry() else Result.failure()
        }
    }

    private suspend fun fetchRanged(r: Resolved, part: File, progress: suspend (Long, Long) -> Unit) {
        var offset = if (part.isFile) part.length() else 0L
        var total = -1L
        FileOutputStream(part, offset > 0).use { out ->
            while (total < 0 || offset < total) {
                val end = offset + CHUNK - 1
                val req = Request.Builder().url(r.uri).header("Range", "bytes=$offset-$end").apply { r.headers.forEach { (k, v) -> header(k, v) } }.build()
                dlClient.newCall(req).await().use { resp -> // v2.0.14: download client with callTimeout
                    if (resp.code == 416) { total = offset; return@use }
                    if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                    // v2.0.14: server ignored Range on a resume → restart the .part from zero.
                    if (resp.code == 200 && offset > 0) { out.flush(); out.channel.truncate(0); offset = 0 }
                    // v2.0.14: a 206 must start exactly where we asked.
                    if (resp.code == 206) {
                        val start = resp.header("Content-Range")?.substringAfter("bytes ", "")?.substringBefore('-')?.trim()?.toLongOrNull()
                        if (start != null && start != offset) throw IOException("Content-Range starts at $start, expected $offset")
                    }
                    total = resp.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
                        ?: resp.body?.contentLength()?.takeIf { resp.code == 200 }?.let { it + offset } ?: -1L
                    val body = resp.body ?: throw IOException("Empty response")
                    val n = body.byteStream().use { input -> input.copyTo(out) }
                    offset += n
                    if (resp.code == 200) total = offset // server ignored Range and sent everything
                    if (n == 0L) total = offset
                }
                progress(offset, total)
            }
            out.fd.sync()
        }
    }

    private fun foreground(t: Track, p: Float): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(DownloadsRepo.CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(DownloadsRepo.CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW))
        val n = NotificationCompat.Builder(applicationContext, DownloadsRepo.CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome).setContentTitle(t.title).setContentText("Downloading · ${t.artist}")
            .setOnlyAlertOnce(true).setOngoing(true).setProgress(100, (p * 100).toInt(), p <= 0f).build()
        // A distinct id per song, so parallel downloads never overwrite each other's notification (fixes OpenTune B6).
        val nid = DownloadsRepo.notificationId(t.id)
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(nid, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(nid, n)
    }

    companion object {
        private const val CHUNK = 1L shl 20
        // v2.0.14: dedicated download client so one stalled range can't hang the worker forever.
        private val dlClient by lazy { Http.client.newBuilder().callTimeout(3, TimeUnit.MINUTES).build() }
    }
}
