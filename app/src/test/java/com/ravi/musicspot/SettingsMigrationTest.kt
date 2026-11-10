package com.ravi.musicspot

import com.ravi.musicspot.core.Http
import com.ravi.musicspot.data.AppSettings
import com.ravi.musicspot.data.Backup
import com.ravi.musicspot.data.DockStyle
import com.ravi.musicspot.data.DockStyleSerializer
import com.ravi.musicspot.data.SettingsRepo
import com.ravi.musicspot.data.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 1.0.6: settings files and backups written by 1.0.5 and older still load after the design trim. Old player / home
 * designs are dropped (Aura player and Spotlight Home are the only ones), old docks map to the two that remain, and
 * no other setting is reset.
 */
class SettingsMigrationTest {
    private fun legacy(player: String, dock: String, home: String) = """
        {"onboarded":true,"guest":false,"themeMode":"LIGHT","crossfadeSec":6,"glass":"FROSTED","setupDone":true,
         "playerStyle":"$player","dockStyle":"$dock","homeStyle":"$home","recentSearches":["a","b"]}
    """.trimIndent()

    private fun decode(text: String) = Http.json.decodeFromString(AppSettings.serializer(), text)

    @Test fun oldDesignValuesMapAndKeepOtherSettings() {
        val cases = listOf(
            Triple("CLASSIC", "CLASSIC", "CLASSIC") to DockStyle.CLASSIC,
            Triple("VINYL", "ISLAND", "AURA") to DockStyle.CLASSIC,
            Triple("MINIMAL", "EDGE", "BENTO") to DockStyle.CAPSULE,
            Triple("AURA", "CAPSULE", "SPOTLIGHT") to DockStyle.CAPSULE,
        )
        for ((old, dock) in cases) {
            val s = decode(legacy(old.first, old.second, old.third))
            assertEquals(old.toString(), dock, s.dockStyle)
            assertEquals(ThemeMode.LIGHT, s.themeMode)
            assertEquals(6, s.crossfadeSec)
            assertEquals(com.ravi.musicspot.data.GlassStyle.FROSTED, s.glass)
            assertEquals(listOf("a", "b"), s.recentSearches)
            assertTrue(s.onboarded && s.setupDone && !s.guest)
        }
    }

    @Test fun unknownDockFallsBackToClassic() {
        assertEquals(DockStyle.CLASSIC, DockStyleSerializer.fromName("SOMETHING_NEW"))
        assertEquals(DockStyle.CLASSIC, DockStyleSerializer.fromName("ISLAND"))
        assertEquals(DockStyle.CAPSULE, DockStyleSerializer.fromName("EDGE"))
        assertEquals(DockStyle.CAPSULE, DockStyleSerializer.fromName("CAPSULE"))
    }

    @Test fun defaultsAndRoundTrip() {
        assertEquals(DockStyle.CLASSIC, AppSettings().dockStyle)
        val text = Http.json.encodeToString(AppSettings.serializer(), AppSettings(dockStyle = DockStyle.CAPSULE))
        assertTrue(text, text.contains("\"dockStyle\":\"CAPSULE\""))
        assertFalse(text, text.contains("playerStyle") || text.contains("homeStyle"))
        assertEquals(DockStyle.CAPSULE, decode(text).dockStyle)
    }

    @Test fun oldBackupRestoresSettings() {
        val text = """{"app":"UpZe","format":1,"appVersion":"1.0.5","settings":${legacy("VINYL", "EDGE", "BENTO")}}"""
        val s = Backup.decode(text).settings!!
        assertEquals(DockStyle.CAPSULE, s.dockStyle)
        assertEquals(ThemeMode.LIGHT, s.themeMode)
    }

    @Test fun oldSettingsFileLoadsWithoutReset() {
        val dir: File = Files.createTempDirectory("upze-settings").toFile()
        try {
            File(dir, "settings.json").writeText(legacy("MINIMAL", "ISLAND", "AURA"))
            val repo = SettingsRepo(dir)
            repo.load()
            assertEquals(DockStyle.CLASSIC, repo.value.dockStyle)
            assertEquals(ThemeMode.LIGHT, repo.value.themeMode)
            assertEquals(6, repo.value.crossfadeSec)
            assertFalse("settings file must not be set aside as corrupt", File(dir, "settings.json.corrupt").exists())
        } finally { dir.deleteRecursively() }
    }
}
