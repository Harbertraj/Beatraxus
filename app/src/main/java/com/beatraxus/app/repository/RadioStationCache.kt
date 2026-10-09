package com.beatraxus.app.repository

import com.beatraxus.app.model.RadioStation

/**
 * Remembers the station list the radio library last showed, so the radio Now Playing screen can
 * step to the previous/next station even when the library screen is no longer on screen.
 */
object RadioStationCache {
    @Volatile
    var stations: List<RadioStation> = emptyList()

    /** Station adjacent to [currentSongId] (a "radio_<id>" song id); [step] is -1 or +1. Wraps around. */
    fun neighbour(currentSongId: String, step: Int): RadioStation? {
        val list = stations
        if (list.size < 2) return null
        val index = list.indexOfFirst { "radio_${it.id}" == currentSongId }
        if (index < 0) return null
        return list[(index + step + list.size) % list.size]
    }

    fun contains(currentSongId: String): Boolean =
        stations.size >= 2 && stations.any { "radio_${it.id}" == currentSongId }
}
