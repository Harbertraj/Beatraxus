package com.beatraxus.app.addons

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.zip.ZipInputStream

object AddonInstaller {
    private const val MAX_FILE_SIZE = 10L * 1024 * 1024 // 10 MiB per file
    private const val MAX_TOTAL_SIZE = 30L * 1024 * 1024 // 30 MiB expanded package size
    private const val MAX_ENTRIES = 100
    private const val MAX_MANIFEST_SIZE = 64 * 1024
    private val VALID_PROTOCOLS = setOf(
        "jellyfin", "plex", "emby", "subsonic", "archive_org", "stremio", "jamendo", "ytxtube"
    )
    private val VALID_ID = Regex("[A-Za-z0-9_]{1,64}")

    fun installAddon(context: Context, zipUri: Uri): Result<AddonPackage> = runCatching {
        val addonsDir = File(context.filesDir, "addons")
        if (!addonsDir.exists() && !addonsDir.mkdirs()) throw IOException("Could not create add-ons directory")

        val tempDir = File(addonsDir, ".install_${UUID.randomUUID()}")
        if (!tempDir.mkdirs()) throw IOException("Could not create temporary install directory")

        try {
            val input = context.contentResolver.openInputStream(zipUri)
                ?: throw IOException("Could not open add-on package")
            input.use { source ->
                ZipInputStream(source).use { zip ->
                    val seenEntries = HashSet<String>()
                    var entryCount = 0
                    var expandedBytes = 0L
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        entryCount++
                        if (entryCount > MAX_ENTRIES) throw IOException("Too many entries in ZIP")

                        val normalizedName = entry.name.replace('\\', '/')
                        val segments = normalizedName.split('/')
                        if (normalizedName.startsWith('/') || segments.any { it == ".." || it == "." } ||
                            normalizedName.isBlank() || !seenEntries.add(normalizedName)
                        ) {
                            throw IOException("Invalid or duplicate ZIP entry")
                        }

                        val target = File(tempDir, normalizedName)
                        val rootPath = tempDir.canonicalPath + File.separator
                        val targetPath = target.canonicalPath
                        if (targetPath != tempDir.canonicalPath && !targetPath.startsWith(rootPath)) {
                            throw IOException("ZIP path escapes the add-ons directory")
                        }

                        if (entry.isDirectory) {
                            if (!target.exists() && !target.mkdirs()) throw IOException("Could not create package directory")
                        } else {
                            target.parentFile?.let { parent ->
                                if (!parent.exists() && !parent.mkdirs()) throw IOException("Could not create package directory")
                            }
                            FileOutputStream(target).use { output ->
                                val buffer = ByteArray(8 * 1024)
                                var fileBytes = 0L
                                while (true) {
                                    val count = zip.read(buffer)
                                    if (count < 0) break
                                    fileBytes += count
                                    expandedBytes += count
                                    if (fileBytes > MAX_FILE_SIZE) throw IOException("ZIP entry exceeds the 10 MiB limit")
                                    if (expandedBytes > MAX_TOTAL_SIZE) throw IOException("Expanded add-on exceeds the 30 MiB limit")
                                    output.write(buffer, 0, count)
                                }
                            }
                        }
                        zip.closeEntry()
                    }
                }
            }

            val manifestFile = File(tempDir, "manifest.json")
            if (!manifestFile.isFile || manifestFile.length() !in 1..MAX_MANIFEST_SIZE.toLong()) {
                throw IOException("manifest.json is missing or too large")
            }
            val json = JSONObject(manifestFile.readText(Charsets.UTF_8))
            val id = json.getString("id")
            val manifest = AddonManifest(
                id = id,
                displayName = json.getString("displayName").trim(),
                mediaType = json.getString("mediaType"),
                protocol = json.getString("protocol"),
                authType = json.getString("authType"),
                iconFile = json.optString("iconFile").takeIf { it.isNotBlank() }
            )
            if (!VALID_ID.matches(id)) throw IOException("Invalid add-on ID")
            if (manifest.displayName.isBlank() || manifest.displayName.length > 80) throw IOException("Invalid add-on display name")
            if (manifest.protocol !in VALID_PROTOCOLS) throw IOException("Unsupported protocol: ${manifest.protocol}")
            if (manifest.mediaType !in setOf("audio", "video", "mixed")) throw IOException("Unsupported media type")
            if (manifest.authType.length > 64) throw IOException("Invalid authentication type")

            manifest.iconFile?.let { icon ->
                val iconFile = File(tempDir, icon)
                val rootPath = tempDir.canonicalPath + File.separator
                require(iconFile.canonicalPath.startsWith(rootPath) && iconFile.isFile) { "Invalid add-on icon path" }
            }

            val finalDir = File(addonsDir, id)
            val backupDir = File(addonsDir, ".backup_${UUID.randomUUID()}")
            val hadPrevious = finalDir.exists()
            if (hadPrevious && !finalDir.renameTo(backupDir)) throw IOException("Could not stage the previous add-on")
            if (!tempDir.renameTo(finalDir)) {
                if (hadPrevious) backupDir.renameTo(finalDir)
                throw IOException("Could not move the validated add-on into place")
            }
            if (hadPrevious) backupDir.deleteRecursively()
            AddonPackage(manifest, finalDir)
        } catch (error: Exception) {
            tempDir.deleteRecursively()
            throw error
        }
    }

    fun uninstallAddon(context: Context, id: String) {
        require(VALID_ID.matches(id)) { "Invalid add-on ID" }
        val addonsDir = File(context.filesDir, "addons")
        val targetDir = File(addonsDir, id)
        if (targetDir.exists()) targetDir.deleteRecursively()
    }
}
