// 1.0.8: a tiny loudness / bass / treble meter fed by the sound engine, read by the Live lyrics stage.
package com.ravi.musicspot.playback

import kotlin.math.exp
import kotlin.math.sqrt

/**
 * 1.0.8 Live lyrics: what the music is doing right now, measured on the audio the engine is already processing (no
 * Visualizer, no microphone permission, no extra pass over the data: three multiply-adds per sample inside the
 * engine's existing loop).
 *
 * Per buffer the engine calls [feed] with the mono-summed block; the meter keeps
 *  - **level**: RMS loudness of the block,
 *  - **bass**: RMS of a ~150 Hz low-pass (kicks, bass line: what "the beat" feels like),
 *  - **treble**: RMS of everything above ~2.5 kHz (hats, vocals' air: how bright the song's tone is),
 * timestamped with [System.nanoTime] into a small ring. The audio is processed ahead of what is heard (the sink's
 * buffer), so readers ask for the reading [LATENCY_MS] in the past ([at]), which lines it up with the speaker closely
 * enough for motion graphics.
 *
 * Writes come from the playback thread, reads from the UI thread; the ring stores primitives only and a torn read just
 * returns a neighbouring block, which is harmless for visuals. Nothing here allocates after construction.
 */
object BeatMeter {
    /** Roughly how far the engine runs ahead of the speaker (DefaultAudioSink buffer). */
    const val LATENCY_MS = 260L
    private const val SIZE = 128

    private val times = LongArray(SIZE)
    private val levels = FloatArray(SIZE)
    private val basses = FloatArray(SIZE)
    private val trebles = FloatArray(SIZE)
    @Volatile private var head = -1
    /** Set by the Live lyrics stage while it is on screen; the engine skips metering otherwise (zero cost). */
    @Volatile var wanted = false
    @Volatile var lastFeedNanos = 0L; private set

    // Filter state (playback thread only).
    private var lp = 0f
    private var lp2 = 0f
    private var rate = 0
    private var aBass = 0f
    private var aTreble = 0f

    /** True when the engine has fed audio in the last second (false in Pure hi-res mode, paused, or not playing). */
    val live: Boolean get() = lastFeedNanos != 0L && System.nanoTime() - lastFeedNanos < 1_000_000_000L

    /** Called by the engine for every processed block: [buf] holds [frames] interleaved frames of [channels]. */
    fun feed(buf: FloatArray, frames: Int, channels: Int, sampleRate: Int) {
        if (frames <= 0 || channels <= 0 || sampleRate <= 0) return
        if (sampleRate != rate) {
            rate = sampleRate
            aBass = (1.0 - exp(-2.0 * Math.PI * 150.0 / sampleRate)).toFloat()
            aTreble = (1.0 - exp(-2.0 * Math.PI * 2500.0 / sampleRate)).toFloat()
        }
        var sAll = 0f; var sBass = 0f; var sTreble = 0f
        val inv = 1f / channels
        var i = 0
        var f = 0
        while (f < frames) {
            var m = 0f
            var c = 0
            while (c < channels) { m += buf[i + c]; c++ }
            m *= inv
            i += channels
            lp += aBass * (m - lp)
            lp2 += aTreble * (m - lp2)
            val hi = m - lp2
            sAll += m * m; sBass += lp * lp; sTreble += hi * hi
            f++
        }
        val n = (head + 1) and (SIZE - 1)
        times[n] = System.nanoTime()
        levels[n] = sqrt(sAll / frames)
        basses[n] = sqrt(sBass / frames)
        trebles[n] = sqrt(sTreble / frames)
        head = n
        lastFeedNanos = times[n]
    }

    /** Resets the filters (new stream / seek), so a stale bass tail doesn't read as a beat. */
    fun reset() { lp = 0f; lp2 = 0f }

    /** A reading: loudness, bass and treble (all roughly 0..1 for full-scale audio). */
    class Reading { var level = 0f; var bass = 0f; var treble = 0f }

    /** Fills [out] with the block that was being heard at [nowNanos] (i.e. processed [LATENCY_MS] earlier). */
    fun at(nowNanos: Long, out: Reading): Reading {
        val h = head
        if (h < 0) { out.level = 0f; out.bass = 0f; out.treble = 0f; return out }
        val want = nowNanos - LATENCY_MS * 1_000_000L
        var k = h
        var steps = 0
        // Walk back to the newest block not newer than `want` (the ring holds ~1-3 s of blocks).
        while (steps < SIZE - 1 && times[k] > want) {
            val prev = (k - 1) and (SIZE - 1)
            if (times[prev] == 0L || times[prev] > times[k]) break
            k = prev; steps++
        }
        out.level = levels[k]; out.bass = basses[k]; out.treble = trebles[k]
        return out
    }
}
