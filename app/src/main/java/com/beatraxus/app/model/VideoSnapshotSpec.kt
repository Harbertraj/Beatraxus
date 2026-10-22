package com.beatraxus.app.model

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Video feature 16: screenshot / snapshot saved to the gallery.
 *
 * Pure rules (picture size and file name) with no Android types, so they can be unit tested.
 * The capture itself is in `util/VideoSnapshot`.
 */
object VideoSnapshotSpec {
    /** Gallery album the pictures are saved in (Pictures/Beatraxus). */
    const val ALBUM = "Beatraxus"

    /** Longest side of a snapshot, so a 8K video cannot ask for a bitmap the phone cannot hold. */
    const val MAX_SIDE = 4096

    /**
     * Picture size of a snapshot. It keeps the proportions the viewer sees on screen (aspect-ratio mode included)
     * and is scaled up to the video's real height, so a 1080p video on a 720p-tall player still gives a sharp
     * 1080-line picture. Never smaller than the on-screen view.
     */
    fun targetSize(viewWidth: Int, viewHeight: Int, videoHeight: Int): Pair<Int, Int> {
        if (viewWidth <= 0 || viewHeight <= 0) return 1 to 1
        var scale = if (videoHeight > viewHeight) videoHeight.toDouble() / viewHeight else 1.0
        val longest = max(viewWidth, viewHeight) * scale
        if (longest > MAX_SIDE) scale *= MAX_SIDE / longest
        scale = max(scale, 0.01)
        return max(1, (viewWidth * scale).roundToInt()) to max(1, (viewHeight * scale).roundToInt())
    }

    /** 3725000 ms -> "01-02-05". Hours always shown so names sort. File names cannot hold ":". */
    fun formatPosition(positionMs: Long): String {
        val total = max(0L, positionMs) / 1000
        return String.format(Locale.US, "%02d-%02d-%02d", total / 3600, (total / 60) % 60, total % 60)
    }

    /** Safe file name part from a video title: letters, digits, "." "_" "-" only, at most 60 characters. */
    fun safeTitle(title: String?): String {
        val cleaned = (title ?: "")
            .replace(Regex("\\.[A-Za-z0-9]{1,5}$"), "") // drop a file extension (".mkv"), not "Mr. Robot"
            .map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }
            .joinToString("")
            .replace(Regex("_+"), "_")
            .trim('_')
        return cleaned.take(60).trim('_').ifEmpty { "video" }
    }

    /** e.g. "My_Movie_01-02-05_20261023-101500.jpg". */
    fun fileName(title: String?, positionMs: Long, nowMs: Long): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(nowMs))
        return "${safeTitle(title)}_${formatPosition(positionMs)}_$stamp.jpg"
    }
}
