package com.beatraxus.app.engine

import android.content.Context
import com.beatraxus.app.model.StemQuality

/** Picks the separation engine for a quality level. */
object StemEngines {
    fun create(context: Context, quality: StemQuality): StemEngine = when (quality) {
        StemQuality.FAST -> StemSeparator(context)
        StemQuality.MAX -> HtDemucsSeparator(context)
    }

    fun isModelInstalled(context: Context?, quality: StemQuality): Boolean = when (quality) {
        StemQuality.FAST -> StemSeparator.isModelInstalled(context)
        StemQuality.MAX -> HtDemucsSeparator.isModelInstalled(context)
    }
}
