package com.ravi.musicspot.core

import android.content.Context
import android.content.Intent
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * v2.0.4 playback diagnostics: an in-memory ring buffer (last [MAX] lines) mirrored to filesDir/playback-log.txt.
 * Every line is passed through [redact] (cookies, tokens, signatures, query strings removed; URLs cut to host + path).
 * One play attempt carries a [Attempt] coroutine element so deep code (YouTube resolver, JioSaavn fallback) can tag
 * lines and record the step summary that the player's error message shows.
 */
object PlaybackLog {
    const val MAX = 300
    private val lines = ArrayDeque<String>(MAX + 1)
    private val _version = MutableStateFlow(0)
    /** Bumps on every new line (UI refresh). */
    val version: StateFlow<Int> = _version
    @Volatile private var file: File? = null
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "playback-log").apply { isDaemon = true } }
    private val fmt = ThreadLocal.withInitial { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }

    /** Per-attempt context: a short tag for its lines and the per-source step summary. */
    class Attempt(val tag: String) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Attempt>
        private val steps = LinkedHashMap<String, String>()
        @Synchronized fun step(source: String, what: String) { steps[source] = what }
        @Synchronized fun summary(): String = steps.entries.joinToString(" · ") { "${it.key}: ${it.value}" }
    }

    /** The last failure summary per track id (bounded), used when ExoPlayer itself reports the error. */
    private val summaries = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 32
    }

    fun init(ctx: Context) {
        val f = File(ctx.filesDir, "playback-log.txt")
        file = f
        runCatching { if (f.exists()) synchronized(lines) { f.readLines().takeLast(MAX).forEach { lines.addLast(it) } } }
    }

    fun add(msg: String, tag: String? = null) {
        val line = "${fmt.get()!!.format(Date())} ${tag?.let { "[$it] " } ?: ""}${redact(msg)}".take(600)
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > MAX) lines.removeFirst()
        }
        _version.value = _version.value + 1
        val f = file ?: return
        val snapshot = text()
        runCatching { writer.execute { runCatching { f.writeText(snapshot) } } }
    }

    /** Logs with the current attempt's tag, if any. */
    suspend fun log(msg: String) = add(msg, coroutineContext[Attempt]?.tag)
    suspend fun step(source: String, what: String) { coroutineContext[Attempt]?.step(source, what) }
    suspend fun attempt(): Attempt? = coroutineContext[Attempt]

    fun remember(trackId: String, summary: String) { if (summary.isNotBlank()) synchronized(summaries) { summaries[trackId] = summary } }
    fun forget(trackId: String) { synchronized(summaries) { summaries.remove(trackId) } }
    fun summaryFor(trackId: String?): String? = trackId?.let { synchronized(summaries) { summaries[it] } }

    fun text(): String = synchronized(lines) { lines.joinToString("\n") }
    fun size(): Int = synchronized(lines) { lines.size }
    fun clear() { synchronized(lines) { lines.clear() }; _version.value = _version.value + 1; file?.let { f -> runCatching { writer.execute { runCatching { f.writeText("") } } } } }

    fun shareIntent(): Intent = Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "UpZe playback log")
        putExtra(Intent.EXTRA_TEXT, text().ifBlank { "(playback log is empty)" })
    }, "Share playback log").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private val URL = Regex("""https?://[^\s"'<>]+""")
    private val SECRET = Regex("""(?i)\b(cookie|set-cookie|authorization|sapisid|__secure-[0-9a-z]+|sp_dc|x-goog-[a-z-]+|visitor_?data|token|access_token|key|sig|signature|pot|n)\s*[=:]\s*[^\s;,&"]+""")

    /** Cuts URLs to host + path and blanks cookie/token-looking key=value pairs. Pure; unit-tested. */
    fun redact(s: String): String {
        val noUrls = URL.replace(s) { m ->
            val u = m.value
            val rest = u.substringAfter("://")
            val hostPath = rest.substringBefore('?').substringBefore('#')
            "${u.substringBefore("://")}://${hostPath.take(80)}${if ('?' in rest) "?…" else ""}"
        }
        return SECRET.replace(noUrls) { "${it.groupValues[1]}=<redacted>" }
    }
}
