package com.ravi.musicspot.core

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.util.Log
import com.ravi.musicspot.BuildConfig
import com.ravi.musicspot.CrashActivity
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Crash safety net (v2.0.1). Installed first thing in MusicSpotApp.onCreate, before anything else can fail:
 * any uncaught exception on any thread writes the full stack trace plus device info to filesDir/crash.txt,
 * then opens [CrashActivity] in the separate ":crash" process (plain Android views, no Compose, no Graph) so the
 * user can copy/share the trace. If the crash screen cannot be opened, the next launch shows the saved report.
 *
 * Deliberately uses only java.io and framework classes that exist on API 26.
 */
object CrashGuard {
    private const val TAG = "UpZe"
    const val FILE = "crash.txt"
    const val PREV_FILE = "crash-prev.txt"
    const val CRASH_PROCESS_SUFFIX = ":crash"

    @Volatile private var installed = false
    @Volatile private var handling = false

    fun crashFile(ctx: Context) = File(ctx.filesDir, FILE)
    fun previousFile(ctx: Context) = File(ctx.filesDir, PREV_FILE)

    /** The current process name, read without Application.getProcessName() (API 28+). */
    fun processName(ctx: Context): String {
        runCatching {
            val raw = File("/proc/self/cmdline").readBytes()
            val end = raw.indexOfFirst { it == 0.toByte() }.let { if (it < 0) raw.size else it }
            val name = String(raw, 0, end, Charsets.UTF_8).trim()
            if (name.isNotEmpty()) return name
        }
        return ctx.packageName
    }

    fun isCrashProcess(ctx: Context) = processName(ctx).endsWith(CRASH_PROCESS_SUFFIX)

    fun install(app: Application) {
        if (installed) return
        installed = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            if (handling) { previous?.uncaughtException(thread, error); return@setDefaultUncaughtExceptionHandler }
            handling = true
            try {
                val report = buildReport(app, thread, error)
                runCatching { Log.e(TAG, "Uncaught exception, saved to $FILE", error) }
                write(app, report)
                if (!isCrashProcess(app) && Build.FINGERPRINT != "robolectric") openCrashScreen(app)
            } catch (_: Throwable) {
                // Never throw from the handler itself.
            }
            if (isCrashProcess(app) || Build.FINGERPRINT == "robolectric") {
                // The crash screen itself failed: let the system handle it (no loop). JVM tests: report normally.
                previous?.uncaughtException(thread, error)
            } else {
                Process.killProcess(Process.myPid())
                System.exit(10)
            }
        }
    }

    /** Records a non-fatal problem caught by a startup guard (logged, and kept in filesDir/startup-warnings.txt). */
    fun nonFatal(ctx: Context?, where: String, e: Throwable) {
        runCatching { Log.w(TAG, "Startup step failed: $where", e) }
        if (ctx == null) return
        runCatching {
            val f = File(ctx.filesDir, "startup-warnings.txt")
            if (f.length() > 64 * 1024) f.delete()
            f.appendText("${stamp()} [$where] ${stackTrace(e).take(4000)}\n")
        }
    }

    fun buildReport(ctx: Context, thread: Thread, error: Throwable): String = buildString {
        appendLine("UpZe crash report")
        appendLine("Time: ${stamp()}")
        appendLine("App: ${BuildConfig.APPLICATION_ID} ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${BuildConfig.BUILD_TYPE}")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("ABIs: ${Build.SUPPORTED_ABIS.joinToString()}")
        appendLine("Process: ${processName(ctx)}  Thread: ${thread.name}")
        appendLine()
        append(stackTrace(error))
        val warnings = File(ctx.filesDir, "startup-warnings.txt")
        if (warnings.isFile) {
            appendLine()
            appendLine("--- startup warnings ---")
            append(runCatching { warnings.readText().takeLast(8000) }.getOrDefault(""))
        }
    }

    fun stackTrace(e: Throwable): String = StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString()

    private fun stamp() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())

    private fun write(ctx: Context, report: String) {
        val f = crashFile(ctx)
        f.parentFile?.mkdirs()
        f.writeText(report)
    }

    private fun openCrashScreen(ctx: Context) {
        val i = Intent(ctx, CrashActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        ctx.startActivity(i)
    }

    /** A report saved by an earlier crash that has not been shown yet. */
    fun pendingReport(ctx: Context): File? = crashFile(ctx).takeIf { it.isFile && it.length() > 0 }

    /** Moves the shown report aside (kept as crash-prev.txt for Settings › About › Last crash report). */
    fun archive(ctx: Context) {
        runCatching {
            val f = crashFile(ctx)
            if (!f.isFile) return
            val prev = previousFile(ctx)
            prev.delete()
            if (!f.renameTo(prev)) { prev.writeText(f.readText()); f.delete() }
        }
    }
}
