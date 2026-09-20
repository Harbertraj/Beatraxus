package com.beatraxus.app.addons

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

object AddonInstaller {
    private const val MAX_FILE_SIZE = 10 * 1024 * 1024 // 10MB
    private const val MAX_ENTRIES = 100

    fun installAddon(context: Context, zipUri: Uri): Result<AddonPackage> {
        return try {
            val addonsDir = File(context.filesDir, "addons")
            if (!addonsDir.exists()) addonsDir.mkdirs()

            val tempDir = File(addonsDir, "temp_${System.currentTimeMillis()}")
            tempDir.mkdirs()

            context.contentResolver.openInputStream(zipUri)?.use { inputStream ->
                ZipInputStream(inputStream).use { zis ->
                    var entry = zis.nextEntry
                    var entryCount = 0
                    while (entry != null) {
                        if (entryCount++ > MAX_ENTRIES) throw Exception("Too many entries in zip")
                        
                        val name = entry.name
                        if (name.contains("..")) throw Exception("Zip slip vulnerability detected")
                        
                        val targetFile = File(tempDir, name)
                        if (!targetFile.canonicalPath.startsWith(tempDir.canonicalPath)) {
                            throw Exception("Zip slip vulnerability detected (canonical)")
                        }

                        if (entry.isDirectory) {
                            targetFile.mkdirs()
                        } else {
                            targetFile.parentFile?.mkdirs()
                            val fos = FileOutputStream(targetFile)
                            val buffer = ByteArray(8192)
                            var len = zis.read(buffer)
                            var totalSize = 0L
                            while (len > 0) {
                                totalSize += len
                                if (totalSize > MAX_FILE_SIZE) throw Exception("File too large")
                                fos.write(buffer, 0, len)
                                len = zis.read(buffer)
                            }
                            fos.close()
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }

            val manifestFile = File(tempDir, "manifest.json")
            if (!manifestFile.exists()) throw Exception("manifest.json not found")

            val json = JSONObject(manifestFile.readText())
            val id = json.getString("id")
            val manifest = AddonManifest(
                id = id,
                displayName = json.getString("displayName"),
                mediaType = json.getString("mediaType"),
                protocol = json.getString("protocol"),
                authType = json.getString("authType"),
                iconFile = json.optString("iconFile", null)
            )

            val validProtocols = listOf("jellyfin", "plex", "emby", "subsonic", "archive_org")
            if (manifest.protocol !in validProtocols) {
                throw Exception("Unsupported protocol: ${manifest.protocol}")
            }

            val finalDir = File(addonsDir, id)
            if (finalDir.exists()) finalDir.deleteRecursively()
            tempDir.renameTo(finalDir)

            Result.success(AddonPackage(manifest, finalDir))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    fun uninstallAddon(context: Context, id: String) {
        val addonsDir = File(context.filesDir, "addons")
        val targetDir = File(addonsDir, id)
        if (targetDir.exists()) {
            targetDir.deleteRecursively()
        }
    }
}
