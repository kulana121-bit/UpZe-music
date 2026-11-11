package com.ravi.musicspot.logic

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.roundToLong

/**
 * Pack 6 Part 2: DJ Mode maths. Pure Kotlin, no Android, unit-tested in DjModeTest.
 *
 * Three parts:
 *  1. [OnsetEnvelope] + [analyze]: tempo (BPM) and a beat grid from decoded PCM. Onset/energy based and light: the
 *     audio is reduced on the fly to a 5 ms log-energy onset curve (about 6 000 floats for 30 s), so nothing close to
 *     the raw PCM is ever held in memory. Autocorrelation picks the beat period, a comb search refines the BPM to a
 *     fraction of a beat-per-minute and finds the phase (where the first beat is).
 *  2. [tempoMatch]: the speed the incoming song needs to sit on the outgoing song's tempo, capped at ±[MAX_STRETCH].
 *  3. [plan]: when the incoming song starts so its first beat lands on the outgoing song's beat grid.
 *
 * A song without a clear pulse gets NO number: [analyze] returns null and DJ Mode falls back to the plain crossfade.
 * "Downbeat" here means the first detected beat: there is no bar (1-2-3-4) detection.
 */
object DjMode {
    /** Onset frames per second of audio (5 ms hop). */
    const val FRAMES_PER_SEC = 200
    /** Tempo range searched. Slower songs report double time, faster ones half time (both mix on the same grid). */
    const val MIN_BPM = 70.0
    const val MAX_BPM = 180.0
    /** Normalised autocorrelation needed to trust a tempo (white noise and random clicks score ~0.04, pop 0.4–0.7). */
    const val MIN_CONFIDENCE = 0.15
    /** At least this much audio is needed for an estimate. */
    const val MIN_ANALYSIS_MS = 8_000L
    /** Tempo match never stretches a song by more than this (±6 %). */
    const val MAX_STRETCH = 0.06
    /** Head and tail estimates further apart than this (2 %) mean the tempo drifts: no grid is trusted. */
    const val MAX_DRIFT = 0.02

    /** One analysed window: tempo, the first beat inside the window (ms from the window start) and the confidence. */
    data class Grid(val bpm: Double, val firstBeatMs: Long, val confidence: Double) {
        val periodMs: Double get() = 60_000.0 / bpm
    }

    /**
     * Streams PCM into a log-energy onset curve. [feed] takes mono samples in -1..1 (any block size); nothing but the
     * curve is kept. One per analysed window.
     */
    class OnsetEnvelope(val sampleRate: Int) {
        /** Samples per frame; the real frame rate is sampleRate / hop (not exactly 200 at 44.1 kHz). */
        val hop: Int = (sampleRate / FRAMES_PER_SEC).coerceAtLeast(1)
        val frameRate: Double get() = sampleRate.toDouble() / hop
        private var energies = FloatArray(1024)
        private var count = 0
        private var acc = 0.0
        private var inFrame = 0
        var samples = 0L; private set

        fun feed(mono: FloatArray, n: Int = mono.size) {
            for (i in 0 until n) {
                val x = mono[i].toDouble()
                acc += x * x
                if (++inFrame == hop) push()
            }
            samples += n
        }

        /** Shorts in -32768..32767, interleaved [channels]: downmixed here. */
        fun feedPcm16(pcm: ShortArray, n: Int, channels: Int) {
            val ch = channels.coerceAtLeast(1)
            var i = 0
            while (i + ch <= n) {
                var s = 0.0
                for (c in 0 until ch) s += pcm[i + c]
                val x = s / ch / 32768.0
                acc += x * x
                if (++inFrame == hop) push()
                i += ch
                samples++
            }
        }

        private fun push() {
            if (count == energies.size) energies = energies.copyOf(energies.size * 2)
            energies[count++] = acc.toFloat(); acc = 0.0; inFrame = 0
        }

        val durationMs: Long get() = samples * 1000 / sampleRate.coerceAtLeast(1)

        /** Half-wave rectified rise of log energy (20 ms smoothing), one value per frame. */
        fun onsets(): FloatArray {
            val n = count
            if (n < 8) return FloatArray(0)
            val e = energies
            var mean = 0.0
            for (i in 0 until n) mean += e[i]
            mean /= n
            val floor = 1e-3 * mean + 1e-12
            val le = DoubleArray(n)
            for (i in 0 until n) {
                var w = 0.0
                for (k in -2..1) { val j = i + k; if (j in 0 until n) w += e[j] }
                le[i] = ln(w + floor)
            }
            return FloatArray(n) { i -> if (i < 2) 0f else (le[i] - le[i - 2]).coerceAtLeast(0.0).toFloat() }
        }
    }

