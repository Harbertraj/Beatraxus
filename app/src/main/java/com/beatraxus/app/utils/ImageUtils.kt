package com.beatraxus.app.utils

import com.beatraxus.app.R
import java.time.LocalTime

object ImageUtils {
    fun getDefaultAlbumArtRes(): Int {
        val hour = LocalTime.now().hour
        return when (hour) {
            in 5..11 -> R.drawable.morning_albumart
            in 12..16 -> R.drawable.aftrtnoon_albumart
            in 17..20 -> R.drawable.evening_albumart
            else -> R.drawable.night_albumart
        }
    }
}
