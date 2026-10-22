package com.beatraxus.app.subtitles.domain

/**
 * Video feature 21: maps between the plain folder paths MediaStore reports for a video and the document ids of the
 * folder trees the user granted through the system folder picker.
 *
 * On Android 11+ a `.srt` next to a video is not readable by path (it is not a media file), but it is readable
 * through a folder the user granted. The app already keeps such grants for the video library folders, so this lets
 * subtitle lookup reuse them. Ids of the external-storage provider look like `primary:Movies/Show`
 * (`<volume>:<path inside the volume>`).
 *
 * Pure Kotlin, no Android types, so it can be unit tested.
 */
object SafTreePaths {

    private const val PRIMARY_ROOT = "/storage/emulated/0"

    /** Rewrites the other names of the primary storage ("/sdcard", "/storage/self/primary", ...) to one form. */
    fun normalizeStoragePath(path: String): String {
        val p = path.trim().trimEnd('/')
        val prefixes = listOf("/storage/self/primary", "/mnt/sdcard", "/sdcard", "/storage/emulated/legacy")
        for (prefix in prefixes) {
            if (p == prefix || p.startsWith("$prefix/")) return PRIMARY_ROOT + p.removePrefix(prefix)
        }
        // "/storage/emulated/10/..." is another user profile's primary storage: keep it as it is.
        return p
    }

    /** Root path of a volume id: "primary" -> internal storage, "1A2B-3C4D" -> that SD card. Null for unusable ids. */
    fun volumeRoot(volumeId: String): String? = when {
        volumeId.isBlank() -> null
        volumeId.equals("primary", ignoreCase = true) -> PRIMARY_ROOT
        volumeId.equals("raw", ignoreCase = true) || volumeId.equals("home", ignoreCase = true) -> null
        volumeId.contains('/') -> null
        else -> "/storage/$volumeId"
    }

    /** Folder path a tree document id stands for, or null when it is not an external-storage id we understand. */
    fun docIdToPath(docId: String): String? {
        val colon = docId.indexOf(':')
        if (colon <= 0) return null
        val root = volumeRoot(docId.substring(0, colon)) ?: return null
        val rel = docId.substring(colon + 1).trim('/')
        return if (rel.isEmpty()) root else "$root/$rel"
    }

    /**
     * Document id of [videoFolder] when it lies inside the folder granted as [treeDocId] (or is that folder),
     * otherwise null. Example: tree `primary:Movies`, folder `/sdcard/Movies/Show` -> `primary:Movies/Show`.
     */
    fun folderDocId(videoFolder: String, treeDocId: String): String? {
        val colon = treeDocId.indexOf(':')
        if (colon <= 0) return null
        val volume = treeDocId.substring(0, colon)
        val root = volumeRoot(volume) ?: return null
        val grantPath = docIdToPath(treeDocId) ?: return null
        val folder = normalizeStoragePath(videoFolder)
        if (folder != grantPath && !folder.startsWith("$grantPath/")) return null
        val rel = folder.removePrefix(root).trim('/')
        return "$volume:$rel"
    }

    /** Of several granted trees, the one that covers [videoFolder] most closely (the deepest), or null if none does. */
    fun bestTree(videoFolder: String, treeDocIds: Collection<String>): String? =
        treeDocIds
            .filter { folderDocId(videoFolder, it) != null }
            .maxByOrNull { docIdToPath(it)?.length ?: -1 }
}
