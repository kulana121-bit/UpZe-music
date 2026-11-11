package com.ravi.musicspot

import com.ravi.musicspot.model.SourceKind
import com.ravi.musicspot.source.QUALITY_NOT_VERIFIED
import com.ravi.musicspot.source.QualityEstimate
import com.ravi.musicspot.source.StreamFormatProbe
import com.ravi.musicspot.source.classifyStreamBytes
import com.ravi.musicspot.source.probeStreamFormat
import com.ravi.musicspot.source.streamInfoFor
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/** v1.04 FIX 1: a Monochrome stream may only be labelled FLAC when the probe positively confirms "fLaC". */
class MonochromeProbeTest {
    private val plainClient = OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).readTimeout(2, TimeUnit.SECONDS).build()

    private fun assertUnverified(probe: StreamFormatProbe) {
        val info = streamInfoFor(probe, SourceKind.MONOCHROME, "mono:1")
        assertEquals(QUALITY_NOT_VERIFIED, info.codec)
        assertNull(info.bitrateKbps)
        assertFalse("an unverified stream must never count as lossless", QualityEstimate.isLosslessStream(info))
        assertFalse(info.label.contains("FLAC", ignoreCase = true))
        assertFalse(info.label.contains("1411"))
        assertFalse(info.label.contains("lossless", ignoreCase = true))
    }

    @Test fun probeFailureIsUnverifiedNeverFlac() {
        assertUnverified(StreamFormatProbe.Failed)
    }

    @Test fun probeThatThrowsIsFailed() {
        // The app's shared client refuses plain http:// (Http.httpsOnly): the probe itself throws.
        val p = probeStreamFormat("http://example.invalid/stream.flac")
        assertEquals(StreamFormatProbe.Failed, p)
        assertUnverified(p)
    }

    @Test fun unreachableHostIsFailed() {
        val p = probeStreamFormat("https://127.0.0.1:1/stream.flac", plainClient)
        assertEquals(StreamFormatProbe.Failed, p)
        assertUnverified(p)
    }

    @Test fun httpErrorIsFailed() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(500))
            server.start()
            val p = probeStreamFormat(server.url("/s").toString(), plainClient)
            assertEquals(StreamFormatProbe.Failed, p)
            assertUnverified(p)
        }
    }

    @Test fun emptyBodyIsFailed() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Type", "audio/flac").setBody(""))
            server.start()
            val p = probeStreamFormat(server.url("/s").toString(), plainClient)
            assertEquals(StreamFormatProbe.Failed, p) // a FLAC Content-Type alone is not proof
            assertUnverified(p)
        }
    }

    @Test fun confirmedFlacHeaderIsFlac() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(206).setBody(Buffer().write(byteArrayOf(0x66, 0x4C, 0x61, 0x43, 0, 0, 0, 0x22))))
            server.start()
            val p = probeStreamFormat(server.url("/s").toString(), plainClient)
            assertEquals(StreamFormatProbe.Flac, p)
            val info = streamInfoFor(p, SourceKind.MONOCHROME, "mono:1")
            assertEquals("FLAC", info.codec)
            assertEquals(1411, info.bitrateKbps)
            assertTrue(QualityEstimate.isLosslessStream(info))
            assertEquals("bytes=0-11", server.takeRequest().getHeader("Range"))
        }
    }

    @Test fun nonFlacIsLabelledWithDeliveredFormat() {
        val mp4 = byteArrayOf(0, 0, 0, 0x20, 0x66, 0x74, 0x79, 0x70, 0x4D, 0x34, 0x41, 0x20) // ....ftypM4A
        assertEquals(StreamFormatProbe.NotFlac("AAC"), classifyStreamBytes(mp4, null))
        assertEquals(StreamFormatProbe.NotFlac("MP3"), classifyStreamBytes(byteArrayOf(0x49, 0x44, 0x33, 4), null))
        assertEquals(StreamFormatProbe.NotFlac("Ogg"), classifyStreamBytes("OggS".toByteArray(), null))
        assertEquals(StreamFormatProbe.NotFlac("AAC"), classifyStreamBytes(byteArrayOf(0xFF.toByte(), 0xF1.toByte(), 0x50), null))
        val aac = streamInfoFor(classifyStreamBytes(mp4, null), SourceKind.MONOCHROME, "mono:1")
        assertEquals("AAC", aac.codec)
        assertNull(aac.bitrateKbps)
        assertFalse(QualityEstimate.isLosslessStream(aac))
    }

    @Test fun unknownBytesAreUnverifiedEvenWithFlacContentType() {
        val p = classifyStreamBytes(byteArrayOf(1, 2, 3, 4), "audio/flac")
        assertEquals(StreamFormatProbe.NotFlac(null), p)
        assertUnverified(p)
    }
}
