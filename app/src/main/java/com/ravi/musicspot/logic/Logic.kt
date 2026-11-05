package com.ravi.musicspot.logic

import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/* ------------------------------------------------------------------------------------------
 * Pure, Android-free logic. Everything here is covered by JVM unit tests.
 * ---------------------------------------------------------------------------------------- */

enum class RepeatMode { OFF, ALL, ONE }

/** Queue operations that mirror what the player does, so the UI can stay consistent and tests can pin it. */
object QueueOps {
    /** Moves the element at [from] to [to] (both indices in the original list). */
    fun <T> move(list: List<T>, from: Int, to: Int): List<T> {
        if (from !in list.indices || to !in list.indices || from == to) return list
        val m = list.toMutableList()
        val item = m.removeAt(from)
        m.add(to, item)
        return m
    }

    /** The current index after moving [from] to [to]. */
    fun currentAfterMove(current: Int, from: Int, to: Int): Int = when {
        current == from -> to
        from < current && to >= current -> current - 1
        from > current && to <= current -> current + 1
        else -> current
    }

    /** Returns the list without [index] and the adjusted current index (-1 when the list becomes empty). */
    fun <T> remove(list: List<T>, current: Int, index: Int): Pair<List<T>, Int> {
        if (index !in list.indices) return list to current
        val m = list.toMutableList().also { it.removeAt(index) }
        val newCurrent = when {
            m.isEmpty() -> -1
            index < current -> current - 1
            index == current -> min(current, m.lastIndex)
            else -> current
        }
        return m to newCurrent
    }

    /** Inserts [items] right after the current item ("Play next"). */
    fun <T> insertNext(list: List<T>, current: Int, items: List<T>): List<T> {
        val at = if (list.isEmpty()) 0 else (current + 1).coerceIn(0, list.size)
        return list.toMutableList().also { it.addAll(at, items) }
    }

    /**
     * Next index to play given the play [order] (a permutation of indices; identity when shuffle is off).
     * Returns null when playback should stop.
     */
    fun nextIndex(current: Int, order: List<Int>, repeat: RepeatMode, userInitiated: Boolean): Int? {
        if (order.isEmpty()) return null
        if (repeat == RepeatMode.ONE && !userInitiated) return current
        val pos = order.indexOf(current)
        if (pos < 0) return order.first()
        return when {
            pos < order.lastIndex -> order[pos + 1]
            repeat != RepeatMode.OFF -> order.first()
            else -> null
        }
    }

    /** Previous index: restarts the current track when more than 3 s in (standard player behaviour). */
    fun previousIndex(current: Int, order: List<Int>, repeat: RepeatMode, positionMs: Long): Int {
        if (positionMs > 3_000 || order.isEmpty()) return current
        val pos = order.indexOf(current)
        return when {
            pos > 0 -> order[pos - 1]
            repeat == RepeatMode.ALL -> order.last()
            else -> current
        }
    }

    /** Shuffle order that keeps the current item first so the song playing does not jump. */
    fun shuffledOrder(size: Int, current: Int, random: java.util.Random): List<Int> {
        if (size <= 0) return emptyList()
        val rest = (0 until size).filter { it != current }.toMutableList()
        java.util.Collections.shuffle(rest, random)
        return if (current in 0 until size) listOf(current) + rest else rest
    }

    fun totalDurationMs(durations: List<Long>, fromIndex: Int): Long =
        durations.drop(fromIndex.coerceAtLeast(0)).sum()
}

/** Sleep timer state machine, driven by an injected clock (ms). */
class SleepTimer {
    enum class Mode { OFF, DURATION, END_OF_TRACK, END_OF_QUEUE }

    var mode: Mode = Mode.OFF
        private set
    var endAtMs: Long = 0
        private set
    var totalMs: Long = 0
        private set

    fun startDuration(durationMs: Long, nowMs: Long) {
        require(durationMs > 0)
        mode = Mode.DURATION; endAtMs = nowMs + durationMs; totalMs = durationMs
    }

    fun startEndOfTrack() { mode = Mode.END_OF_TRACK; endAtMs = 0; totalMs = 0 }
    fun startEndOfQueue() { mode = Mode.END_OF_QUEUE; endAtMs = 0; totalMs = 0 }
    fun cancel() { mode = Mode.OFF; endAtMs = 0; totalMs = 0 }

