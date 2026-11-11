package com.ravi.musicspot.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.ravi.musicspot.MainActivity
import com.ravi.musicspot.R

/**
 * Pack 5 Part 2: 4×1 shortcuts widget. Each button is a real action that works from a cold tap (MainActivity's
 * open-route extra): Search (Search tab), Downloads (the Downloads list), Shuffle (plays Liked songs shuffled, else
 * History), Resume (the saved queue at its saved position, which PlaybackService restores on start).
 */
class ShortcutsWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = render(context)
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, newOptions: android.os.Bundle?) = render(context)

    companion object {
        /** (button id, open-route value, request code). */
        private val BUTTONS = listOf(
            Triple(R.id.k_search, MainActivity.ROUTE_SEARCH, 30),
            Triple(R.id.k_downloads, MainActivity.ROUTE_DOWNLOADS, 31),
            Triple(R.id.k_shuffle, MainActivity.ROUTE_SHUFFLE, 32),
            Triple(R.id.k_resume, MainActivity.ROUTE_RESUME, 33),
        )

        fun render(context: Context) { runCatching {
            val app = context.applicationContext
            val m = AppWidgetManager.getInstance(app)
            for (id in m.getAppWidgetIds(ComponentName(app, ShortcutsWidget::class.java))) runCatching {
                val v = RemoteViews(app.packageName, R.layout.widget_shortcuts)
                v.setImageViewBitmap(R.id.k_bg, WidgetCard.forWidget(app, m, id, 250, 56))
                for ((view, route, code) in BUTTONS) {
                    val i = Intent(app, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_ROUTE, route).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    v.setOnClickPendingIntent(view, PendingIntent.getActivity(app, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                }
                m.updateAppWidget(id, v)
            }
        } }
    }
}
