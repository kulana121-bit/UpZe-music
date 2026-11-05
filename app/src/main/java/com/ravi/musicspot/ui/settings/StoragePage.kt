package com.ravi.musicspot.ui.settings

import android.os.StatFs
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.SingletonImageLoader
import com.ravi.musicspot.Graph
import com.ravi.musicspot.model.SourceKind
import com.ravi.musicspot.playback.PlaybackService
import com.ravi.musicspot.ui.Pills
import com.ravi.musicspot.ui.components.formatBytes
import com.ravi.musicspot.ui.theme.Ms
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File

// ---- Storage -----------------------------------------------------------------------------------------------------

private data class Sizes(val media: Long, val downloads: Long, val lyrics: Long, val images: Long, val free: Long, val total: Long)

private fun dirSize(f: File?): Long = f?.takeIf { it.exists() }?.walkTopDown()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L

@Composable
fun StoragePage() {
    val ctx = LocalContext.current
    val s by Graph.settings.state.collectAsState()
    val dls by Graph.downloads.items.collectAsState()
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var sizes by remember { mutableStateOf<Sizes?>(null) }
    var confirm by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(refresh, dls.size) {
        sizes = withContext(Dispatchers.IO) {
            val st = runCatching { StatFs(ctx.filesDir.absolutePath) }.getOrNull()
            val images = runCatching { SingletonImageLoader.get(ctx).diskCache?.size }.getOrNull() ?: dirSize(File(ctx.cacheDir, "image_cache"))
            Sizes(PlaybackService.cacheBytes(ctx), Graph.downloads.usedBytes(), Graph.lyricsCache.sizeBytes(), images,
                st?.availableBytes ?: 0L, st?.totalBytes ?: 0L)
        }
    }

    fun clear(what: String) = scope.launch {
        withContext(Dispatchers.IO) {
            when (what) {
                "media" -> PlaybackService.clearCache(ctx)
                "downloads" -> Graph.downloads.deleteAll()
                "lyrics" -> Graph.lyricsCache.clear()
                "images" -> runCatching { SingletonImageLoader.get(ctx).let { l -> l.diskCache?.clear(); l.memoryCache?.clear() } }
            }
        }
        Pills.show("Cleared"); refresh++
    }

    SettingsScaffold("Storage") {
        item {
            Group("This device") {
                val z = sizes
                if (z == null) Row(Modifier.fillMaxWidth().padding(16.dp)) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) }
                else {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        val used = z.media + z.downloads + z.lyrics + z.images
                        Text("UpZe uses ${formatBytes(used)}", style = MaterialTheme.typography.titleMedium)
                        if (z.total > 0) {
                            Text("${formatBytes(z.free)} free of ${formatBytes(z.total)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.size(8.dp))
                            LinearProgressIndicator({ /* v2.0.14 */ if (z.total > 0) (1f - z.free.toFloat() / z.total).coerceIn(0f, 1f) else 0f }, Modifier.fillMaxWidth())
                        }
                    }
                    RowDivider()
                    SizeRow("Downloads", "${dls.values.count { it.state == com.ravi.musicspot.data.DownloadEntry.State.DONE }} songs saved for offline", z.downloads, "Delete all") { confirm = "downloads" }
                    RowDivider()
                    SizeRow("Streaming cache", "Recently played audio, limit ${limitLabel(s.cacheLimitMb)}", z.media, "Clear") { confirm = "media" }
                    RowDivider()
                    SizeRow("Artwork cache", "Album art and avatars", z.images, "Clear") { clear("images") }
                    RowDivider()
                    SizeRow("Lyrics cache", "Saved lyrics lookups", z.lyrics, "Clear") { clear("lyrics") }
                }
            }
        }
        item {
            Group("Streaming cache limit", footer = "A new limit applies the next time the app starts. Older audio is removed first when the cache is full.") {
                ChoiceRow(null, listOf(256, 512, 1024, 2048, 4096), s.cacheLimitMb, ::limitLabel) { v -> Graph.settings.update { it.copy(cacheLimitMb = v) } }
            }
        }
    }

    com.ravi.musicspot.ui.motion.MotionAlertDialog(item = confirm, onDismissRequest = { confirm = null },
        title = { what -> Text(if (what == "downloads") "Delete all downloads?" else "Clear the streaming cache?") },
        text = { what -> Text(if (what == "downloads") "Downloaded songs are removed from this phone and won't play offline until you download them again." else "Songs will stream again next time you play them.") },
        confirmButton = { what -> TextButton(onClick = { clear(what); confirm = null }) { Text(if (what == "downloads") "Delete" else "Clear") } },
        dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } })
}

