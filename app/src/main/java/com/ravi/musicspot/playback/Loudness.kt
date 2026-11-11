@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.ravi.musicspot.playback

import com.ravi.musicspot.dsp.ReplayGainMode
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.tan

/**
 * 1.0.10 volume levelling: ReplayGain tags and loudness normalisation, decided in ONE place ([GainPlan]) and applied
 * by ONE gain stage (EngineAudioProcessor), so the two can never stack.
 *
 * Precedence (fixed): a ReplayGain value for the chosen mode wins; loudness normalisation only fills in when the
 * track has none (or ReplayGain is Off). Neither: unity gain.
 */

/** A gain in dB with the peak (linear, 1.0 = full scale) it was measured against, when known. */
data class Gain(val gainDb: Double, val peak: Double? = null)

/** ReplayGain values read from a file's or stream's tags. */
data class ReplayGainTags(val track: Gain? = null, val album: Gain? = null) {
    val isEmpty: Boolean get() = track == null && album == null
}

/** A track's measured integrated loudness (LUFS, ITU-R BS.1770 gated) and sample peak. */
data class Measured(val lufs: Double, val peak: Double)

object GainPlan {
    /** Loudness target, equal to the ReplayGain 2.0 reference, so tagged and untagged tracks land at the same level. */
    const val TARGET_LUFS = -18.0
    /** Quiet tracks are never raised by more than this. */
    const val MAX_BOOST_DB = 6.0
    /** A boost always leaves this much room under full scale above the track's known peak. */
    const val HEADROOM_DB = 1.0
    /** Sanity floor for absurd tag values. */
    const val MAX_CUT_DB = -24.0

    enum class Source { NONE, REPLAY_GAIN, LOUDNESS }
    data class Decision(val gainDb: Double, val source: Source)

    /**
     * The one gain for a track. [provisional] = the loudness is still a running estimate (first play, still
     * measuring): only cuts are allowed then, since the peak isn't known yet.
     */
    fun decide(mode: ReplayGainMode, normalize: Boolean, tags: ReplayGainTags?, measured: Measured?, provisional: Boolean = false): Decision {
        val rg = when (mode) {
            ReplayGainMode.OFF -> null
            ReplayGainMode.TRACK -> tags?.track ?: tags?.album
            ReplayGainMode.ALBUM -> tags?.album ?: tags?.track
        }
        if (rg != null) return Decision(safe(rg.gainDb, rg.peak), Source.REPLAY_GAIN)
        if (normalize && measured != null && measured.lufs.isFinite()) {
            val raw = TARGET_LUFS - measured.lufs
            val g = if (provisional) min(raw, 0.0).coerceAtLeast(MAX_CUT_DB) else safe(raw, measured.peak)
            return Decision(g, Source.LOUDNESS)
        }
        return Decision(0.0, Source.NONE)
    }

    /** Cuts pass (floored at [MAX_CUT_DB]); boosts need a known peak and stay under full scale minus [HEADROOM_DB]. */
    fun safe(gainDb: Double, peak: Double?): Double {
        if (!gainDb.isFinite()) return 0.0
        if (gainDb <= 0.0) return gainDb.coerceAtLeast(MAX_CUT_DB)
        val room = if (peak != null && peak > 0.0) -20.0 * log10(peak) - HEADROOM_DB else 0.0
        return min(gainDb, min(MAX_BOOST_DB, max(room, 0.0)))
    }

    fun linear(db: Double): Float = 10.0.pow(db / 20.0).toFloat()
}

/** Parses ReplayGain (and Opus R128) tags. Keys are case-insensitive; values like "-7.89 dB", "+1,20 dB", "0.988". */
object ReplayGain {
    private val number = Regex("[-+\u2212]?\\d+(?:[.,]\\d+)?")

