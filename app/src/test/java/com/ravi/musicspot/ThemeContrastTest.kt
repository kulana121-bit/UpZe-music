package com.ravi.musicspot

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.ravi.musicspot.data.ThemeMode
import com.ravi.musicspot.logic.Contrast
import com.ravi.musicspot.ui.theme.MusicSpotTheme
import com.ravi.musicspot.ui.theme.softScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v2.0.2 "black text on black" regression. API 29 = no platform dynamic colour, so the app's own schemes are all
 * there is. Every text/icon role must reach WCAG AA (4.5:1) on the surfaces it is drawn on, for the default palette
 * and for album-art seeds (including pathological ones: black, white, pure yellow, deep blue), in Dark, Light and
 * Pure black. And the composition root must provide a readable LocalContentColor (Material's default is black).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = android.app.Application::class)
class ThemeContrastTest {
    @get:Rule val compose = androidx.compose.ui.test.junit4.createComposeRule()

    private val seeds = listOf(null, Color.Black, Color.White, Color(0xFFFFFF00), Color(0xFF0A1A6B), Color(0xFFE53935), Color(0xFF1DB954), Color(0xFF7E57C2), Color(0xFF8D6E63))

    private fun ratio(a: Color, b: Color) = Contrast.ratio(a.copy(alpha = 1f).toArgb(), b.copy(alpha = 1f).toArgb())

    private fun check(name: String, s: ColorScheme, failures: MutableList<String>) {
        val surfaces = listOf("background" to s.background, "surface" to s.surface, "surfaceContainerLow" to s.surfaceContainerLow,
            "surfaceContainer" to s.surfaceContainer, "surfaceContainerHigh" to s.surfaceContainerHigh, "surfaceContainerHighest" to s.surfaceContainerHighest, "surfaceVariant" to s.surfaceVariant)
        for ((sn, sc) in surfaces) {
            for ((fn, fc) in listOf("onSurface" to s.onSurface, "onBackground" to s.onBackground, "onSurfaceVariant" to s.onSurfaceVariant, "primary" to s.primary, "error" to s.error)) {
                val r = ratio(fc, sc)
                if (r < 4.5) failures += "$name: $fn on $sn = ${"%.2f".format(r)}"
            }
        }
        listOf("onPrimary/primary" to (s.onPrimary to s.primary), "onPrimaryContainer/primaryContainer" to (s.onPrimaryContainer to s.primaryContainer),
            "onSecondaryContainer/secondaryContainer" to (s.onSecondaryContainer to s.secondaryContainer), "onError/error" to (s.onError to s.error),
            "onErrorContainer/errorContainer" to (s.onErrorContainer to s.errorContainer), "inverseOnSurface/inverseSurface" to (s.inverseOnSurface to s.inverseSurface),
            "onTertiaryContainer/tertiaryContainer" to (s.onTertiaryContainer to s.tertiaryContainer),
        ).forEach { (n, p) -> val r = ratio(p.first, p.second); if (r < 4.5) failures += "$name: $n = ${"%.2f".format(r)}" }
    }

    @Test fun everyRoleIsAaInDarkLightAndPureBlack() {
        val failures = mutableListOf<String>()
        var checked = 0
        for (seed in seeds) for ((mode, dark, black) in listOf(Triple("dark", true, false), Triple("light", false, false), Triple("pureBlack", true, true))) {
            check("$mode seed=${seed?.toArgb()?.let { Integer.toHexString(it) } ?: "default"}", softScheme(seed, dark, black), failures); checked++
        }
        val d = softScheme(null, true, false); val l = softScheme(null, false, false)
        println("Contrast (API 29, no dynamic colour): $checked schemes checked. Default dark onBackground/background = ${"%.1f".format(ratio(d.onBackground, d.background))}:1, " +
            "light = ${"%.1f".format(ratio(l.onBackground, l.background))}:1, pure black = ${"%.1f".format(ratio(softScheme(null, true, true).onBackground, Color.Black))}:1")
        assertTrue("Contrast failures:\n" + failures.joinToString("\n"), failures.isEmpty())
        assertEquals(Color.Black, softScheme(null, true, true).background)
    }

    @Test fun rootProvidesReadableContentColor() {
        var content = Color.Unspecified; var bg = Color.Unspecified
        compose.setContent { MusicSpotTheme(artUrl = null, dynamic = false, mode = ThemeMode.DARK) { content = LocalContentColor.current; bg = MaterialTheme.colorScheme.background } }
        compose.waitForIdle()
        assertNotEquals("LocalContentColor is still Material's default black", Color.Black, content)
        assertTrue("dark text on background = ${ratio(content, bg)}", ratio(content, bg) >= 7.0)
        println("API 29 DARK: LocalContentColor #${Integer.toHexString(content.toArgb())} on #${Integer.toHexString(bg.toArgb())} = ${"%.1f".format(ratio(content, bg))}:1")
    }

    @Test fun rootProvidesReadableContentColorLight() {
        var content = Color.Unspecified; var bg = Color.Unspecified
        compose.setContent { MusicSpotTheme(null, false, ThemeMode.LIGHT) { content = LocalContentColor.current; bg = MaterialTheme.colorScheme.background } }
        compose.waitForIdle()
        assertTrue("light text on background = ${ratio(content, bg)}", ratio(content, bg) >= 7.0)
    }

    @Test fun rootProvidesReadableContentColorPureBlack() {
        var content = Color.Unspecified; var bg = Color.Unspecified
        compose.setContent { MusicSpotTheme(null, false, ThemeMode.PURE_BLACK) { content = LocalContentColor.current; bg = MaterialTheme.colorScheme.background } }
        compose.waitForIdle()
        assertEquals(Color.Black, bg)
        assertTrue("pure black text = ${ratio(content, bg)}", ratio(content, bg) >= 7.0)
    }
}
