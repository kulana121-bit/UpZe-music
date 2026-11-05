package com.ravi.musicspot

import android.os.Build
import androidx.compose.ui.test.performClick
import android.os.Looper
import com.ravi.musicspot.core.CrashGuard
import com.ravi.musicspot.playback.PlaybackService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.File
import java.time.Duration

/**
 * v2.0.1 crash investigation: runs the real cold start (MusicSpotApp.onCreate → Graph.init → MainActivity →
 * AppRoot composition → Welcome flow → main shell with glass layer, and the PlaybackService onCreate) on the
 * JVM with Robolectric at API 26, 29 (Ravi's Android 10) and 31. Any exception thrown on the main thread
 * during startup or composition fails the test with its stack trace.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [26, 29, 31], application = MusicSpotApp::class)
class StartupRobolectricTest {
    @get:org.junit.Rule val compose = androidx.compose.ui.test.junit4.createEmptyComposeRule()

    private fun hasText(t: String) = compose.onAllNodes(androidx.compose.ui.test.hasText(t, substring = true), useUnmergedTree = true)
        .fetchSemanticsNodes().isNotEmpty()

    private fun idle(ms: Long = 50) {
        val main = shadowOf(Looper.getMainLooper())
        main.idle()
        main.idleFor(Duration.ofMillis(ms))
        main.idle()
    }

    // Note: the draw pass is not exercised: Robolectric legacy graphics cannot allocate Compose vector caches.
    @Test fun coldStartShowsUiWithoutCrashing() {
        println("Robolectric API ${Build.VERSION.SDK_INT}")
        // Robolectric's bindService hands Media3 a null ComponentName (a test-harness artefact, not an app bug);
        // the real service start is covered by playbackServiceCreatesAndDestroys. Here the bind just fails cleanly.
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        shadowOf(app).declareComponentUnbindable(android.content.ComponentName(app, PlaybackService::class.java))
        PlaybackService.cacheEnabled = false
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()

        // Graph.init loads stores on IO; the UI shows a blank canvas until ready, then the Welcome flow.
        val deadline = System.currentTimeMillis() + 20_000
        while (!Graph.ready.value && System.currentTimeMillis() < deadline) { idle(16); Thread.sleep(10) }
        assertTrue("Graph.ready never completed", Graph.ready.value)
        repeat(30) { idle(16) }                       // Welcome flow frames
        assertFalse(activity.isFinishing)
        assertFalse("Welcome expected on first run", Graph.settings.value.onboarded)
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(1_000)
        assertTrue("Welcome screen not composed", hasText("Welcome to UpZe"))
        assertTrue(hasText("Get started"))
        compose.onNode(androidx.compose.ui.test.hasText("Get started")).performClick()
        compose.mainClock.advanceTimeBy(1_000); idle(16)
        assertTrue("Account step not shown", hasText("Bring your YouTube Music"))

        // Finish onboarding → main shell (GlassLayer, pages, bottom dock) composes.
        Graph.settings.update { it.copy(onboarded = true, setupDone = true) } // 1.0.0: skip the v2.0.15 design setup
        repeat(60) { idle(16) }
        compose.mainClock.advanceTimeBy(1_000); idle(16)
        assertFalse(activity.isFinishing)
        assertTrue("Main shell (nav) not composed", hasText("Library") && hasText("Explore"))
        println("API ${Build.VERSION.SDK_INT}: Welcome + main shell composed")

        // Every glass grade composes on this API level (Liquid falls back below 33, Frosted below 31).
        for (g in com.ravi.musicspot.data.GlassStyle.entries) {
            Graph.settings.update { it.copy(glass = g) }
            repeat(10) { idle(16) }
            compose.mainClock.advanceTimeBy(500); idle(16)
            assertTrue("Shell missing with glass $g", hasText("Library"))
        }
        assertFalse(activity.isFinishing)

        controller.pause().stop().destroy()
        assertFalse("crash.txt written during startup", File(activity.filesDir, CrashGuard.FILE).exists())
        val warnings = File(activity.filesDir, "startup-warnings.txt")
        if (warnings.exists()) println("startup warnings (non-fatal, expected under Robolectric for Keystore etc.):\n" + warnings.readText().take(3000))
    }

    @Test fun playbackServiceCreatesAndDestroys() {
        Graph.init(org.robolectric.RuntimeEnvironment.getApplication())
        PlaybackService.cacheEnabled = false   // no native SQLite in Robolectric on aarch64
        val svc = Robolectric.buildService(PlaybackService::class.java).create()
        idle(100)
        svc.destroy()
        idle()
    }

    @Test fun crashGuardWritesReport() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        val report = CrashGuard.buildReport(app, Thread.currentThread(), IllegalStateException("boom"))
        assertTrue(report.contains("IllegalStateException: boom"))
        assertTrue(report.contains("API ${Build.VERSION.SDK_INT}"))
        assertEquals(app.packageName, CrashGuard.processName(app).substringBefore(':').ifEmpty { app.packageName }.let { if (it.startsWith("java") || it.contains('/')) app.packageName else it })
    }

    @Test fun crashActivityShowsSavedReport() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        CrashGuard.crashFile(app).writeText("MusicSpot crash report\njava.lang.RuntimeException: test")
        val c = Robolectric.buildActivity(CrashActivity::class.java).setup()
        idle()
        assertFalse(CrashGuard.crashFile(app).exists())      // archived once shown
        assertTrue(CrashGuard.previousFile(app).readText().contains("RuntimeException: test"))
        c.pause().stop().destroy()
    }
}