    private fun num(v: String): Double? = number.find(v)?.value?.replace('\u2212', '-')?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it.isFinite() }

    fun fromPairs(pairs: Iterable<Pair<String, String>>): ReplayGainTags? {
        var tg: Double? = null; var tp: Double? = null; var ag: Double? = null; var ap: Double? = null
        var r128t: Double? = null; var r128a: Double? = null
        for ((k0, v) in pairs) {
            when (k0.trim().uppercase()) {
                "REPLAYGAIN_TRACK_GAIN" -> tg = num(v) ?: tg
                "REPLAYGAIN_TRACK_PEAK" -> tp = num(v)?.takeIf { it > 0 } ?: tp
                "REPLAYGAIN_ALBUM_GAIN" -> ag = num(v) ?: ag
                "REPLAYGAIN_ALBUM_PEAK" -> ap = num(v)?.takeIf { it > 0 } ?: ap
                // Opus: Q7.8 dB relative to -23 LUFS; +5 dB brings it to the ReplayGain reference (-18 LUFS).
                "R128_TRACK_GAIN" -> r128t = num(v)?.let { it / 256.0 + 5.0 } ?: r128t
                "R128_ALBUM_GAIN" -> r128a = num(v)?.let { it / 256.0 + 5.0 } ?: r128a
            }
        }
        val track = tg?.let { Gain(it, tp) } ?: r128t?.let { Gain(it, null) }
        val album = ag?.let { Gain(it, ap) } ?: r128a?.let { Gain(it, null) }
        return ReplayGainTags(track, album).takeUnless { it.isEmpty }
    }

    /** Vorbis comments (FLAC/Ogg/Opus), ID3 TXXX frames (MP3) and iTunes freeform atoms (MP4/M4A). */
    @Suppress("DEPRECATION")
    fun fromMetadata(list: List<androidx.media3.common.Metadata>): ReplayGainTags? {
        val pairs = buildList {
            for (m in list) for (i in 0 until m.length()) when (val e = m.get(i)) {
                is androidx.media3.extractor.metadata.flac.VorbisComment -> add(e.key to e.value)
                is androidx.media3.extractor.metadata.id3.TextInformationFrame ->
                    if (e.id == "TXXX" || e.id == "TXX") e.description?.let { d -> add(d to e.value) }
                is androidx.media3.extractor.metadata.id3.InternalFrame -> add(e.description to e.text)
                else -> {}
            }
        }
        return fromPairs(pairs)
    }

    /** Tags of the selected audio track(s) of the current item. */
    fun fromTracks(tracks: androidx.media3.common.Tracks): ReplayGainTags? {
        val md = buildList {
            for (g in tracks.groups) if (g.type == androidx.media3.common.C.TRACK_TYPE_AUDIO) for (i in 0 until g.length) {
                if (g.isTrackSelected(i)) g.getTrackFormat(i).metadata?.let { add(it) }
            }
        }
        return if (md.isEmpty()) null else fromMetadata(md)
    }
}

/**
 * ITU-R BS.1770-4 integrated loudness: K-weighting (shelf + high-pass, coefficients for any sample rate), 400 ms
 * blocks with 75 % overlap, absolute gate -70 LUFS and relative gate -10 LU. All channels weighted 1.0.
 * Also tracks the sample peak. Not thread-safe: fed from the audio thread only.
 */
class LoudnessMeter(sampleRate: Int, private val channels: Int) {
    private val fs = sampleRate.toDouble()
    private val pb0: Double; private val pb1: Double; private val pb2: Double; private val pa1: Double; private val pa2: Double
    private val ra1: Double; private val ra2: Double
    // Filter state per channel: two DF2T biquads.
    private val s1 = DoubleArray(channels); private val s2 = DoubleArray(channels)
    private val t1 = DoubleArray(channels); private val t2 = DoubleArray(channels)
    private val subLen = max(1, sampleRate / 10) // 100 ms
    private var subPos = 0
    private var subAcc = 0.0
    private val lastSubs = DoubleArray(4); private var subsSeen = 0
    private var blocks = DoubleArray(1024); private var nBlocks = 0
    var peak = 0.0; private set

    init {
        var f0 = 1681.974450955533; val g = 3.999843853973347; var q = 0.7071752369554196
        var k = tan(PI * f0 / fs)
        val vh = 10.0.pow(g / 20.0); val vb = vh.pow(0.4996667741545416)
        var a0 = 1.0 + k / q + k * k
        pb0 = (vh + vb * k / q + k * k) / a0; pb1 = 2.0 * (k * k - vh) / a0; pb2 = (vh - vb * k / q + k * k) / a0
        pa1 = 2.0 * (k * k - 1.0) / a0; pa2 = (1.0 - k / q + k * k) / a0
        f0 = 38.13547087602444; q = 0.5003270373238773; k = tan(PI * f0 / fs)
        a0 = 1.0 + k / q + k * k
        ra1 = 2.0 * (k * k - 1.0) / a0; ra2 = (1.0 - k / q + k * k) / a0
    }

