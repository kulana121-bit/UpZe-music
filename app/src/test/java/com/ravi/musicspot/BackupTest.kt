package com.ravi.musicspot

import com.ravi.musicspot.data.Backup
import com.ravi.musicspot.data.BackupFile
import com.ravi.musicspot.data.LibraryData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.0.2: backup files round-trip, and other files are refused. */
class BackupTest {
    @Test fun roundTrip() {
        val b = BackupFile(app = BackupFile.APP, format = BackupFile.FORMAT, appVersion = "1.0.2", createdMs = 5, library = LibraryData())
        val back = Backup.decode(Backup.encode(b))
        assertEquals(b, back)
    }

    @Test fun rejectsOtherJson() {
        for (text in listOf("{}", "{\"app\":\"Other\",\"format\":1}", "not json", "{\"app\":\"UpZe\",\"format\":99}")) {
            assertTrue(text, runCatching { Backup.decode(text) }.exceptionOrNull() is IllegalArgumentException)
        }
    }
}
