package com.ravi.musicspot.data

import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.ravi.musicspot.logic.DjMode
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Pack 6 Part 2: per-track tempo and beat grid for DJ Mode, kept on the device (`bpm.tsv` in the app's files dir).
 * A track is analysed once: a known grid or "unknown" (no clear pulse) is stored and never analysed again. A failed
 * analysis (network, decoder) is NOT stored, so it can be tried again on a later play.
 *
 * Append-only TSV like LoudnessStore: `id \t U` (unknown) or `id \t bpm \t firstBeatMs \t tailBpm \t tailBeatMs`.
 */
class BpmStore(private val file: File) {
    /** What the store knows about a track. [beats] null = analysed, no confident tempo (unknown). */
    data class Entry(val beats: DjMode.TrackBeats?)

    private val map = ConcurrentHashMap<String, Entry>()
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "bpm-store").apply { isDaemon = true } }
    @Volatile var loaded = false; private set

    /** Reads the file on the store's thread (call once). */
    fun load() { io.execute { loadNow() } }

    /** Blocking read (tests and the store thread). */
    fun loadNow() {
        runCatching {
            if (file.exists()) {
                val lines = file.readLines()
                val got = LinkedHashMap<String, Entry>()
                for (l in lines) parse(l)?.let { (id, e) -> got.remove(id); got[id] = e }
                val keep = got.entries.toList().takeLast(MAX)
                keep.forEach { (k, v) -> map.putIfAbsent(k, v) }
                if (lines.size > keep.size * 2 + 100) file.writeText(keep.joinToString("") { (k, v) -> line(k, v) })
            }
        }
        loaded = true
    }

    fun get(id: String): Entry? = map[id]
    /** Known tempo for [id], or null (unknown or not analysed yet). */
    fun beats(id: String): DjMode.TrackBeats? = map[id]?.beats
    fun has(id: String): Boolean = map.containsKey(id)

    fun put(id: String, beats: DjMode.TrackBeats?) {
        if (id.isEmpty() || id.contains('\t') || id.contains('\n')) return
        if (beats != null && !(beats.bpm.isFinite() && beats.tailBpm.isFinite())) return
        val e = Entry(beats)
        map[id] = e
        io.execute { runCatching { file.parentFile?.mkdirs(); file.appendText(line(id, e)) } }
    }

    /** Waits for queued writes (tests). */
    fun flushForTest() { io.submit {}.get() }

    companion object {
        const val MAX = 5_000

        internal fun line(id: String, e: Entry): String {
            val b = e.beats ?: return "$id\tU\n"
            return "$id\t${"%.3f".format(java.util.Locale.ROOT, b.bpm)}\t${b.firstBeatMs}\t${"%.3f".format(java.util.Locale.ROOT, b.tailBpm)}\t${b.tailBeatMs}\n"
        }

        internal fun parse(l: String): Pair<String, Entry>? {
            val p = l.trimEnd('\n').split('\t')
            if (p.size == 2 && p[1] == "U" && p[0].isNotEmpty()) return p[0] to Entry(null)
            if (p.size != 5 || p[0].isEmpty()) return null
            val bpm = p[1].toDoubleOrNull() ?: return null
            val fb = p[2].toLongOrNull() ?: return null
            val tb = p[3].toDoubleOrNull() ?: return null
            val tbeat = p[4].toLongOrNull() ?: return null
            return p[0] to Entry(DjMode.TrackBeats(bpm, fb, tb, tbeat))
        }

        @Volatile private var shared: BpmStore? = null
        /** The app's store (PlaybackService). */
        fun init(dir: File): BpmStore = shared ?: synchronized(this) {
            shared ?: BpmStore(File(dir, "bpm.tsv")).also { it.load(); shared = it }
        }
    }
}

/**
 * Decodes two short windows of a track (the first [WINDOW_MS] and the last [WINDOW_MS]) and runs [DjMode.analyze] on
 * each. Streaming: decoded PCM goes straight into [DjMode.OnsetEnvelope] and is dropped, so memory stays at the codec's
 * own buffers plus ~6 000 floats per window (fine on a 3 GB Android 10 phone). Blocking: call on a background thread.
 */
object BpmAnalyzer {
    const val WINDOW_MS = 30_000L
    /** Songs shorter than this are not analysed (they never get a DJ transition: crossfade skips short songs). */
    const val MIN_TRACK_MS = 70_000L

    sealed interface Outcome {
        /** Decoded and analysed: a grid, or null for no confident pulse (stored as unknown). */
        data class Done(val beats: DjMode.TrackBeats?) : Outcome
        /** Could not decode (network, codec). Not stored; a later play tries again. */
        data class Failed(val why: String) : Outcome
    }

