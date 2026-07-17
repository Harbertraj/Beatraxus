package com.beatraxus.app.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.beatraxus.app.model.NetworkType

object NetworkUtils {
    fun isWifiConnected(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities = cm.getNetworkCapabilities(cm.activeNetwork)
        return capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }

    fun isMobileConnected(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities = cm.getNetworkCapabilities(cm.activeNetwork)
        return capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
    }

    fun isNetworkAllowed(context: Context, allowedType: NetworkType): Boolean {
        return when (allowedType) {
            NetworkType.WIFI_ONLY -> isWifiConnected(context)
            NetworkType.WIFI_MOBILE -> isWifiConnected(context) || isMobileConnected(context)
            NetworkType.MOBILE_ONLY -> isMobileConnected(context)
            NetworkType.ASK_MOBILE -> isWifiConnected(context) // For now, assume ASK_MOBILE means "block mobile until asked"
        }
    }

    fun formatFileSize(size: Long): String {
        if (size <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
        return java.util.Locale.US.let { locale ->
            String.format(locale, "%.1f %s", size / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
        }
    }
}