    /** Tempo and grid of one window, or null when there is no confident pulse (no fake number). */
    fun analyze(env: OnsetEnvelope): Grid? {
        if (env.durationMs < MIN_ANALYSIS_MS) return null
        return analyzeOnsets(env.onsets(), env.frameRate)
    }

    /** [analyze] on a ready onset curve at [fps] frames per second. */
    fun analyzeOnsets(onsets: FloatArray, fps: Double): Grid? {
        val n = onsets.size
        if (n < fps * MIN_ANALYSIS_MS / 1000) return null
        var mean = 0.0
        for (v in onsets) mean += v
        mean /= n
        val o = DoubleArray(n) { onsets[it] - mean }
        var ac0 = 0.0
        for (v in o) ac0 += v * v
        if (ac0 <= 1e-9) return null // silence or a flat tone
        ac0 /= n
        val lo = floor(60 * fps / MAX_BPM).toInt().coerceAtLeast(2)
        val hi = (60 * fps / MIN_BPM).toInt() + 1
        if (hi >= n / 2) return null
        val ac = DoubleArray(hi + 2)
        for (lag in (lo / 2).coerceAtLeast(1)..hi + 1) {
            var s = 0.0
            for (i in 0 until n - lag) s += o[i] * o[i + lag]
            ac[lag] = s / (n - lag) / ac0
        }
        var best = lo
        for (lag in lo..hi) if (ac[lag] > ac[best]) best = lag
        // A pure pulse scores the same at 2x the period; prefer the faster tempo when it is nearly as strong.
        val half = best / 2
        if (half >= lo) {
            val h = (half - 1..half + 1).maxBy { ac[it] }
            if (ac[h] >= 0.85 * ac[best]) best = h
        }
        val confidence = ac[best]
        if (confidence < MIN_CONFIDENCE) return null
        val coarse = 60 * fps / best
        val (bpm, phase) = refine(onsets, fps, coarse) ?: return null
        if (bpm < MIN_BPM * 0.97 || bpm > MAX_BPM * 1.03) return null
        return Grid(bpm, (phase * 1000 / fps).roundToLong(), confidence)
    }

    /** Comb search around [coarse] (±3 %, 0.02 BPM steps, half-frame phase steps) on a lightly smoothed curve. */
    private fun refine(onsets: FloatArray, fps: Double, coarse: Double): Pair<Double, Double>? {
        val n = onsets.size
        var min = Float.MAX_VALUE
        for (v in onsets) if (v < min) min = v
        val k = doubleArrayOf(1.0, 4.0, 6.0, 4.0, 1.0)
        val sm = DoubleArray(n) { i ->
            var s = 0.0
            for (j in -2..2) { val x = i + j; if (x in 0 until n) s += (onsets[x] - min) * k[j + 2] }
            s / 16
        }
        var bestScore = -1.0; var bestBpm = coarse; var bestPhase = 0.0
        var bpm = coarse * 0.97
        while (bpm <= coarse * 1.03) {
            val p = 60 * fps / bpm
            val beats = ((n - 3) / p).toInt()
            if (beats >= 4) {
                var ph = 0.0
                while (ph < p) {
                    var s = 0.0
                    for (b in 0 until beats) {
                        val t = ph + b * p
                        val i = t.toInt(); val f = t - i
                        if (i + 1 < n) s += sm[i] * (1 - f) + sm[i + 1] * f
                    }
                    s /= beats
                    if (s > bestScore) { bestScore = s; bestBpm = bpm; bestPhase = ph }
                    ph += 0.5
                }
            }
            bpm += 0.02
        }
        return if (bestScore > 0) bestBpm to bestPhase else null
    }

    /** What the store keeps per track: head grid (incoming alignment) and tail grid (outgoing alignment). */
    data class TrackBeats(val bpm: Double, val firstBeatMs: Long, val tailBpm: Double, val tailBeatMs: Long)

    /**
     * Combines the head window (from 0) and the tail window (starting at [tailStartMs]) into one entry, or null (unknown)
     * when either window has no pulse or the two disagree by more than [MAX_DRIFT] (a tempo that drifts).
     */
    fun combine(head: Grid?, tail: Grid?, tailStartMs: Long): TrackBeats? {
        if (head == null || tail == null) return null
        val r = octaveRatio(head.bpm, tail.bpm)
        if (abs(r - 1) > MAX_DRIFT) return null
        // Express the tail on the head's octave so both describe the same pulse.
        val tailBpm = tail.bpm * octaveFactor(head.bpm, tail.bpm)
        return TrackBeats(head.bpm, head.firstBeatMs, tailBpm, tailStartMs + tail.firstBeatMs)
    }

