package com.beatflowy.app.repository

import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.beatflowy.app.model.DownloadItem
import com.beatflowy.app.model.DownloadQuality
import com.beatflowy.app.model.DownloadSettings
import com.beatflowy.app.model.DownloadStatus
import com.beatflowy.app.model.FilenameTemplate
import com.beatflowy.app.model.DownloadProgress
import kotlinx.coroutines.flow.collect

class DownloadWorker(
    context: Context,
    parameters: WorkerParameters
) : CoroutineWorker(context, parameters) {

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override suspend fun doWork(): Result {
        val trackId = inputData.getString("track_id") ?: return Result.failure()
        val title = inputData.getString("title") ?: "Unknown"
        val artist = inputData.getString("artist") ?: "Unknown"
        val album = inputData.getString("album") ?: "Unknown"
        val qualityCode = inputData.getInt("quality_code", 6)
        val destinationUri = inputData.getString("destination_uri") ?: return Result.failure()
        val templateName = inputData.getString("filename_template") ?: FilenameTemplate.ARTIST_TITLE.name
        val createSubfolders = inputData.getBoolean("create_subfolders", true)
        val overwrite = inputData.getBoolean("overwrite", false)

        val quality = when (qualityCode) {
            27 -> DownloadQuality.HiRes24Bit
            else -> DownloadQuality.Lossless
        }

        val settings = DownloadSettings(
            defaultQuality = quality,
            filenameTemplate = FilenameTemplate.valueOf(templateName),
            createAlbumSubfolders = createSubfolders,
            overwriteExisting = overwrite,
            downloadLocation = destinationUri
        )

        val item = DownloadItem(
            id = trackId,
            title = title,
            artist = artist,
            album = album,
            quality = quality,
            coverUrl = null,
            status = DownloadStatus.QUEUED,
            progressPercent = 0,
            fileSizeBytes = 0
        )

        val repository = QobuzRepository(applicationContext)
        val notificationId = trackId.hashCode()

        setForeground(createForegroundInfo(notificationId, title, 0))

        return try {
            var finalUri: Uri? = null
            repository.downloadTrack(
                item,
                settings,
                Uri.parse(destinationUri),
                onFileDownloaded = { uri: Uri -> finalUri = uri }
            ).collect { progress: DownloadProgress ->
                when (progress) {
                    is DownloadProgress.InProgress -> {
                        this@DownloadWorker.setProgress(workDataOf("progress" to progress.progress))
                        notificationManager.notify(
                            notificationId,
                            createNotification(title, progress.progress)
                        )
                    }
                    is DownloadProgress.Failed -> {
                        throw java.lang.Exception(progress.error)
                    }
                    else -> {}
                }
            }
            
            showFinishedNotification(notificationId, title)
            
            if (finalUri != null) {
                Result.success(workDataOf("file_uri" to finalUri.toString()))
            } else {
                Result.success()
            }
        } catch (e: Exception) {
            val isCaptcha = e.message?.contains("Captcha required") == true
            Result.failure(workDataOf(
                "error" to (e.message ?: "Unknown error"),
                "is_captcha" to isCaptcha
            ))
        }
    }

    private fun createForegroundInfo(id: Int, title: String, progress: Int): ForegroundInfo {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                id,
                createNotification(title, progress),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(id, createNotification(title, progress))
        }
    }

    private fun createNotification(title: String, progress: Int) =
        NotificationCompat.Builder(applicationContext, "downloads")
            .setContentTitle("Downloading $title")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setProgress(100, progress, false)
            .build()

    private fun showFinishedNotification(id: Int, title: String) {
        val notification = NotificationCompat.Builder(applicationContext, "downloads")
            .setContentTitle("Download Complete")
            .setContentText(title)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .build()
        notificationManager.notify(id + 1, notification)
    }
}