    /** Seconds of audio measured so far. */
    val seconds: Double get() = (subsSeen * subLen + subPos) / fs

    fun feed(x: FloatArray, frames: Int) {
        var i = 0
        for (f in 0 until frames) {
            for (c in 0 until channels) {
                val v = x[i++].toDouble()
                val a = if (v < 0) -v else v
                if (a > peak) peak = a
                // stage 1: high shelf
                val y1 = pb0 * v + s1[c]
                s1[c] = pb1 * v - pa1 * y1 + s2[c]
                s2[c] = pb2 * v - pa2 * y1
                // stage 2: RLB high-pass (b = 1, -2, 1)
                val y2 = y1 + t1[c]
                t1[c] = -2.0 * y1 - ra1 * y2 + t2[c]
                t2[c] = y1 - ra2 * y2
                subAcc += y2 * y2
            }
            if (++subPos == subLen) endSub()
        }
    }

    private fun endSub() {
        lastSubs[subsSeen % 4] = subAcc / subLen
        subsSeen++; subPos = 0; subAcc = 0.0
        if (subsSeen >= 4) {
            if (nBlocks == blocks.size) { if (blocks.size >= 1 shl 16) return; blocks = blocks.copyOf(blocks.size * 2) }
            blocks[nBlocks++] = (lastSubs[0] + lastSubs[1] + lastSubs[2] + lastSubs[3]) / 4.0
        }
    }

    /** Gated integrated loudness in LUFS, or null before the first 400 ms block (or for silence). */
    fun integrated(): Double? {
        fun lufs(z: Double) = -0.691 + 10.0 * log10(z)
        val absZ = 10.0.pow((-70.0 + 0.691) / 10.0)
        var sum = 0.0; var n = 0
        for (i in 0 until nBlocks) if (blocks[i] > absZ) { sum += blocks[i]; n++ }
        if (n == 0) return null
        val rel = lufs(sum / n) - 10.0
        val relZ = 10.0.pow((rel + 0.691) / 10.0)
        sum = 0.0; n = 0
        for (i in 0 until nBlocks) if (blocks[i] > absZ && blocks[i] > relZ) { sum += blocks[i]; n++ }
        return if (n == 0) null else lufs(sum / n)
    }

    fun result(): Measured? = integrated()?.let { Measured(it, peak) }
}

/**
 * Measured loudness per track id, kept across sessions in a small tab-separated file (later lines win; compacted on
 * load). Reads are lock-free (audio thread); writes go to one background thread. Works in memory only until [init].
 */
object LoudnessStore {
    private const val MAX = 10_000
    private val map = ConcurrentHashMap<String, Measured>()
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "loudness-store").apply { isDaemon = true } }
    @Volatile private var file: File? = null

    fun init(dir: File) {
        if (file != null) return
        val f = File(dir, "loudness.tsv"); file = f
        io.execute {
            runCatching {
                if (!f.exists()) return@runCatching
                val lines = f.readLines()
                val loaded = LinkedHashMap<String, Measured>()
                for (l in lines) {
                    val p = l.split('\t'); if (p.size != 3) continue
                    val lufs = p[1].toDoubleOrNull() ?: continue; val pk = p[2].toDoubleOrNull() ?: continue
                    loaded.remove(p[0]); loaded[p[0]] = Measured(lufs, pk)
                }
                val keep = loaded.entries.toList().takeLast(MAX)
                keep.forEach { (k, v) -> map.putIfAbsent(k, v) }
                if (lines.size > keep.size * 2 + 100) f.writeText(keep.joinToString("") { (k, v) -> line(k, v) })
            }
        }
    }

    fun get(id: String): Measured? = map[id]

    fun put(id: String, m: Measured) {
        if (id.isEmpty() || id.contains('\t') || id.contains('\n') || !m.lufs.isFinite()) return
        map[id] = m
        val f = file ?: return
        io.execute { runCatching { f.appendText(line(id, m)) } }
    }

    private fun line(id: String, m: Measured) = "$id\t${"%.2f".format(java.util.Locale.ROOT, m.lufs)}\t${"%.5f".format(java.util.Locale.ROOT, m.peak)}\n"

    internal fun clearForTest() = map.clear()
}

/** One-pole smoothing factor per frame for a time constant of [seconds]. */
internal fun smoothing(seconds: Double, rate: Int): Float = (1.0 - exp(-1.0 / (seconds * rate))).toFloat()
