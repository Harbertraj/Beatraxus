package com.beatraxus.app.addons

/**
 * Optional capabilities an add-on can offer on top of [MediaServerAddon]. Add-ons that don't implement
 * them behave exactly as before (one list, no chips, no "load more"). Kept free of Android classes so the
 * logic around them can be unit-tested on a plain JVM.
 */

/** One chip inside an [AddonFilterGroup]. */
data class AddonFilterOption(val key: String, val label: String)

/** A row of mutually exclusive chips on the browse screen (media type, sort order, quality, …). */
data class AddonFilterGroup(
    val id: String,
    /** Small caption shown before the chips, e.g. "SORT". */
    val label: String,
    val options: List<AddonFilterOption>,
    val defaultKey: String
)

/** One page of results; [hasMore] tells the browse screen whether to ask for the next page. */
data class AddonPage(val items: List<AddonMediaItem>, val hasMore: Boolean)

/** Add-ons whose results can be narrowed or re-sorted with chips. */
interface FilterableAddon {
    val filterGroups: List<AddonFilterGroup>

    /** Called before each (re)load with the currently selected chip of a group. Unknown keys must be ignored. */
    fun setFilter(groupId: String, optionKey: String)
}

/** Add-ons that can return their results page by page ([page] is 0-based). */
interface PagedAddon {
    suspend fun page(query: String?, page: Int): AddonPage
}

/** Add-on traffic is HTTPS-only; some provider docs and samples still hand out http:// links, so upgrade them. */
internal fun httpsOnly(url: String): String =
    if (url.startsWith("http://", ignoreCase = true)) "https://" + url.substring(7) else url
