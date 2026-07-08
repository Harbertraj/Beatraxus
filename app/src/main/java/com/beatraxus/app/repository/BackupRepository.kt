package com.beatraxus.app.repository

import android.content.Context
import android.net.Uri
import com.beatraxus.app.repository.lastfm.LastFmRepository
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import java.io.InputStreamReader

class BackupRepository(
    private val context: Context,
    private val dspPreferences: DspPreferences,
    private val lastFmRepository: LastFmRepository,
    private val telegramChannelRepository: TelegramChannelRepository,
    private val driveAccountRepository: DriveAccountRepository
) {
    private val gson = Gson()

    suspend fun exportSettings(uri: Uri) {
        withContext(Dispatchers.IO) {
            val backupData = mapOf(
                "dsp" to dspPreferences.exportPreferences(),
                "lastfm" to lastFmRepository.exportPreferences(),
                "telegram" to telegramChannelRepository.exportPreferences(),
                "drive" to driveAccountRepository.exportPreferences(),
                "version" to 1
            )
            
            context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                OutputStreamWriter(outputStream).use { writer ->
                    gson.toJson(backupData, writer)
                }
            }
        }
    }

    suspend fun importSettings(uri: Uri) {
        withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                InputStreamReader(inputStream).use { reader ->
                    val type = object : TypeToken<Map<String, Any>>() {}.type
                    val backupData: Map<String, Any> = gson.fromJson(reader, type)
                    
                    (backupData["dsp"] as? Map<String, Any>)?.let { dspPreferences.importPreferences(it) }
                    (backupData["lastfm"] as? Map<String, Any>)?.let { lastFmRepository.importPreferences(it) }
                    (backupData["telegram"] as? Map<String, Any>)?.let { telegramChannelRepository.importPreferences(it) }
                    (backupData["drive"] as? Map<String, Any>)?.let { driveAccountRepository.importPreferences(it) }
                }
            }
        }
    }
}
