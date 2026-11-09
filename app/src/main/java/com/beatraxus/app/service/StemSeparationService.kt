package com.beatraxus.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.beatraxus.app.R
import com.beatraxus.app.engine.StemSeparationState
import com.beatraxus.app.engine.StemSeparatorManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps the app process at foreground-service priority while a stem separation runs. Without it, a separation that
 * goes on after the user leaves the app is the first thing Android's low-memory killer takes (the Max model holds
 * roughly a gigabyte). The service stops itself when the separation ends, fails or is cancelled.
 */
class StemSeparationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            StemSeparatorManager.cancelSeparation()
            stopSelf()
            return START_NOT_STICKY
        }
        ensureChannel()
        val first = buildNotification(null)
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, first)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not start as a foreground service: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        if (watcher == null) {
            watcher = scope.launch {
                StemSeparatorManager.state
                    .combine(StemSeparatorManager.queuedSongId) { st, queued -> st to queued }
                    .collect { (st, queued) ->
                        if (st is StemSeparationState.Separating) {
                            getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification(st))
                        } else if (queued == null) {
                            // Stay alive while another song is waiting its turn.
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        }
                    }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Stem separation", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(st: StemSeparationState.Separating?): Notification {
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, StemSeparationService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_search_notification)
            .setContentTitle(if (st != null) "Separating stems (${st.quality.label})" else "Separating stems")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "Cancel", cancel)
        if (st != null) {
            val pct = (st.progress * 100).toInt().coerceIn(0, 100)
            b.setContentText(if (st.totalChunks > 0) "$pct%  ·  chunk ${st.chunk}/${st.totalChunks}" else "$pct%")
            b.setProgress(100, pct, st.totalChunks == 0)
        } else {
            b.setProgress(100, 0, true)
        }
        return b.build()
    }

    companion object {
        private const val TAG = "StemSeparationService"
        private const val CHANNEL_ID = "stem_separation"
        private const val NOTIFICATION_ID = 7421
        private const val ACTION_CANCEL = "com.beatraxus.app.action.CANCEL_STEM_SEPARATION"

        /** Best effort: some situations (background start limits) forbid it, and separation still runs without it. */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, StemSeparationService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "Foreground service not started: ${e.message}")
            }
        }
    }
}
