package com.beatflowy.app.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.beatflowy.app.model.NetworkType

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
}
