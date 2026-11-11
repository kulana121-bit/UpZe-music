package com.ravi.musicspot.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.ravi.musicspot.Graph
import com.ravi.musicspot.MainActivity
import com.ravi.musicspot.R
import com.ravi.musicspot.data.PlayStatsData
import com.ravi.musicspot.logic.Wrapped
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.TimeZone

/**
 * Pack 5 Part 2: "This week" stats widget (3×2, resizable to 4×2): plays this week, current listening streak and the
 * top song, all from [Wrapped.summarize] over the on-device PlayStats (no maths of its own, see [StatsCard]).
 * Refreshed on the system cycle (updatePeriodMillis 30 min) and on every now-playing push. Tap → Your stats.
 */
class StatsWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        refresh(context) { runCatching { pending.finish() } }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, newOptions: android.os.Bundle?) {
        val pending = goAsync()
        refresh(context) { runCatching { pending.finish() } }
    }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Re-renders every stats widget from the current PlayStats (waits for the stores to load on a cold start). */
        fun refresh(context: Context, done: () -> Unit = {}) {
            val app = context.applicationContext
            val m = AppWidgetManager.getInstance(app)
            val ids = runCatching { m.getAppWidgetIds(ComponentName(app, StatsWidget::class.java)) }.getOrNull()
            if (ids == null || ids.isEmpty()) { done(); return }
            scope.launch {
                try {
                    withTimeoutOrNull(8_000) { Graph.awaitReady() }
                    val data = runCatching { Graph.playStats.data.value }.getOrNull() ?: PlayStatsData()
                    val model = StatsCard.from(data, System.currentTimeMillis())
                    for (id in ids) runCatching { m.updateAppWidget(id, views(app, m, id, model)) }
                } finally { done() }
            }
        }

        private fun views(app: Context, m: AppWidgetManager, id: Int, s: StatsCard.Model): RemoteViews {
            val v = RemoteViews(app.packageName, R.layout.widget_stats)
            v.setImageViewBitmap(R.id.s_bg, WidgetCard.forWidget(app, m, id, 180, 110))
            if (s.empty) {
                v.setViewVisibility(R.id.s_numbers, View.GONE)
                v.setViewVisibility(R.id.s_top, View.GONE)
                v.setViewVisibility(R.id.s_empty, View.VISIBLE)
                v.setTextViewText(R.id.s_empty, StatsCard.EMPTY_LINE)
            } else {
                v.setViewVisibility(R.id.s_numbers, View.VISIBLE)
                v.setViewVisibility(R.id.s_empty, View.GONE)
                v.setTextViewText(R.id.s_plays, s.plays.toString())
                v.setTextViewText(R.id.s_plays_label, s.playsLabel)
                v.setTextViewText(R.id.s_streak, s.streak.toString())
                v.setTextViewText(R.id.s_streak_label, s.streakLabel)
                if (s.topSong != null) { v.setViewVisibility(R.id.s_top, View.VISIBLE); v.setTextViewText(R.id.s_top, s.topSong) }
                else v.setViewVisibility(R.id.s_top, View.GONE)
            }
            val open = PendingIntent.getActivity(app, 20, Intent(app, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_ROUTE, MainActivity.ROUTE_WRAPPED)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            v.setOnClickPendingIntent(R.id.s_root, open)
            return v
        }
    }
}

/** What the stats widget shows: a direct read of [Wrapped.summarize] for this week (unit-tested against it). */
object StatsCard {
    const val EMPTY_LINE = "Play something — your week in music will appear here"

    data class Model(
        val empty: Boolean,
        val plays: Int = 0,
        val playsLabel: String = "",
        val streak: Int = 0,
        val streakLabel: String = "",
        /** "Title · Artist" of the week's top song, or null. */
        val topSong: String? = null,
    )

    fun from(d: PlayStatsData, nowMs: Long, tz: TimeZone = TimeZone.getDefault()): Model {
        val s = Wrapped.summarize(d, Wrapped.Range.WEEK, nowMs, tz)
        if (s.isEmpty) return Model(empty = true)
        val top = s.topSongs.firstOrNull()?.track
        return Model(
            empty = false,
            plays = s.totalPlays,
            playsLabel = if (s.totalPlays == 1) "play" else "plays",
            streak = s.currentStreak,
            streakLabel = "day streak",
            topSong = top?.let { t -> if (t.artist.isBlank()) t.title else "${t.title} · ${t.artist}" },
        )
    }
}
