package com.ravi.musicspot

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.ravi.musicspot.core.CrashGuard

/**
 * Crash report screen. Runs in its own ":crash" process (see AndroidManifest) with plain framework views only:
 * no Compose, no Graph, no AndroidX, so it still works when the main app cannot even start.
 * Shows the saved trace with Copy, Share and Restart app.
 */
class CrashActivity : Activity() {
    private var report: String = ""
    private var fromPrevious = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fromPrevious = intent?.getBooleanExtra(EXTRA_PREVIOUS, false) == true
        val file = if (fromPrevious) CrashGuard.previousFile(this) else CrashGuard.crashFile(this)
        report = runCatching { file.readText() }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: if (fromPrevious) "No crash report saved." else "UpZe stopped, but no crash report was saved."
        // The report is now safely shown; keep it as the "previous" report so the next launch starts normally.
        if (!fromPrevious) CrashGuard.archive(this)

        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#111114"))
            setPadding(px(16), px(24), px(16), px(16))
            fitsSystemWindows = true
        }
        root.addView(TextView(this).apply {
            text = if (fromPrevious) "Last crash report" else "UpZe crashed"
            setTextColor(Color.parseColor("#EDEBEA"))
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "Please send this report to the developer (Copy or Share). It contains the error and your phone model, " +
                "no personal data or passwords."
            setTextColor(Color.parseColor("#B9B7BD"))
            textSize = 14f
            setPadding(0, px(8), 0, px(12))
        })
        val trace = TextView(this).apply {
            text = report
            setTextColor(Color.parseColor("#EDEBEA"))
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
            setPadding(px(10), px(10), px(10), px(10))
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#202026"))
            addView(HorizontalScrollView(this@CrashActivity).apply { addView(trace) })
        }
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END; setPadding(0, px(12), 0, 0) }
        fun button(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { runCatching(onClick) }
        }
        buttons.addView(button("Copy") { copy() }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        buttons.addView(button("Share") { share() }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        buttons.addView(button(if (fromPrevious) "Close" else "Restart app") { if (fromPrevious) finish() else restart() },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        root.addView(buttons, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        setContentView(root)
    }

    private fun copy() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("UpZe crash report", report))
        Toast.makeText(this, "Crash report copied", Toast.LENGTH_SHORT).show()
    }

    private fun share() {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "UpZe crash report")
            putExtra(Intent.EXTRA_TEXT, report.take(100_000))
        }
        startActivity(Intent.createChooser(send, "Share crash report"))
    }

    private fun restart() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
    }

    companion object {
        const val EXTRA_PREVIOUS = "previous"

        /** Opens the last saved report (Settings › About). */
        fun showPrevious(ctx: Context) {
            ctx.startActivity(Intent(ctx, CrashActivity::class.java).putExtra(EXTRA_PREVIOUS, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
