package com.beatraxus.app.repository

import android.content.Context
import android.net.Uri
import com.beatraxus.app.repository.lastfm.LastFmRepository
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

class BackupRepository(
    private val context: Context,
    private val dspPreferences: DspPreferences,
    private val lastFmRepository: LastFmRepository,
    private val telegramChannelRepository: TelegramChannelRepository,
    private val driveAccountRepository: DriveAccountRepository
) {
    private val gson = Gson()

    /** What an import hands back to the caller for data that does not live in a repository here. */
    data class ImportedExtras(val customEqPresetsJson: String?)

    /**
     * Writes all settings to [uri], encrypted with [passphrase].
     *
     * The backup contains Last.fm, Telegram and cloud-drive account data, so it is never written
     * as plain JSON. [customEqPresetsJson] is the raw saved-EQ-presets JSON (kept in
     * SharedPreferences by the view model); device EQ assignments refer to those presets by name,
     * so they travel together.
     */
    suspend fun exportSettings(uri: Uri, passphrase: CharArray, customEqPresetsJson: String?) {
        withContext(Dispatchers.IO) {
            val backupData = mutableMapOf<String, Any>(
                "dsp" to dspPreferences.exportPreferences(),
                "lastfm" to lastFmRepository.exportPreferences(),
                "telegram" to telegramChannelRepository.exportPreferences(),
                "drive" to driveAccountRepository.exportPreferences(),
                "version" to 2
            )
            if (!customEqPresetsJson.isNullOrBlank()) backupData["eqPresets"] = customEqPresetsJson

            val plain = gson.toJson(backupData).toByteArray(Charsets.UTF_8)
            val encrypted = try {
                BackupCrypto.encrypt(plain, passphrase)
            } finally {
                plain.fill(0)
            }

            val stream = context.contentResolver.openOutputStream(uri)
                ?: throw IOException("Could not open the backup file for writing")
            stream.use { it.write(encrypted) }
        }
    }

    /** True when the file at [uri] is an encrypted backup, i.e. a passphrase is needed to import it. */
    suspend fun isEncrypted(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Could not open the backup file")
        stream.use {
            val head = ByteArray(16)
            val read = it.read(head)
            read > 0 && BackupCrypto.isEncrypted(head.copyOf(read))
        }
    }

    /**
     * Restores settings from [uri]. Older plain-JSON backups are still accepted; encrypted ones
     * need [passphrase]. The file is fully decrypted and parsed before any setting is changed, so
     * a wrong passphrase or a damaged file leaves the app untouched.
     *
     * @throws BackupCrypto.WrongPassphraseException wrong passphrase, or the file was modified
     * @throws BackupCrypto.InvalidBackupException the file is not a usable backup
     */
    suspend fun importSettings(uri: Uri, passphrase: CharArray?): ImportedExtras {
        return withContext(Dispatchers.IO) {
            val raw = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IOException("Could not open the backup file")

            val json: String = if (BackupCrypto.isEncrypted(raw)) {
                if (passphrase == null) throw BackupCrypto.WrongPassphraseException()
                val plain = BackupCrypto.decrypt(raw, passphrase)
                try {
                    String(plain, Charsets.UTF_8)
                } finally {
                    plain.fill(0)
                }
            } else {
                String(raw, Charsets.UTF_8)
            }

            val type = object : TypeToken<Map<String, Any>>() {}.type
            val parsed: Map<String, Any>? = try {
                gson.fromJson<Map<String, Any>>(json, type)
            } catch (e: Exception) {
                null
            }
            val backupData: Map<String, Any> = parsed
                ?: throw BackupCrypto.InvalidBackupException("Not a Beatraxus backup file")
            if (backupData.keys.none { it in KNOWN_SECTIONS }) {
                throw BackupCrypto.InvalidBackupException("Not a Beatraxus backup file")
            }

            @Suppress("UNCHECKED_CAST")
            (backupData["dsp"] as? Map<String, Any>)?.let { dspPreferences.importPreferences(it) }
            @Suppress("UNCHECKED_CAST")
            (backupData["lastfm"] as? Map<String, Any>)?.let { lastFmRepository.importPreferences(it) }
            @Suppress("UNCHECKED_CAST")
            (backupData["telegram"] as? Map<String, Any>)?.let { telegramChannelRepository.importPreferences(it) }
            @Suppress("UNCHECKED_CAST")
            (backupData["drive"] as? Map<String, Any>)?.let { driveAccountRepository.importPreferences(it) }

            ImportedExtras(customEqPresetsJson = backupData["eqPresets"] as? String)
        }
    }

    private companion object {
        val KNOWN_SECTIONS = setOf("dsp", "lastfm", "telegram", "drive")
    }
}
