@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.ravi.musicspot

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import com.ravi.musicspot.dsp.EngineSettings
import com.ravi.musicspot.dsp.EnhanceLevel
import com.ravi.musicspot.dsp.EqConfig
import com.ravi.musicspot.dsp.ReplayGainMode
import com.ravi.musicspot.playback.EngineAudioProcessor
import com.ravi.musicspot.playback.Gain
import com.ravi.musicspot.playback.ReplayGainTags
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin

/** 1.0.10: with every effect off the engine passes PCM through byte for byte; any effect brings processing back. */
class EnginePassThroughTest {
    private val identity = EngineSettings(eq = EqConfig(enabled = false), bandwidthExtension = EnhanceLevel.OFF, transientRestore = false, spatial = false)

    private fun pcm16(frames: Int, ch: Int = 2): ByteArray {
        val b = ByteBuffer.allocate(frames * ch * 2).order(ByteOrder.nativeOrder())
        for (i in 0 until frames) for (c in 0 until ch) b.putShort((12000 * sin(i * 0.05 + c)).toInt().toShort())
        return b.array()
    }

    private fun pcmFloat(frames: Int, ch: Int = 2): ByteArray {
        val b = ByteBuffer.allocate(frames * ch * 4).order(ByteOrder.nativeOrder())
        for (i in 0 until frames) for (c in 0 until ch) b.putFloat((0.4 * sin(i * 0.03 + c)).toFloat())
        return b.array()
    }

    /** Runs [buffers] buffers of [data] through a freshly configured processor and returns everything it output. */
    private fun run(s: EngineSettings, data: ByteArray, encoding: Int = C.ENCODING_PCM_16BIT, buffers: Int = 3,
                    tags: ReplayGainTags? = null): List<ByteArray> {
        val p = EngineAudioProcessor { s }
        val fmt = p.configure(AudioProcessor.AudioFormat(44_100, 2, encoding))
        assertEquals(encoding, fmt.encoding)
        p.flush()
        if (tags != null) p.setTrack("t1", tags)
        return (0 until buffers).map {
            val inBuf = ByteBuffer.allocateDirect(data.size).order(ByteOrder.nativeOrder()); inBuf.put(data); inBuf.flip()
            p.queueInput(inBuf)
            assertEquals(0, inBuf.remaining())
            val out = p.output
            ByteArray(out.remaining()).also { out.get(it) }
        }
    }

    @Test fun identityConfigPassesPcm16ThroughByteIdentical() {
        val data = pcm16(2048)
        run(identity, data).forEach { assertArrayEquals(data, it) }
    }

    @Test fun identityConfigPassesFloatThroughByteIdentical() {
        val data = pcmFloat(2048)
        run(identity, data, C.ENCODING_PCM_FLOAT).forEach { assertArrayEquals(data, it) }
    }

    @Test fun eqEnabledButNeutralIsStillPassThrough() {
        val data = pcm16(1024)
        run(identity.copy(eq = EqConfig(enabled = true)), data).forEach { assertArrayEquals(data, it) }
    }

    @Test fun anyEffectOnIsProcessed() {
        val data = pcm16(2048)
        val effects = listOf(
            "eq" to identity.copy(eq = EqConfig(enabled = true, preampDb = -6.0)),
            "bwe" to identity.copy(bandwidthExtension = EnhanceLevel.LIGHT),
            "transient" to identity.copy(transientRestore = true),
            "spatial" to identity.copy(spatial = true),
        )
        for ((name, s) in effects) {
            val out = run(s, data, buffers = 4)
            assertFalse("$name should change the PCM", out.all { it.contentEquals(data) })
        }
    }

    @Test fun levellingGainIsProcessed() {
        val data = pcm16(2048)
        val s = identity.copy(replayGain = ReplayGainMode.TRACK)
        val out = run(s, data, buffers = 4, tags = ReplayGainTags(track = Gain(-6.0, 0.9)))
        assertFalse(out.last().contentEquals(data))
    }
}
