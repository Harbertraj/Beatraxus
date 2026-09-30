package com.beatraxus.app.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

abstract class BaseMusicWidgetReceiver(override val glanceAppWidget: GlanceAppWidget) : GlanceAppWidgetReceiver() {
    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        scope.launch {
            WidgetStateStore.syncStateToWidgets(context)
        }
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        scope.launch {
            WidgetStateStore.syncStateToWidgets(context)
        }
    }
}

class MusicWidgetSmallReceiver : BaseMusicWidgetReceiver(MusicWidgetSmall())

class MusicWidgetMediumReceiver : BaseMusicWidgetReceiver(MusicWidgetMedium())

class MusicWidgetLargeReceiver : BaseMusicWidgetReceiver(MusicWidgetLarge())