    /** Power of two (1/2, 1, 2) that brings [b] closest to [a]. */
    fun octaveFactor(a: Double, b: Double): Double =
        listOf(0.5, 1.0, 2.0).minBy { abs(ln(a / (b * it))) }

    /** a / (b on a's octave): 1.0 means the same tempo. */
    fun octaveRatio(a: Double, b: Double): Double = a / (b * octaveFactor(a, b))

    // ---- tempo match --------------------------------------------------------------------------------------------

    /**
     * Result of a tempo match. [stretch] multiplies the incoming song's speed (1.0 = untouched); [matched] is false for
     * every fallback (plain crossfade at normal speed, stretch 1.0).
     */
    data class Match(val matched: Boolean, val stretch: Double, val reason: String)

    /**
     * Speed factor that puts the incoming song ([inBpm]) on the outgoing song's tempo ([outBpm]), trying half/double
     * time too. Null BPM on either side, or a stretch beyond ±[MAX_STRETCH], is a fallback with stretch exactly 1.0.
     */
    fun tempoMatch(outBpm: Double?, inBpm: Double?): Match {
        if (outBpm == null || inBpm == null || outBpm <= 0 || inBpm <= 0) return Match(false, 1.0, "bpm unknown")
        val stretch = outBpm / (inBpm * octaveFactor(outBpm, inBpm))
        if (abs(stretch - 1) > MAX_STRETCH + 1e-9) return Match(false, 1.0, "beyond ±6%")
        return Match(true, stretch, "matched")
    }

    /**
     * The PlaybackParameters (speed, pitch) for a song at the user's [userSpeed] with tempo [stretch]. The user's pitch
     * choice is kept exactly: keep-pitch → pitch 1.0 (Sonic time-stretch); otherwise pitch follows the speed, as the
     * speed picker already does.
     */
    fun parameters(userSpeed: Float, keepPitch: Boolean, stretch: Double): Pair<Float, Float> {
        val speed = (userSpeed * stretch).toFloat()
        return speed to (if (keepPitch) 1f else speed)
    }

    // ---- beat-aligned start --------------------------------------------------------------------------------------

    /**
     * When to start the incoming song. All times are track ms.
     * [outStartMs]: outgoing position at which the incoming song starts, [inStartMs]: where in the incoming song it
     * starts, [firstBeatAtOutMs]: outgoing position where the incoming first beat lands (on the outgoing grid).
     */
    data class Plan(val outStartMs: Long, val inStartMs: Long, val stretch: Double, val firstBeatAtOutMs: Long)

    /**
     * Beat-aligned start inside the crossfade window [windowStartMs, outDurationMs). The incoming song plays at
     * [stretch] times the outgoing song's speed, so 1 ms of incoming time is 1/stretch ms of outgoing time.
     * The incoming first beat (or a whole number of beats after its start, when the intro is longer than the window)
     * lands exactly on an outgoing beat. Null when no outgoing beat fits in the window.
     */
    fun plan(out: TrackBeats, outDurationMs: Long, windowStartMs: Long, incoming: TrackBeats, stretch: Double): Plan? {
        if (outDurationMs <= windowStartMs || stretch <= 0) return null
        val outPeriod = 60_000.0 / out.tailBpm
        val inPeriod = 60_000.0 / incoming.bpm
        // Landing the first incoming beat on any outgoing beat aligns the grids: after the octave-aware match the
        // incoming beat period is 1/2, 1 or 2 outgoing periods, so every later incoming beat is on the grid too.
        val gridStep = outPeriod
        // Incoming start: 0, or skip whole beats of a long intro so the first landed beat stays in the window's first half.
        val windowLen = outDurationMs - windowStartMs
        var inStart = 0.0
        val firstBeatOut = incoming.firstBeatMs / stretch
        if (firstBeatOut > windowLen / 2.0) {
            // Start a whole number of beats before the first beat, so the start itself sits on the incoming grid.
            val keepBeats = floor(windowLen / 2.0 * stretch / inPeriod)
            inStart = (incoming.firstBeatMs - keepBeats * inPeriod).coerceAtLeast(0.0)
        }
        val leadOut = (incoming.firstBeatMs - inStart) / stretch // outgoing ms from start to first beat
        // First outgoing beat B >= windowStart + leadOut on the tail grid (anchor + k * step).
        val earliest = windowStartMs + leadOut
        val k = kotlin.math.ceil((earliest - out.tailBeatMs) / gridStep)
        val beat = out.tailBeatMs + k * gridStep
        if (beat >= outDurationMs) return null
        val start = beat - leadOut
        if (start < windowStartMs - 0.5) return null
        return Plan(start.roundToLong(), inStart.roundToLong(), stretch, beat.roundToLong())
    }
}