    /** Adds (or removes, when negative) time. Never drops below one minute; extends the total for progress. */
    fun adjust(deltaMs: Long, nowMs: Long) {
        if (mode != Mode.DURATION) return
        val newRemaining = max(60_000L, remainingMs(nowMs) + deltaMs)
        endAtMs = nowMs + newRemaining
        totalMs = max(totalMs, newRemaining)
    }

    fun remainingMs(nowMs: Long): Long = if (mode == Mode.DURATION) max(0L, endAtMs - nowMs) else 0L
    fun isExpired(nowMs: Long): Boolean = mode == Mode.DURATION && nowMs >= endAtMs

    /** 0..1 fraction of the timer elapsed, for the progress bar. */
    fun progress(nowMs: Long): Float =
        if (mode != Mode.DURATION || totalMs <= 0) 0f else (1f - remainingMs(nowMs).toFloat() / totalMs).coerceIn(0f, 1f)

    /** Volume multiplier for a gentle fade over the last [fadeWindowMs]. */
    fun fadeFactor(nowMs: Long, fadeWindowMs: Long): Float {
        if (mode != Mode.DURATION || fadeWindowMs <= 0) return 1f
        val r = remainingMs(nowMs)
        return if (r >= fadeWindowMs) 1f else (r.toFloat() / fadeWindowMs).coerceIn(0f, 1f)
    }

    /**
     * Whether playback should pause now. [trackRemainingMs] is the time left in the current track, [isLastInQueue]
     * whether that track is the last one. When [finishSong] is on, a duration timer waits for the track to end.
     */
    fun shouldPause(nowMs: Long, trackRemainingMs: Long, isLastInQueue: Boolean, finishSong: Boolean): Boolean = when (mode) {
        Mode.OFF -> false
        Mode.DURATION -> isExpired(nowMs) && (!finishSong || trackRemainingMs <= END_GRACE_MS)
        Mode.END_OF_TRACK -> trackRemainingMs <= END_GRACE_MS
        Mode.END_OF_QUEUE -> isLastInQueue && trackRemainingMs <= END_GRACE_MS
    }

    companion object {
        const val END_GRACE_MS = 600L
        fun format(ms: Long): String {
            val s = (ms + 999) / 1000
            val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
        }
    }
}

/** Search over any catalog item. Normalises case and accents, matches every token, ranks by field. */
object SearchFilter {
    data class Fields(val title: String, val artist: String, val album: String, val extra: String = "")

    fun normalize(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
            .lowercase().replace("&", " and ").replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    /** Score > 0 when every token matches somewhere; higher is better. 0 = no match. */
    fun score(query: String, f: Fields): Int {
        val q = normalize(query)
        if (q.isEmpty()) return 0
        val title = normalize(f.title); val artist = normalize(f.artist); val album = normalize(f.album); val extra = normalize(f.extra)
        var total = 0
        for (tok in q.split(' ')) {
            val s = when {
                title == q -> 120
                title.startsWith(tok) -> 60
                title.split(' ').any { it.startsWith(tok) } -> 45
                artist.split(' ').any { it.startsWith(tok) } -> 35
                album.split(' ').any { it.startsWith(tok) } -> 25
                title.contains(tok) -> 15
                artist.contains(tok) || album.contains(tok) -> 10
                extra.contains(tok) -> 5
                else -> return 0
            }
            total += s
        }
        return total
    }

    fun <T> filter(query: String, items: List<T>, fields: (T) -> Fields): List<T> {
        if (normalize(query).isEmpty()) return emptyList()
        return items.map { it to score(query, fields(it)) }.filter { it.second > 0 }
            .sortedByDescending { it.second }.map { it.first }
    }
}

/** Timed lyric lookup. */
data class LyricLine(val timeMs: Long, val text: String)

object LyricSync {
    /** Index of the active line at [positionMs] (with [offsetMs] applied; positive = lyrics later), -1 before the first. */
    fun activeIndex(lines: List<LyricLine>, positionMs: Long, offsetMs: Long = 0): Int {
        if (lines.isEmpty()) return -1
        val p = positionMs - offsetMs
        var lo = 0; var hi = lines.lastIndex; var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].timeMs <= p) { ans = mid; lo = mid + 1 } else hi = mid - 1
        }
        return ans
    }

    /** 0..1 progress through the active line, using the next line (or [trackDurationMs]) as its end. */
    fun lineProgress(lines: List<LyricLine>, positionMs: Long, trackDurationMs: Long, offsetMs: Long = 0): Float {
        val i = activeIndex(lines, positionMs, offsetMs)
        if (i < 0) return 0f
        val start = lines[i].timeMs
        val end = if (i < lines.lastIndex) lines[i + 1].timeMs else max(start + 1, trackDurationMs)
        return ((positionMs - offsetMs - start).toFloat() / (end - start)).coerceIn(0f, 1f)
    }
}

