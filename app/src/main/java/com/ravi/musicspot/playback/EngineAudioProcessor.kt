@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.ravi.musicspot.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import com.ravi.musicspot.dsp.EngineCore
import com.ravi.musicspot.dsp.EngineSettings
import com.ravi.musicspot.dsp.EngineStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * UpZe's software audio engine inside ExoPlayer's audio sink: 16-bit or float PCM in, same encoding out
 * (at the upscale rate when Upscale is on). Replaces android.media.audiofx entirely.
 */
class EngineAudioProcessor(private val settings: () -> EngineSettings) : BaseAudioProcessor() {
    // 1.0.5: @Volatile — apply() runs on the settings collector thread while these are swapped on the playback thread;
    // without it apply() could keep updating a stale core and the new settings never reached the audio.
    @Volatile private var pendingCore: EngineCore? = null
    @Volatile private var core: EngineCore? = null
    // 1.0.5: scratch PCM buffers, grown only when a larger buffer arrives (amortised, rare) and reused in place.
    private var inFloats = FloatArray(0)
    private var outFloats = FloatArray(0)
    private var lastStatusAt = 0L
    private val rng = java.util.Random()

    fun apply(s: EngineSettings) { core?.update(s); pendingCore?.update(s); levelCfg = s; levelDirty = true }

    // ---- 1.0.10 volume levelling: ONE gain stage for ReplayGain and loudness normalisation (see GainPlan) ----
    private class TrackReq(val id: String?, val tags: ReplayGainTags?)
    @Volatile private var trackReq: TrackReq? = null
    @Volatile private var levelCfg: EngineSettings? = null
    @Volatile private var levelDirty = true
    private var lvId: String? = null
    private var lvTags: ReplayGainTags? = null
    private var meter: LoudnessMeter? = null
    private var meterRate = 0; private var meterCh = 0
    private var lvGain = 1f
    private var lvTarget = 1f
    private var lvAlpha = 0f
    private var lvFramesSinceEval = 0
    private var lvProvisional = false

    /** The track now playing and its ReplayGain tags (if any). Called on the player thread on transitions / new tracks. */
    fun setTrack(id: String?, tags: ReplayGainTags?) { trackReq = TrackReq(id, tags); levelDirty = true }

    /** Seconds of a track that must be heard before its measured loudness is kept. */
    private val minKeepSeconds = 30.0

    private fun finishMeter() {
        val m = meter ?: return
        val id = lvId
        if (id != null && m.seconds >= minKeepSeconds) m.result()?.let { LoudnessStore.put(id, it) }
        meter = null
    }

    /** Audio thread: picks up a new track / settings and recomputes the target gain. */
    private fun levelUpdate(rate: Int, ch: Int, frames: Int) {
        val req = trackReq
        val s = levelCfg ?: settings()
        if (req != null && req.id != lvId) { finishMeter(); lvId = req.id; levelDirty = true }
        if (req != null && req.tags != lvTags) { lvTags = req.tags; levelDirty = true }
        if (rate != meterRate || ch != meterCh) { meter = null; meterRate = rate; meterCh = ch }
        val stored = lvId?.let { LoudnessStore.get(it) }
        val wantMeter = s.loudnessNormalize && lvId != null && stored == null
        if (wantMeter && meter == null) meter = LoudnessMeter(rate, ch)
        if (!wantMeter && meter != null) finishMeter()
        lvFramesSinceEval += frames
        val reEval = lvProvisional && lvFramesSinceEval >= rate // provisional estimate: refresh about once a second
        if (!levelDirty && !reEval) return
        levelDirty = false; lvFramesSinceEval = 0
        val m = meter
        val running = if (stored == null && m != null && m.seconds >= 5.0) m.result() else null
        lvProvisional = stored == null && m != null
        val d = GainPlan.decide(s.replayGain, s.loudnessNormalize, lvTags, stored ?: running, provisional = stored == null)
        lvTarget = GainPlan.linear(d.gainDb)
        // Track changes / settings move in ~150 ms; a running estimate eases in over ~1.5 s so it never pumps.
        lvAlpha = smoothing(if (lvProvisional) 1.5 else 0.15, rate)
    }

