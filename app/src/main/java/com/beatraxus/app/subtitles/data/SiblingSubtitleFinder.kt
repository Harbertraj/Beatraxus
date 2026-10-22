package com.beatraxus.app.subtitles.data

import android.content.ContentUris
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.beatraxus.app.model.Video
import com.beatraxus.app.model.VideoSource
import com.beatraxus.app.subtitles.domain.SafTreePaths
import com.beatraxus.app.subtitles.domain.SiblingSubtitleCandidate
import com.beatraxus.app.subtitles.domain.SubtitleSiblingMatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** A subtitle found next to a video, and where to read it from. */
data class SiblingSubtitle(val candidate: SiblingSubtitleCandidate, val uri: Uri)

/**
 * Video feature 21: finds the subtitle files that sit in the same folder as a local video.
 *
 * Which way a folder can be read depends on the Android version, so three ways are tried in turn and the first one
 * that turns up a matching subtitle wins:
 *
 *  1. Plain file access. Works on Android 10 and below, or when the app has all-files access.
 *  2. MediaStore. Sees subtitle files the system has indexed (and any the app can already read).
 *  3. Folders the user granted through the system folder picker. On Android 11+ this is the dependable way, because
 *     a `.srt` is not a media file. The app already keeps such grants for the video library folders, so a video
 *     inside a library folder is covered without asking for anything new.
 *
 * Only local videos are looked at; streamed videos have no folder.
 */
class SiblingSubtitleFinder(private val context: Context) {

    /** Subtitles that belong to [video], best first. Empty when there are none or the folder cannot be read. */
    suspend fun find(video: Video, preferredLanguages: List<String>): List<SiblingSubtitle> = withContext(Dispatchers.IO) {
        if (video.source != VideoSource.LOCAL) return@withContext emptyList()
        val folder = SafTreePaths.normalizeStoragePath(video.folderPath)
        if (!folder.startsWith("/")) return@withContext emptyList()

        val tiers: List<() -> Map<String, Uri>> = listOf(
            { listWithFiles(folder) },
            { listWithMediaStore(folder) },
            { listWithGrantedFolders(folder) }
        )
        for (tier in tiers) {
            val files = try {
                tier()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyMap() // SecurityException, a revoked grant, a provider that is gone: just try the next way
            }
            if (files.isEmpty()) continue
            val ranked = SubtitleSiblingMatcher.rank(video.displayName, files.keys.toList(), preferredLanguages)
            if (ranked.isNotEmpty()) {
                return@withContext ranked.mapNotNull { c -> files[c.fileName]?.let { SiblingSubtitle(c, it) } }
            }
        }
        emptyList()
    }

    private fun listWithFiles(folder: String): Map<String, Uri> {
        val children = File(folder).listFiles() ?: return emptyMap()
        return children.asSequence()
            .filter { it.isFile && SubtitleSiblingMatcher.isSubtitleFileName(it.name) && it.canRead() }
            .associate { it.name to Uri.fromFile(it) }
    }

    @Suppress("DEPRECATION") // FileColumns.DATA is deprecated but still the only way to match by folder before Android 10
    private fun listWithMediaStore(folder: String): Map<String, Uri> {
        val collection = MediaStore.Files.getContentUri("external")
        val data = MediaStore.Files.FileColumns.DATA
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            data
        )
        val extensionTest = SubtitleSiblingMatcher.SUPPORTED_EXTENSIONS.joinToString(" OR ") { "$data LIKE ?" }
        val selection = "$data LIKE ? AND ($extensionTest)"
        val args = arrayOf("$folder/%") + SubtitleSiblingMatcher.SUPPORTED_EXTENSIONS.map { "%.$it" }

        val out = LinkedHashMap<String, Uri>()
        context.contentResolver.query(collection, projection, selection, args, null)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val dataCol = c.getColumnIndexOrThrow(data)
            while (c.moveToNext()) {
                val path = c.getString(dataCol) ?: continue
                val name = c.getString(nameCol) ?: continue
                // LIKE treats '%' and '_' in the folder name as wildcards and also reaches sub-folders, so check
                // that the file really is directly inside this folder.
                val parent = File(path).parent ?: continue
                if (SafTreePaths.normalizeStoragePath(parent) != folder) continue
                out.putIfAbsent(name, ContentUris.withAppendedId(collection, c.getLong(idCol)))
            }
        }
        return out
    }

    private fun listWithGrantedFolders(folder: String): Map<String, Uri> {
        val resolver = context.contentResolver
        val granted = resolver.persistedUriPermissions
            .filter { it.isReadPermission && DocumentsContract.isTreeUri(it.uri) }
            .mapNotNull { perm ->
                try {
                    DocumentsContract.getTreeDocumentId(perm.uri) to perm.uri
                } catch (_: Exception) {
                    null
                }
            }
        if (granted.isEmpty()) return emptyMap()

        val treeDocId = SafTreePaths.bestTree(folder, granted.map { it.first }) ?: return emptyMap()
        val treeUri = granted.first { it.first == treeDocId }.second
        val folderDocId = SafTreePaths.folderDocId(folder, treeDocId) ?: return emptyMap()

        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, folderDocId)
        val out = LinkedHashMap<String, Uri>()
        resolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val docId = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                if (!SubtitleSiblingMatcher.isSubtitleFileName(name)) continue
                out.putIfAbsent(name, DocumentsContract.buildDocumentUriUsingTree(treeUri, docId))
            }
        }
        return out
    }

    companion object {
        /** Settings -> Video Settings -> Subtitles. On by default. */
        const val KEY_AUTO_LOAD = "video_subtitle_auto_load_sibling"

        fun isAutoLoadEnabled(prefs: SharedPreferences): Boolean = prefs.getBoolean(KEY_AUTO_LOAD, true)
    }
}