/** EQ maths: map the app's curve onto whatever bands the device Equalizer exposes. */
object EqMath {
    val GRAPHIC_FREQS = intArrayOf(32, 64, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)

    /** Gain (dB) of a 10-band graphic curve at [freqHz], linear interpolation in log-frequency. */
    fun graphicGainAt(gains: List<Float>, freqHz: Double): Float {
        require(gains.size == GRAPHIC_FREQS.size)
        val x = ln(freqHz)
        if (freqHz <= GRAPHIC_FREQS.first()) return gains.first()
        if (freqHz >= GRAPHIC_FREQS.last()) return gains.last()
        for (i in 0 until GRAPHIC_FREQS.lastIndex) {
            val a = ln(GRAPHIC_FREQS[i].toDouble()); val b = ln(GRAPHIC_FREQS[i + 1].toDouble())
            if (x in a..b) {
                val t = ((x - a) / (b - a)).toFloat()
                return gains[i] + (gains[i + 1] - gains[i]) * t
            }
        }
        return 0f
    }

    enum class FilterType { LOW_SHELF, BELL, HIGH_SHELF }
    data class ParamBand(val type: FilterType, val freqHz: Float, val gainDb: Float, val q: Float, val enabled: Boolean = true)

    /** Approximate magnitude response (dB) of one parametric band at [f]. */
    fun paramGainAt(b: ParamBand, f: Double): Float {
        if (!b.enabled || b.gainDb == 0f) return 0f
        val r = ln(f / b.freqHz) / ln(2.0) // octaves from centre
        return when (b.type) {
            FilterType.BELL -> {
                val bwOct = 1.0 / max(0.1f, b.q) // rough Q -> bandwidth (octaves)
                (b.gainDb * kotlin.math.exp(-(r * r) / (2 * (bwOct / 2.0).pow(2)))).toFloat()
            }
            FilterType.LOW_SHELF -> (b.gainDb / (1 + kotlin.math.exp(r * 4 * b.q))).toFloat()
            FilterType.HIGH_SHELF -> (b.gainDb / (1 + kotlin.math.exp(-r * 4 * b.q))).toFloat()
        }
    }

    fun parametricGainAt(bands: List<ParamBand>, f: Double): Float = bands.sumOf { paramGainAt(it, f).toDouble() }.toFloat()

    /** Device band levels in millibels, clamped to the device range. */
    fun toDeviceLevels(centersHz: List<Double>, gainAt: (Double) -> Float, minMb: Int, maxMb: Int): List<Int> =
        centersHz.map { (gainAt(it) * 100).toInt().coerceIn(minMb, maxMb) }

    /** Log-spaced frequencies for drawing a response curve. */
    fun plotFreqs(n: Int = 64): List<Double> = List(n) { i -> 20.0 * (1000.0).pow(i / (n - 1.0)) }

    fun rms(a: List<Float>, b: List<Float>): Float = sqrt(a.zip(b).sumOf { (x, y) -> ((x - y) * (x - y)).toDouble() } / max(1, a.size)).toFloat()

    @Suppress("unused") fun absMax(a: List<Float>) = a.maxOfOrNull { abs(it) } ?: 0f
}

fun formatTime(ms: Long): String {
    val s = (ms.coerceAtLeast(0) / 1000)
    return "%d:%02d".format(s / 60, s % 60)
}

fun formatDurationLong(ms: Long): String {
    val m = ms / 60_000
    return if (m >= 60) "${m / 60} hr ${m % 60} min" else if (m > 0) "$m min" else "${ms / 1000} sec"
}