    /** Measures (pre-gain) and applies the levelling gain in place. Never raises a sample past full scale. */
    private fun level(x: FloatArray, frames: Int, ch: Int) {
        meter?.feed(x, frames)
        if (lvGain == 1f && lvTarget == 1f) return
        val a = lvAlpha
        var g = lvGain
        val t = lvTarget
        var i = 0
        for (f in 0 until frames) {
            g += (t - g) * a
            if (kotlin.math.abs(t - g) < 1e-5f) g = t
            for (c in 0 until ch) {
                val v = x[i] * g
                x[i] = if (g > 1f) v.coerceIn(-1f, 1f) else v
                i++
            }
        }
        lvGain = g
    }

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) throw UnhandledAudioFormatException(inputAudioFormat)
        val initial = settings()
        val c = EngineCore(inputAudioFormat.sampleRate, inputAudioFormat.channelCount, initial)
        pendingCore = c
        // 1.0.5: an apply() that landed between settings() and the assignment above went to the old core only; re-sync.
        val latest = settings()
        if (latest != initial) c.update(latest)
        // v2.0.6: keep the input's encoding. Float output into a non-float sink (Sonic/AudioTrack on Android 10) failed with
        // "Unhandled input format ... encoding=4" (ERROR_CODE_AUDIO_TRACK_INIT_FAILED). 16-bit in -> 16-bit out (TPDF-dithered).
        return AudioFormat(c.outRate, inputAudioFormat.channelCount, inputAudioFormat.encoding)
    }

    override fun onFlush() { pendingCore?.let { core = it; pendingCore = null }; BeatMeter.reset() }
    override fun onReset() { core = null; pendingCore = null; _status.value = EngineStatus() }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val c = core ?: run { inputBuffer.position(inputBuffer.limit()); return }
        val ch = inputAudioFormat.channelCount
        val float = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        val bytesPerSample = if (float) 4 else 2
        val samples = inputBuffer.remaining() / bytesPerSample
        val frames = samples / ch
        if (frames == 0) { inputBuffer.position(inputBuffer.limit()); return }
        if (inFloats.size < samples) inFloats = FloatArray(samples)
        val src = inputBuffer.order(ByteOrder.nativeOrder())
        levelUpdate(inputAudioFormat.sampleRate, ch, frames)
        // 1.0.10 true pass-through: EQ neutral, no enhance / spatial / upscale and unity levelling gain → the PCM goes
        // out byte for byte (no float conversion, no dither, no gain). Meters only read a float copy when they need one.
        if (lvGain == 1f && lvTarget == 1f && c.passThrough()) {
            if (meter != null || BeatMeter.wanted) {
                val inF = inFloats
                val p0 = src.position()
                val total = frames * ch
                if (float) { var i = 0; while (i < total) { inF[i] = src.getFloat(p0 + i * 4); i++ } }
                else { var i = 0; while (i < total) { inF[i] = src.getShort(p0 + i * 2) / 32768f; i++ } }
                meter?.feed(inF, frames)
                if (BeatMeter.wanted) BeatMeter.feed(inF, frames, ch, inputAudioFormat.sampleRate)
            }
            val out = replaceOutputBuffer(inputBuffer.remaining())
            out.put(inputBuffer)
            out.flip()
            val now = System.currentTimeMillis()
            if (now - lastStatusAt > 500) { lastStatusAt = now; _status.value = c.status }
            return
        }
        // 1.0.5: plain indexed loops over a local array ref; no views (asFloatBuffer/asShortBuffer allocate) or boxing.
        val inF = inFloats
        val total = frames * ch
        if (float) { var i = 0; while (i < total) { inF[i] = src.getFloat(); i++ } }
        else { var i = 0; while (i < total) { inF[i] = src.getShort() / 32768f; i++ } }
        inputBuffer.position(inputBuffer.limit())
        level(inF, frames, ch) // 1.0.10: ReplayGain / loudness normalisation, one stage
        val maxOut = c.maxOutputFrames(frames) * ch
        if (outFloats.size < maxOut) outFloats = FloatArray(maxOut)
        val t0 = System.nanoTime()
        val n = c.process(inF, frames, outFloats)
        c.guard.record(System.nanoTime() - t0, frames * 1_000_000_000L / inputAudioFormat.sampleRate)
        // 1.0.5: BaseAudioProcessor.replaceOutputBuffer() reuses its direct ByteBuffer and only reallocates when the
        // capacity is too small, so the output buffer is not allocated per call.
        val outF = outFloats
        val outSamples = n * ch
        val out: ByteBuffer
        if (float) {
            out = replaceOutputBuffer(outSamples * 4)
            var i = 0
            while (i < outSamples) { out.putFloat(outF[i]); i++ }
        } else {
            out = replaceOutputBuffer(outSamples * 2)
            val r = rng
            var i = 0
            while (i < outSamples) {
                val d = (r.nextFloat() - r.nextFloat()) / 32768f
                val v = ((outF[i] + d) * 32767f).coerceIn(-32768f, 32767f)
                out.putShort(Math.round(v).toShort())
                i++
            }
        }
        out.flip()
        // 1.0.8 Live lyrics: loudness / bass / treble of what was just processed (only while the stage is showing).
        if (BeatMeter.wanted) BeatMeter.feed(outF, n, ch, c.outRate)
        val now = System.currentTimeMillis()
        if (now - lastStatusAt > 500) { lastStatusAt = now; _status.value = c.status }
    }

    companion object {
        private val _status = MutableStateFlow(EngineStatus())
        val status: StateFlow<EngineStatus> = _status
    }
}
