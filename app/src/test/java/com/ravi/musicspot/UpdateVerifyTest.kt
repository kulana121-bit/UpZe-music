package com.ravi.musicspot

import com.ravi.musicspot.update.UpdateLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** v1.04 FIX 2: a downloaded update is installed only when its SHA-256 matches the release asset's digest. */
class UpdateVerifyTest {
    // SHA-256("abc")
    private val abcSha = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

    private fun tempApk(bytes: ByteArray): File = File.createTempFile("upze-update", ".apk").apply { deleteOnExit(); writeBytes(bytes) }

    @Test fun computesSha256OfFile() {
        assertEquals(abcSha, UpdateLogic.sha256Hex(tempApk("abc".toByteArray())))
    }

    @Test fun matchingDigestVerifies() {
        val f = tempApk("abc".toByteArray())
        assertEquals(UpdateLogic.Verification.MATCH, UpdateLogic.verifyDownload(f, abcSha))
        assertEquals(UpdateLogic.Verification.MATCH, UpdateLogic.verifyDownload(f, abcSha.uppercase()))
    }

    @Test fun mismatchingDigestFails() {
        // Same size as "abc", different bytes: the old size-only check would have accepted it.
        val f = tempApk("abd".toByteArray())
        val v = UpdateLogic.verifyDownload(f, abcSha)
        assertEquals(UpdateLogic.Verification.MISMATCH, v)
        assertTrue(UpdateLogic.verificationFailedMessage(v).contains("failed verification"))
        assertTrue(UpdateLogic.verificationFailedMessage(v).contains("not installed"))
    }

    @Test fun missingDigestCannotVerify() {
        val f = tempApk("abc".toByteArray())
        assertEquals(UpdateLogic.Verification.NO_DIGEST, UpdateLogic.verifyDownload(f, null))
        assertEquals(UpdateLogic.Verification.NO_DIGEST, UpdateLogic.verifyDownload(f, "abc123"))
        assertTrue(UpdateLogic.verificationFailedMessage(UpdateLogic.Verification.NO_DIGEST).contains("failed verification"))
    }

    @Test fun digestFromReleaseJsonMatchesDownloadedFile() {
        val body = """{"tag_name":"v1.04","assets":[{"name":"UpZe-v1.04.apk","size":3,
            "browser_download_url":"https://github.com/x/y/releases/download/v1.04/UpZe-v1.04.apk","digest":"sha256:$abcSha"}]}"""
        val rel = UpdateLogic.parseRelease(body)!!
        assertEquals(UpdateLogic.Verification.MATCH, UpdateLogic.verifyDownload(tempApk("abc".toByteArray()), rel.apkSha256))
        assertEquals(UpdateLogic.Verification.MISMATCH, UpdateLogic.verifyDownload(tempApk("xyz".toByteArray()), rel.apkSha256))
    }
}
