package com.ravi.musicspot

import com.ravi.musicspot.update.UpdateLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateLogicTest {
    @Test fun parsesTags() {
        assertEquals(listOf(1, 0, 1), UpdateLogic.parseVersion("v1.0.1")!!.core)
        assertEquals(listOf(2, 3), UpdateLogic.parseVersion("V2.3")!!.core)
        assertEquals(listOf("rc", "1"), UpdateLogic.parseVersion("1.0.0-rc.1+build.5")!!.pre)
        assertNull(UpdateLogic.parseVersion("latest"))
        assertNull(UpdateLogic.parseVersion(""))
        assertNull(UpdateLogic.parseVersion(null))
    }

    @Test fun comparesVersions() {
        assertTrue(UpdateLogic.isNewer("v1.0.1", "1.0.0"))
        assertTrue(UpdateLogic.isNewer("1.1.0", "1.0.9"))
        assertTrue(UpdateLogic.isNewer("1.10.0", "1.9.0"))       // numeric, not lexicographic
        assertTrue(UpdateLogic.isNewer("2.0", "1.9.9"))
        assertFalse(UpdateLogic.isNewer("v1.0.0", "1.0.0"))
        assertFalse(UpdateLogic.isNewer("1.0", "1.0.0"))          // missing parts count as 0
        assertFalse(UpdateLogic.isNewer("1.0.0", "1.0.1"))
        assertTrue(UpdateLogic.isNewer("1.0.0", "1.0.0-beta.2"))  // release beats its pre-release
        assertFalse(UpdateLogic.isNewer("1.0.0-beta.2", "1.0.0"))
        assertTrue(UpdateLogic.isNewer("1.0.0-beta.10", "1.0.0-beta.2"))
        assertTrue(UpdateLogic.isNewer("1.0.0-rc.1", "1.0.0-beta.9"))
        assertFalse(UpdateLogic.isNewer("garbage", "1.0.0"))
    }

    private val sample = """
        {"url":"https://api.github.com/repos/kulana121-bit/UpZe-music/releases/1","html_url":"https://github.com/kulana121-bit/UpZe-music/releases/tag/v1.0.1",
         "tag_name":"v1.0.1","name":"UpZe 1.0.1","draft":false,"prerelease":false,
         "body":"## What's new\r\n- **Faster** search\r\n- Fixed [lyrics](https://example.com) sync\r\n",
         "assets":[{"name":"checksums.txt","size":120,"browser_download_url":"https://github.com/x/checksums.txt"},
                   {"name":"UpZe-v1.0.1.apk","size":31457280,"browser_download_url":"https://github.com/kulana121-bit/UpZe-music/releases/download/v1.0.1/UpZe-v1.0.1.apk"},
                   {"name":"other.apk","size":1,"browser_download_url":"https://github.com/x/other.apk"}],
         "unknown_field":{"nested":true}}
    """.trimIndent()

    @Test fun parsesRelease() {
        val r = UpdateLogic.parseRelease(sample)
        assertNotNull(r); r!!
        assertEquals("v1.0.1", r.tag)
        assertEquals("1.0.1", r.version)
        assertEquals("UpZe 1.0.1", r.name)
        assertEquals("UpZe-v1.0.1.apk", r.apkName)        // first .apk asset
        assertEquals(31457280L, r.apkSize)
        assertTrue(r.apkUrl.endsWith("/UpZe-v1.0.1.apk"))
        assertEquals("What's new\n• Faster search\n• Fixed lyrics sync", r.notes)
    }

    @Test fun rejectsUnusableReleases() {
        assertNull(UpdateLogic.parseRelease("not json"))
        assertNull(UpdateLogic.parseRelease("""{"message":"Not Found"}"""))
        assertNull(UpdateLogic.parseRelease(sample.replace("\"draft\":false", "\"draft\":true")))
        assertNull(UpdateLogic.parseRelease(sample.replace("\"prerelease\":false", "\"prerelease\":true")))
        assertNull(UpdateLogic.parseRelease("""{"tag_name":"v1.0.1","assets":[{"name":"notes.txt","browser_download_url":"https://x/y"}]}"""))
        assertNull(UpdateLogic.parseRelease("""{"tag_name":"v1.0.1","assets":[{"name":"a.apk","browser_download_url":"http://insecure/a.apk"}]}"""))
        assertNull(UpdateLogic.parseRelease("""{"tag_name":"nightly","assets":[{"name":"a.apk","browser_download_url":"https://x/a.apk"}]}"""))
    }

    @Test fun cleansMarkdown() {
        assertEquals("Title\n\n• one\n• two `x`".replace("`", ""), UpdateLogic.cleanNotes("# Title\n\n\n\n* one\n- two `x`"))
        assertEquals("alt and link text, bold and italic", UpdateLogic.cleanNotes("![alt](a.png) and [link text](u), __bold__ and _italic_"))
        assertEquals("snake_case_name stays", UpdateLogic.cleanNotes("snake_case_name stays"))
    }
}
