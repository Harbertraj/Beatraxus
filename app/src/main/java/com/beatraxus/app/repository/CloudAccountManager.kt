package com.beatraxus.app.repository

import android.content.Context
import com.beatraxus.app.BeatraxusApplication
import com.beatraxus.app.model.SongSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class CloudAccountManager(private val context: Context, private val database: com.beatraxus.app.model.AppDatabase) {
    private val app = context.applicationContext as BeatraxusApplication
    val driveAccountRepository = app.driveAccountRepository
    val dropboxAccountRepository = app.dropboxAccountRepository
    val onedriveAccountRepository = app.onedriveAccountRepository
    val boxAccountRepository = app.boxAccountRepository
    val nextcloudAccountRepository = app.nextcloudAccountRepository
    val telegramChannelRepository = TelegramChannelRepository(context)
    
    private val songDao = database.songDao()
    private val favoriteDao = database.favoriteDao()
    private val recentlyPlayedDao = database.recentlyPlayedDao()

    suspend fun removeDriveAccount(email: String, onSongsRemoved: (List<String>) -> Unit) {
        driveAccountRepository.removeAccount(email)
        cleanupCloudAccountData(email, SongSource.GDRIVE, onSongsRemoved)
    }

    suspend fun removeDropboxAccount(email: String, onSongsRemoved: (List<String>) -> Unit) {
        dropboxAccountRepository.removeAccount(email)
        cleanupCloudAccountData(email, SongSource.DROPBOX, onSongsRemoved)
    }

    suspend fun removeOneDriveAccount(email: String, onSongsRemoved: (List<String>) -> Unit) {
        onedriveAccountRepository.removeAccount(email)
        cleanupCloudAccountData(email, SongSource.ONEDRIVE, onSongsRemoved)
    }

    suspend fun removeBoxAccount(email: String, onSongsRemoved: (List<String>) -> Unit) {
        boxAccountRepository.removeAccount(email)
        cleanupCloudAccountData(email, SongSource.BOX, onSongsRemoved)
    }

    suspend fun removeNextcloudAccount(serverUrl: String, username: String, onSongsRemoved: (List<String>) -> Unit) {
        nextcloudAccountRepository.removeAccount(serverUrl, username)
        cleanupCloudAccountData(username, SongSource.NEXTCLOUD, onSongsRemoved)
    }

    suspend fun removeTelegramChannel(url: String, onSongsRemoved: (List<String>) -> Unit) {
        telegramChannelRepository.removeChannel(url)
        withContext(Dispatchers.IO) {
            val songsToRemove = songDao.getSongsByTelegramChannel(url)
            val songIds = songsToRemove.map { it.id }
            songDao.deleteSongsByTelegramChannel(url)
            // Associated data cleanup for Telegram could be added here if needed
            onSongsRemoved(songIds)
        }
    }

    private suspend fun cleanupCloudAccountData(email: String, source: SongSource, onSongsRemoved: (List<String>) -> Unit) {
        withContext(Dispatchers.IO) {
            val emailLower = email.lowercase()
            val songsToRemove = when (source) {
                SongSource.GDRIVE -> songDao.getSongsByAccount(emailLower)
                SongSource.DROPBOX -> songDao.getSongsByDropboxAccount(emailLower)
                SongSource.ONEDRIVE -> songDao.getSongsByOneDriveAccount(emailLower)
                SongSource.BOX -> songDao.getSongsByBoxAccount(emailLower)
                SongSource.NEXTCLOUD -> songDao.getSongsByNextcloudAccount(emailLower)
                else -> emptyList()
            }
            val songIds = songsToRemove.map { it.id }

            when (source) {
                SongSource.GDRIVE -> songDao.deleteSongsByAccount(emailLower)
                SongSource.DROPBOX -> songDao.deleteSongsByDropboxAccount(emailLower)
                SongSource.ONEDRIVE -> songDao.deleteSongsByOneDriveAccount(emailLower)
                SongSource.BOX -> songDao.deleteSongsByBoxAccount(emailLower)
                SongSource.NEXTCLOUD -> songDao.deleteSongsByNextcloudAccount(emailLower)
                else -> {}
            }

            favoriteDao.deleteByAccount(emailLower)
            recentlyPlayedDao.deleteByAccount(emailLower)
            if (songIds.isNotEmpty()) {
                database.lyricsDao().deleteLyricsBySongIds(songIds)
            }

            val albumArtDir = File(context.filesDir, "album_art")
            val cloudCacheDir = File(context.cacheDir, "cloud_cache")
            songIds.forEach { id ->
                File(albumArtDir, "$id.jpg").delete()
                cloudCacheDir.listFiles { _, name -> name.startsWith("$id.") }?.forEach { it.delete() }
            }
            onSongsRemoved(songIds)
        }
    }
}