    fun analyze(source: MediaDataSource, durationHintMs: Long): Outcome {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(source)
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: return Outcome.Failed("no audio track")
            ex.selectTrack(track)
            val format = ex.getTrackFormat(track)
            // The container's duration wins over the hint (metadata durations can be missing or rounded).
            val durationMs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) / 1000 else durationHintMs
            if (durationMs <= 0) return Outcome.Failed("duration unknown")
            if (durationMs < MIN_TRACK_MS) return Outcome.Done(null)
            val head = decodeWindow(ex, format, 0L) ?: return Outcome.Failed("decode failed (head)")
            val tailStart = (durationMs - WINDOW_MS - 1_000).coerceAtLeast(WINDOW_MS)
            val tail = decodeWindow(ex, format, tailStart) ?: return Outcome.Failed("decode failed (tail)")
            val h = DjMode.analyze(head.first)
            val t = DjMode.analyze(tail.first)
            return Outcome.Done(DjMode.combine(h, t, tail.second))
        } catch (e: Exception) {
            return Outcome.Failed("${e.javaClass.simpleName}: ${e.message?.take(60)}")
        } finally {
            runCatching { ex.release() }
        }
    }

    /** Decodes [WINDOW_MS] from [startMs]. Returns the onset curve and the real start (first decoded sample) in ms. */
    private fun decodeWindow(ex: MediaExtractor, format: MediaFormat, startMs: Long): Pair<DjMode.OnsetEnvelope, Long>? {
        val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
        ex.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        val codec = MediaCodec.createDecoderByType(mime)
        try {
            codec.configure(format, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var env: DjMode.OnsetEnvelope? = null
            var realStartMs = -1L
            var channels = format.getIntegerOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: 2
            var rate = format.getIntegerOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: 44_100
            var float = false
            var inputDone = false
            var shorts = ShortArray(0)
            var floats = FloatArray(0)
            val endUs = (startMs + WINDOW_MS) * 1000
            var idle = 0
            while (true) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i) ?: return null
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0 || ex.sampleTime > endUs) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        channels = f.getIntegerOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: channels
                        rate = f.getIntegerOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: rate
                        float = android.os.Build.VERSION.SDK_INT >= 24 && f.getIntegerOrNull(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT
                    }
                    o >= 0 -> {
                        idle = 0
                        val buf = codec.getOutputBuffer(o)
                        if (buf != null && info.size > 0 && info.presentationTimeUs <= endUs) {
                            if (env == null) { env = DjMode.OnsetEnvelope(rate); realStartMs = info.presentationTimeUs / 1000 }
                            buf.position(info.offset); buf.limit(info.offset + info.size)
                            if (float) {
                                val n = info.size / 4
                                if (floats.size < n) floats = FloatArray(n)
                                buf.order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer().get(floats, 0, n)
                                val frames = n / channels.coerceAtLeast(1)
                                val mono = FloatArray(frames) { f -> var s = 0f; for (c in 0 until channels) s += floats[f * channels + c]; s / channels }
                                env.feed(mono)
                            } else {
                                val n = info.size / 2
                                if (shorts.size < n) shorts = ShortArray(n)
                                buf.order(java.nio.ByteOrder.nativeOrder()).asShortBuffer().get(shorts, 0, n)
                                env.feedPcm16(shorts, n, channels)
                            }
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                    else -> if (inputDone && ++idle > 300) break // ~3 s with no output after end of input
                }
            }
            return env?.let { it to realStartMs.coerceAtLeast(0) }
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
    }

    private fun MediaFormat.getIntegerOrNull(key: String): Int? = if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null
}

/**
 * Random-access [MediaDataSource] over a Media3 [DataSource.Factory], so the analyser reads a song through the same
 * resolver, headers, chunking and media cache as the player. Reads in [BLOCK] blocks, keeps the last few in memory.
 */
class Media3MediaDataSource(private val factory: DataSource.Factory, private val uri: Uri) : MediaDataSource() {
    private val blocks = object : LinkedHashMap<Long, ByteArray>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?) = size > KEEP
    }
    private var size: Long = Long.MIN_VALUE

    @Synchronized override fun getSize(): Long {
        if (size == Long.MIN_VALUE) {
            val ds = factory.createDataSource()
            try {
                val len = ds.open(DataSpec.Builder().setUri(uri).setPosition(0).build())
                size = if (len == C.LENGTH_UNSET.toLong()) -1L else len
                blocks[0L] = readFully(ds, BLOCK)
            } finally { runCatching { ds.close() } }
        }
        return size
    }

    @Synchronized override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val total = getSize()
        if (total >= 0 && position >= total) return -1
        var done = 0
        var pos = position
        while (done < length) {
            val start = pos / BLOCK * BLOCK
            val block = blocks[start] ?: load(start).also { blocks[start] = it }
            val within = (pos - start).toInt()
            if (within >= block.size) break
            val n = minOf(length - done, block.size - within)
            System.arraycopy(block, within, buffer, offset + done, n)
            done += n; pos += n
            if (block.size < BLOCK) break // last block
        }
        return if (done == 0) -1 else done
    }

    private fun load(start: Long): ByteArray {
        val ds = factory.createDataSource()
        try {
            val len = if (size > 0) minOf(BLOCK.toLong(), size - start) else BLOCK.toLong()
            if (len <= 0) return ByteArray(0)
            ds.open(DataSpec.Builder().setUri(uri).setPosition(start).setLength(len).build())
            return readFully(ds, len.toInt())
        } finally { runCatching { ds.close() } }
    }

    private fun readFully(ds: DataSource, max: Int): ByteArray {
        val out = ByteArray(max)
        var n = 0
        while (n < max) {
            val r = ds.read(out, n, max - n)
            if (r == C.RESULT_END_OF_INPUT) break
            n += r
        }
        return if (n == max) out else out.copyOf(n)
    }

    override fun close() { synchronized(this) { blocks.clear() } }

    private companion object { const val BLOCK = 256 * 1024; const val KEEP = 6 }
}
