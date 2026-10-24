package com.beatraxus.app.model

/**
 * Finds the folders where a phone keeps call recordings, so a full scan can keep them out of the music library.
 *
 * Phones and recorder apps name these folders differently ("Recordings/Call" on Samsung, "MIUI/sound_recorder/call_rec"
 * on Xiaomi, "Sounds/CallRecord" on Huawei, "ACRCalls" for ACR ...), so a folder is recognised by its name, not by one
 * fixed path. The match is deliberately narrow (a plain "Call" folder only counts in a recordings folder or at the top
 * of the storage) so a music folder is not hidden by mistake. An excluded folder is always listed in Settings, and
 * adding it back there stops the automatic exclusion for good ([foldersToExclude] leaves it alone).
 *
 * Free of Android types so it can be unit tested.
 */
object CallRecordingFolders {

    /** Folders such a plain "Call" folder may sit in (Samsung keeps calls in "Recordings/Call"). */
    private val RECORDING_PARENTS = setOf(
        "recordings", "recording", "record", "records", "recorder", "voicerecorder", "voicerecorders",
        "soundrecorder", "soundrecorders", "sounds", "miui"
    )

    private val STORAGE_ROOT = Regex("^/(sdcard|storage/self/primary|storage/emulated/\\d+|storage/[0-9a-z]{4}-[0-9a-z]{4})$")

    /**
     * The folder to exclude for [path] (a folder that holds songs): the outermost folder on the way down that is a
     * call recording folder, so a recording kept in a sub folder ("Call/2024") is covered too. Null if none is.
     */
    fun callRecordingRoot(path: String): String? {
        val segments = path.trim().split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return null
        for (i in segments.indices) {
            val parentPath = "/" + segments.take(i).joinToString("/")
            val parentName = if (i > 0) segments[i - 1] else ""
            if (isCallFolderName(segments[i], parentName, parentPath)) {
                return "/" + segments.take(i + 1).joinToString("/")
            }
        }
        return null
    }

    fun isCallRecordingFolder(path: String): Boolean = callRecordingRoot(path) != null

    private fun isCallFolderName(name: String, parentName: String, parentPath: String): Boolean {
        val key = name.lowercase().filter { it.isLetterOrDigit() }
        if (key.isEmpty()) return false
        if ("callrec" in key || "recordedcall" in key || "callsrecord" in key || "phonecallrecord" in key) return true
        if (key == "acrcalls" || key == "acrphone") return true
        if (key == "call" || key == "calls" || key == "phonecalls" || key == "voicecalls") {
            val parentKey = parentName.lowercase().filter { it.isLetterOrDigit() }
            return parentKey in RECORDING_PARENTS || STORAGE_ROOT.matches(parentPath.lowercase().trimEnd('/'))
        }
        return false
    }

    /** True when [path] is [root] or lies inside it. Ignores case and a trailing slash. */
    fun isInside(path: String, root: String): Boolean {
        val p = path.trim().trimEnd('/').lowercase()
        val r = root.trim().trimEnd('/').lowercase()
        if (r.isEmpty()) return false
        return p == r || p.startsWith("$r/")
    }

    /**
     * Which of the folders that hold songs should be excluded automatically.
     *  - [userAllowed]: call recording folders the listener added back. Anything that touches one of them (the folder
     *    itself, a folder inside it or one that contains it) is left alone, so a folder the listener wants never goes
     *    back to the excluded list.
     *  - [alreadyExcluded]: nothing is reported twice.
     */
    fun foldersToExclude(
        songFolders: Collection<String>,
        userAllowed: Collection<String>,
        alreadyExcluded: Collection<String>
    ): List<String> {
        val result = LinkedHashMap<String, String>() // lower case key -> path, one entry per folder
        for (folder in songFolders) {
            val root = callRecordingRoot(folder) ?: continue
            val touchesAllowed = userAllowed.any { isInside(root, it) || isInside(it, root) }
            if (touchesAllowed) continue
            if (alreadyExcluded.any { isInside(root, it) }) continue
            result.putIfAbsent(root.lowercase(), root)
        }
        return result.values.toList()
    }
}
