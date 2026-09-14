package com.beatraxus.app.repository

import android.content.Context
import android.content.Intent
import android.net.Uri

enum class StreamingServiceType(val packageName: String) {
    YOUTUBE_MUSIC("com.google.android.apps.youtube.music"),
    APPLE_MUSIC("com.apple.android.music"),
    AMAZON_MUSIC("com.amazon.mp3")
}

class StreamingLinkResolver {

    fun openInExternalApp(context: Context, service: StreamingServiceType, query: String? = null) {
        val packageManager = context.packageManager
        val launchIntent = packageManager.getLaunchIntentForPackage(service.packageName)

        if (launchIntent != null) {
            // App is installed
            val intent = if (!query.isNullOrBlank()) {
                getSearchIntent(service, query)
            } else {
                launchIntent
            }
            context.startActivity(intent)
        } else {
            // App not installed, open Play Store
            openPlayStore(context, service.packageName)
        }
    }

    private fun getSearchIntent(service: StreamingServiceType, query: String): Intent {
        val uri = when (service) {
            StreamingServiceType.YOUTUBE_MUSIC -> 
                Uri.parse("https://music.youtube.com/search?q=$query")
            StreamingServiceType.APPLE_MUSIC -> 
                Uri.parse("https://music.apple.com/search?term=$query")
            StreamingServiceType.AMAZON_MUSIC -> 
                Uri.parse("amznmp3://search/$query")
        }
        
        return Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage(service.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun openPlayStore(context: Context, packageName: String) {
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (e: Exception) {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageName")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }
    }
}
