package com.beatraxus.app.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import com.beatraxus.app.service.AudioPlaybackService

/**
 * Asks the playback service to immediately re-push its current state to all widgets.
 *
 * Without this, a widget that is freshly added to the home screen (or recreated by the
 * launcher after a reboot / app update) starts out with empty Glance state and shows the
 * hardcoded "Not Playing" defaults from MusicWidgets.kt. It would otherwise only get real
 * data the *next* time playback state changes (song change, play/pause, etc.) — so if
 * nothing changes after the widget is added, it's stuck showing defaults indefinitely even
 * though a song might already be playing.
 */
private fun requestWidgetRefresh(context: Context) {
    try {
        val intent = Intent(context, AudioPlaybackService::class.java).apply {
            action = AudioPlaybackService.ACTION_REFRESH_WIDGETS
        }
        context.startForegroundService(intent)
    } catch (e: Exception) {
        // Service may not be able to start in the background (e.g. app was force-stopped
        // and nothing has ever played). The widget will show its default state until
        // playback actually starts, which is expected in that case.
        Log.e("Beatraxus", "Could not request widget refresh", e)
    }
}

class MusicWidgetSmallReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MusicWidgetSmall()

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        requestWidgetRefresh(context)
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        requestWidgetRefresh(context)
    }
}

class MusicWidgetMediumReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MusicWidgetMedium()

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        requestWidgetRefresh(context)
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        requestWidgetRefresh(context)
    }
}

class MusicWidgetLargeReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MusicWidgetLarge()

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        requestWidgetRefresh(context)
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        requestWidgetRefresh(context)
    }
}