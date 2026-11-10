// 1.0.8: a frame-accurate playback clock for lyrics, and letter-by-letter timing.
package com.ravi.musicspot.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import com.ravi.musicspot.Graph
import com.ravi.musicspot.lyrics.LyricLine

/**
 * 1.0.8 lyrics sync fix. The player publishes its position only every 250 ms, so a word (or letter) could light up to
 * a quarter of a second late, and the highlight moved in visible steps. This clock follows the real position but
 * advances it on every display frame while playing (anchor + elapsed time), and folds each new 250 ms sample in
 * gently: small drift is corrected over the next frames without the highlight ever jumping backwards; a real jump
 * (seek, skip, buffering) snaps straight to the new position.
 *
 * Read the returned State only in draw / layout lambdas or derivedStateOf, never directly in composition, or the
 * reader recomposes every frame. When paused it simply equals the player's position (no frame loop runs).
 */
@Composable
fun rememberSmoothPosition(): State<Long> {
    val sample = Graph.player.position.collectAsState()
    val st by Graph.player.state.collectAsState()
    val playing = st.playing && !st.buffering
    val out = remember { mutableLongStateOf(sample.value) }
    val clock = remember { SmoothClock() }
    LaunchedEffect(playing) {
        if (!playing) {
            // Paused: follow the samples exactly (seek while paused included).
            androidx.compose.runtime.snapshotFlow { sample.value }.collect { v -> clock.snap(v, System.nanoTime()); out.longValue = v }
            return@LaunchedEffect
        }
        clock.snap(sample.value, System.nanoTime())
        var lastSample = sample.value
        while (true) {
            withFrameNanos { }
            val now = System.nanoTime()
            val s = sample.value
            if (s != lastSample) { clock.fold(s, now); lastSample = s }
            out.longValue = clock.at(now)
        }
    }
    return out
}

/**
 * 1.0.8 sync feel: lines are switched a little BEFORE their timestamp, so the new line has finished gliding to the
 * centre and lighting up by the time the voice starts (without this the highlight looked ~0.3 s late, because the
 * glide/colour animations only start at the timestamp). Letters lead by a hair less than one frame of reaction time.
 */
internal const val LYRIC_LINE_LEAD_MS = 150
internal const val LYRIC_LETTER_LEAD_MS = 40

/** The pure part of [rememberSmoothPosition] (unit-testable): an anchored position that runs at 1x. */
internal class SmoothClock {
    private var anchorPos = 0L
    private var anchorNanos = 0L
    private var lastOut = 0L

    fun snap(pos: Long, nowNanos: Long) { anchorPos = pos; anchorNanos = nowNanos; lastOut = pos }

    /** A fresh sample [pos] arrived at [nowNanos]: snap on a big jump, else ease half the error in (no backward step). */
    fun fold(pos: Long, nowNanos: Long) {
        val predicted = predict(nowNanos)
        val err = pos - predicted
        if (kotlin.math.abs(err) > 350L) { snap(pos, nowNanos); return }
        anchorPos = predicted + err / 2
        anchorNanos = nowNanos
    }

    fun at(nowNanos: Long): Long {
        val p = predict(nowNanos)
        // Never step backwards by a few ms because of a correction (would flicker a letter off and on again).
        val v = if (p < lastOut && lastOut - p < 120L) lastOut else p
        lastOut = v
        return v
    }

    private fun predict(nowNanos: Long): Long = anchorPos + (nowNanos - anchorNanos) / 1_000_000L
}

/**
 * 1.0.8 letter-by-letter timing for a word-synced line: each word's time is shared evenly between its letters (spaces
 * and punctuation-only gaps take no time and light with the letter before them). [start]/[end] are per character of
 * [text], which is the words joined exactly as the word-by-word renderer joined them.
 */
internal class LetterTiming(val text: String, val start: LongArray, val end: LongArray) {
    /** 0..1 lit amount of character [i] at [posMs] (fractional while the letter is being sung). */
    fun progress(i: Int, posMs: Long): Float {
        val s = start[i]; val e = end[i]
        return when {
            posMs >= e -> 1f
            posMs <= s -> 0f
            else -> ((posMs - s).toFloat() / (e - s).coerceAtLeast(1L)).coerceIn(0f, 1f)
        }
    }

    companion object {
        fun of(line: LyricLine): LetterTiming {
            val text = buildString { line.words.forEach { append(it.text) } }
            val start = LongArray(text.length)
            val end = LongArray(text.length)
            var idx = 0
            var lastEnd = line.startMs
            for (w in line.words) {
                val letters = w.text.count { !it.isWhitespace() }.coerceAtLeast(1)
                val dur = (w.endMs - w.startMs).coerceAtLeast(letters.toLong())
                var k = 0
                for (ch in w.text) {
                    if (ch.isWhitespace()) {
                        val at = if (k == 0) w.startMs else lastEnd
                        start[idx] = at; end[idx] = at
                    } else {
                        val s = w.startMs + dur * k / letters
                        val e = w.startMs + dur * (k + 1) / letters
                        start[idx] = s; end[idx] = e.coerceAtLeast(s + 1)
                        lastEnd = end[idx]
                        k++
                    }
                    idx++
                }
            }
            return LetterTiming(text, start, end)
        }
    }
}
