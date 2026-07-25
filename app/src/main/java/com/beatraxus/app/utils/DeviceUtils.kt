package com.beatraxus.app.utils

import android.os.Build

object DeviceUtils {
    /**
     * Identifies if the current device is a "Classic" (low-end) device,
     * specifically targeting the Redmi 9A as requested.
     */
    fun isClassicDevice(): Boolean {
        val model = Build.MODEL.uppercase()
        val manufacturer = Build.MANUFACTURER.uppercase()
        
        // Common Redmi 9A model numbers: M2006C3LG, M2006C3LI, M2006C3LC, M2006C3LV
        return model.contains("M2006C3L") || 
               model.contains("REDMI 9A") || 
               (manufacturer.contains("XIAOMI") && model.contains("9A"))
    }

    /**
     * Identifies if the current device is a "Modern" (high-end) device,
     * specifically targeting the Oppo Reno 10 as requested.
     */
    fun isModernDevice(): Boolean {
        val model = Build.MODEL.uppercase()
        // Common Oppo Reno 10 model numbers: CPH2531, PHW110
        return model.contains("CPH2531") || model.contains("PHW110") || model.contains("RENO 10")
    }
}
