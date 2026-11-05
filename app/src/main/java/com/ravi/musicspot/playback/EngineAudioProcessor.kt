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
    private var pendingCore: EngineCore? = null
    private var core: EngineCore? = null
    private var inFloats = FloatArray(0)
    private var outFloats = FloatArray(0)
    private var lastStatusAt = 0L
    private val rng = java.util.Random()

    fun apply(s: EngineSettings) { core?.update(s); pendingCore?.update(s) }

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) throw UnhandledAudioFormatException(inputAudioFormat)
        val c = EngineCore(inputAudioFormat.sampleRate, inputAudioFormat.channelCount, settings())
        pendingCore = c
        // v2.0.6: keep the input's encoding. Float output into a non-float sink (Sonic/AudioTrack on Android 10) failed with
        // "Unhandled input format ... encoding=4" (ERROR_CODE_AUDIO_TRACK_INIT_FAILED). 16-bit in -> 16-bit out (TPDF-dithered).
        return AudioFormat(c.outRate, inputAudioFormat.channelCount, inputAudioFormat.encoding)
    }

    override fun onFlush() { pendingCore?.let { core = it; pendingCore = null } }
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
        if (float) for (i in 0 until frames * ch) inFloats[i] = src.getFloat()
        else for (i in 0 until frames * ch) inFloats[i] = src.getShort() / 32768f
        inputBuffer.position(inputBuffer.limit())
        val maxOut = c.maxOutputFrames(frames) * ch
        if (outFloats.size < maxOut) outFloats = FloatArray(maxOut)
        val t0 = System.nanoTime()
        val n = c.process(inFloats, frames, outFloats)
        c.guard.record(System.nanoTime() - t0, frames * 1_000_000_000L / inputAudioFormat.sampleRate)
        val out: ByteBuffer
        if (float) {
            out = replaceOutputBuffer(n * ch * 4)
            for (i in 0 until n * ch) out.putFloat(outFloats[i])
        } else {
            out = replaceOutputBuffer(n * ch * 2)
            for (i in 0 until n * ch) {
                val d = (rng.nextFloat() - rng.nextFloat()) / 32768f
                val v = ((outFloats[i] + d) * 32767f).coerceIn(-32768f, 32767f)
                out.putShort(Math.round(v).toShort())
            }
        }
        out.flip()
        val now = System.currentTimeMillis()
        if (now - lastStatusAt > 500) { lastStatusAt = now; _status.value = c.status }
    }

    companion object {
        private val _status = MutableStateFlow(EngineStatus())
        val status: StateFlow<EngineStatus> = _status
    }
}
