package com.ravi.musicspot

import com.ravi.musicspot.playback.SeamlessRetryPolicy
import com.ravi.musicspot.playback.StreamSwitchException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SeamlessSwitchTest {
    @Test fun delaysStayInsideTheBuffer() {
        val total = (1..SeamlessRetryPolicy.MAX_RETRIES).sumOf { SeamlessRetryPolicy.delayFor(it) }
        assertTrue("retries must finish within the 15 s minimum buffer", total < 15_000)
        assertEquals(300L, SeamlessRetryPolicy.delayFor(1))
        assertEquals(5_000L, SeamlessRetryPolicy.delayFor(99))
    }

    @Test fun switchIsFoundInCauseChain() {
        val e = java.io.IOException("wrapped", StreamSwitchException("t", "yt:a@251", "js:a@320"))
        assertTrue(SeamlessRetryPolicy.chain(e).any { it is StreamSwitchException })
        assertTrue(403 in SeamlessRetryPolicy.RETRY_CODES && 410 in SeamlessRetryPolicy.RETRY_CODES)
    }
}
