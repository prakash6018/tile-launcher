package com.tilelauncher.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.tilelauncher.MainActivity
import com.tilelauncher.R
import org.json.JSONObject

/** Everything the glance widget shows, saved so it can redraw even when the launcher isn't running. */
data class GlanceSnapshot(
    val accent: Int,
    val weatherTemp: String?,
    val weatherLines: List<String>,
    val nextTitle: String?,
    val nextWhen: String?,
    val unread: Int,
    val unreadApps: String,
    val nowPlaying: String?,
) {
    fun toJson(): String = JSONObject()
        .put("accent", accent)
        .put("weatherTemp", weatherTemp)
        .put("weatherLines", weatherLines.joinToString("\n"))
        .put("nextTitle", nextTitle)
        .put("nextWhen", nextWhen)
        .put("unread", unread)
        .put("unreadApps", unreadApps)
        .put("nowPlaying", nowPlaying)
        .toString()

    companion object {
        fun fromJson(json: String): GlanceSnapshot? = runCatching {
            val o = JSONObject(json)
            fun str(key: String) = if (o.isNull(key)) null else o.optString(key).takeIf { it.isNotEmpty() }
            GlanceSnapshot(
                accent = o.getInt("accent"),
                weatherTemp = str("weatherTemp"),
                weatherLines = str("weatherLines")?.split("\n").orEmpty(),
                nextTitle = str("nextTitle"),
                nextWhen = str("nextWhen"),
                unread = o.optInt("unread"),
                unreadApps = o.optString("unreadApps"),
                nowPlaying = str("nowPlaying"),
            )
        }.getOrNull()
    }
}

/**
 * "Tile glance": clock, weather, next meeting and unread counts as four live tiles. Sized for
 * the Galaxy Z Flip cover screen (see res/xml/samsung_glance_widget.xml) and usable on any
 * home screen. The launcher pushes fresh snapshots while it runs.
 */
class GlanceWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        GlanceWidget.render(context, manager, ids, GlanceWidget.lastSnapshot(context))
    }
}

object GlanceWidget {

    private const val PREFS = "glance_widget"
    private const val KEY = "snapshot"

    fun hasInstances(context: Context): Boolean = ids(context).isNotEmpty()

    fun update(context: Context, snapshot: GlanceSnapshot) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, snapshot.toJson()).apply()
        render(context, AppWidgetManager.getInstance(context), ids(context), snapshot)
    }

    fun lastSnapshot(context: Context): GlanceSnapshot? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)?.let(GlanceSnapshot::fromJson)

    private fun ids(context: Context): IntArray =
        AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, GlanceWidgetProvider::class.java))

    internal fun render(context: Context, manager: AppWidgetManager, ids: IntArray, snapshot: GlanceSnapshot?) {
        if (ids.isEmpty()) return
        val views = RemoteViews(context.packageName, R.layout.widget_glance)
        if (snapshot != null) {
            listOf(R.id.tile_clock, R.id.tile_weather, R.id.tile_next, R.id.tile_unread, R.id.now_playing).forEach {
                views.setInt(it, "setBackgroundColor", snapshot.accent)
            }
            views.setTextViewText(R.id.weather_temp, snapshot.weatherTemp ?: "—")
            views.setTextViewText(R.id.weather_line1, snapshot.weatherLines.getOrNull(0).orEmpty())
            views.setTextViewText(R.id.weather_line2, snapshot.weatherLines.getOrNull(1).orEmpty())
            views.setTextViewText(R.id.next_title, snapshot.nextTitle ?: "no more events")
            views.setTextViewText(R.id.next_when, snapshot.nextWhen.orEmpty())
            views.setTextViewText(R.id.unread_count, snapshot.unread.toString())
            views.setTextViewText(R.id.unread_apps, snapshot.unreadApps)
            if (snapshot.nowPlaying != null) {
                views.setViewVisibility(R.id.now_playing, View.VISIBLE)
                views.setTextViewText(R.id.now_playing, "♪ " + snapshot.nowPlaying)
            } else {
                views.setViewVisibility(R.id.now_playing, View.GONE)
            }
        }
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        views.setOnClickPendingIntent(R.id.glance_root, open)
        manager.updateAppWidget(ids, views)
    }
}
