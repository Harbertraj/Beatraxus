package com.beatraxus.app.model

/**
 * Video library sorting (video library feature 21): by name, date added, file size or duration, in either direction.
 *
 * The rules live here, free of Android types, so they can be unit tested:
 *  - Names are compared "naturally" and ignoring case, so "Episode 2" comes before "Episode 10".
 *  - A size, duration or date of 0 means "not known yet" (a cloud video that has not been enriched). Those items are
 *    always listed last, in both directions, instead of crowding the top of an ascending list.
 *  - Items that tie on the chosen key fall back to name order (always A to Z), so the list never shuffles on refresh.
 */
object VideoSort {

    fun sortVideos(videos: List<Video>, type: SortType, ascending: Boolean): List<Video> = sort(
        items = videos,
        type = type,
        ascending = ascending,
        name = { it.title },
        date = { it.dateAdded },
        size = { it.sizeBytes },
        duration = { it.durationMs }
    )

    /** A folder's date is its newest video; its size and duration are the totals of the videos inside. */
    fun sortFolders(folders: List<VideoFolder>, type: SortType, ascending: Boolean): List<VideoFolder> = sort(
        items = folders,
        type = type,
        ascending = ascending,
        name = { it.name },
        date = { it.latestDateAdded },
        size = { it.totalSizeBytes },
        duration = { it.totalDurationMs }
    )

    fun <T> sort(
        items: List<T>,
        type: SortType,
        ascending: Boolean,
        name: (T) -> String,
        date: (T) -> Long,
        size: (T) -> Long,
        duration: (T) -> Long
    ): List<T> {
        val byName = Comparator<T> { a, b -> naturalCompare(name(a), name(b)) }
        return when (type) {
            SortType.NAME -> items.sortedWith(if (ascending) byName else byName.reversed())
            SortType.DATE_ADDED -> sortByNumber(items, ascending, date, byName)
            SortType.FILE_SIZE -> sortByNumber(items, ascending, size, byName)
            SortType.DURATION -> sortByNumber(items, ascending, duration, byName)
        }
    }

    private fun <T> sortByNumber(
        items: List<T>,
        ascending: Boolean,
        key: (T) -> Long,
        byName: Comparator<T>
    ): List<T> {
        val (known, unknown) = items.partition { key(it) > 0L }
        val byKey = Comparator<T> { a, b ->
            val c = key(a).compareTo(key(b))
            val primary = if (ascending) c else -c
            if (primary != 0) primary else byName.compare(a, b)
        }
        return known.sortedWith(byKey) + unknown.sortedWith(byName)
    }

    /**
     * Case-insensitive comparison that reads runs of digits as numbers ("file2" < "file10").
     * Digit runs are compared by length and then text, so a very long number cannot overflow.
     */
    fun naturalCompare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isAsciiDigit() && cb.isAsciiDigit()) {
                var ie = i
                while (ie < a.length && a[ie].isAsciiDigit()) ie++
                var je = j
                while (je < b.length && b[je].isAsciiDigit()) je++
                val na = a.substring(i, ie).trimStart('0')
                val nb = b.substring(j, je).trimStart('0')
                if (na.length != nb.length) return na.length.compareTo(nb.length)
                val c = na.compareTo(nb)
                if (c != 0) return c
                i = ie
                j = je
            } else {
                val la = ca.lowercaseChar()
                val lb = cb.lowercaseChar()
                if (la != lb) return la.compareTo(lb)
                i++
                j++
            }
        }
        return (a.length - i).compareTo(b.length - j)
    }

    private fun Char.isAsciiDigit() = this in '0'..'9'
}