private fun limitLabel(mb: Int) = if (mb >= 1024) "${mb / 1024} GB" else "$mb MB"

@Composable
private fun SizeRow(title: String, subtitle: String, bytes: Long, action: String, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Text(formatBytes(bytes), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        TextButton(onClick = onAction, enabled = bytes > 0) { Text(action) }
    }
}

// ---- Diagnostics -------------------------------------------------------------------------------------------------

private data class Probe(val running: Boolean = false, val ok: Boolean? = null, val ms: Long? = null, val detail: String? = null)

@Composable
fun DiagnosticsPage() {
    val s by Graph.settings.state.collectAsState()
    val online by Graph.online.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val results = remember { mutableStateMapOf<SourceKind, Probe>() }
    val kinds = (s.sourcePriority + Graph.sources.sources.keys).distinct().filter { it in Graph.sources.sources }
    var running by remember { mutableStateOf(false) }

    suspend fun probe(k: SourceKind) {
        val src = Graph.sources.source(k) ?: return
        results[k] = Probe(running = true)
        val t0 = System.nanoTime()
        results[k] = try {
            val d = withTimeout(15_000) { withContext(Dispatchers.IO) { src.probe() } }
            Probe(ok = true, ms = (System.nanoTime() - t0) / 1_000_000, detail = d)
        } catch (e: CancellationException) {
            if (e is kotlinx.coroutines.TimeoutCancellationException) Probe(ok = false, ms = 15_000, detail = "No answer within 15 s") else throw e
        } catch (e: Exception) {
            Probe(ok = false, ms = (System.nanoTime() - t0) / 1_000_000, detail = e.message ?: e.javaClass.simpleName)
        }
    }
    fun runAll() { running = true; scope.launch { coroutineScope { kinds.map { k -> async { probe(k) } }.awaitAll() }; running = false } }
    LaunchedEffect(Unit) { runAll() }

    SettingsScaffold("Diagnostics") {
        item {
            Group {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (online) "Online${if (Graph.metered) " · metered (mobile data)" else " · unmetered"}" else "Offline", style = MaterialTheme.typography.titleMedium)
                    Text("YouTube Music: ${if (Graph.tube.signedIn) "signed in" else "guest"} · Quality now: ${s.effectiveQuality(Graph.metered).label}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Each test makes a real request to the source (a small search or a server ping) and measures the round trip.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                        Button(onClick = { runAll() }, enabled = !running) { Text(if (running) "Testing…" else "Run all tests") }
                        OutlinedButton(onClick = {
                            val report = buildString {
                                appendLine("UpZe ${com.ravi.musicspot.BuildConfig.VERSION_NAME} diagnostics")
                                appendLine("Network: ${if (online) "online" else "offline"}${if (Graph.metered) ", metered" else ""}")
                                kinds.forEach { k -> val r = results[k]; appendLine("${k.label} [${if (k in s.enabledSources) "on" else "off"}]: ${when (r?.ok) { true -> "OK ${r.ms} ms"; false -> "FAIL ${r.ms} ms"; null -> "not run" }} ${r?.detail ?: ""}") }
                            }
                            ctx.getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(android.content.ClipData.newPlainText("UpZe diagnostics", report)); Pills.show("Report copied")
                        }, enabled = !running && results.isNotEmpty()) { Text("Copy report") }
                    }
                }
            }
        }
        item {
            Group("Sources") {
                kinds.forEachIndexed { i, k ->
                    if (i > 0) RowDivider()
                    val r = results[k] ?: Probe()
                    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        val dot = when (r.ok) { true -> Ms.Success; false -> Ms.Error; null -> MaterialTheme.colorScheme.outline }
                        androidx.compose.foundation.layout.Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(k.label + if (k !in s.enabledSources) " (off)" else "", style = MaterialTheme.typography.titleSmall)
                            Text(if (r.running) "Testing…" else r.detail ?: "Not tested", style = MaterialTheme.typography.bodySmall,
                                color = if (r.ok == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        if (r.running) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else r.ms?.let { Text("$it ms", style = MaterialTheme.typography.labelLarge, color = if (r.ok == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
                        TextButton(onClick = { scope.launch { probe(k) } }, enabled = !r.running) { Text("Test") }
                    }
                }
            }
        }
    }
}

