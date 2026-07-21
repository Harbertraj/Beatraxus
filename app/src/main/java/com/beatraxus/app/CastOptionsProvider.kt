package com.beatraxus.app

import android.content.Context
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.media.CastMediaOptions
import com.google.android.gms.cast.framework.media.NotificationOptions
import com.beatraxus.app.cast.CustomExpandedControllerActivity

class CastOptionsProvider : OptionsProvider {

    companion object {
        // ---------------------------------------------------------------------------------
        // Why the TV shows "Default Media Receiver" instead of your app's name:
        // CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID points at Google's
        // shared, unbranded receiver. No amount of code changes here can rename it — Google
        // controls that receiver's UI, not your app.
        //
        // To show "Beatraxus" (and your icon) on the TV's connect/cast screen:
        //   1. Go to https://cast.google.com/publish and sign in (one-time $5 Cast SDK
        //      developer registration if you haven't already).
        //   2. Click "Add New Application" -> "Styled Media Receiver" (NOT "Custom Receiver" —
        //      styled receiver needs zero HTML/JS code, just your app name + a logo image URL).
        //   3. Fill in the app name ("Beatraxus"), background image, and your device's Wi-Fi
        //      testing info during development.
        //   4. Save it and copy the "Application ID" it gives you (a 4-8 char code).
        //   5. Paste that ID below, replacing YOUR_CAST_APP_ID.
        //   6. Also register any TV/Chromecast devices you test with as "test devices" in
        //      that same dashboard, or unregistered receivers will refuse to load your app.
        //
        // Until you do this, CAST_APP_ID intentionally falls back to the default receiver so
        // the app keeps working — it just can't be re-branded without that registration.
        // ---------------------------------------------------------------------------------
        const val YOUR_CAST_APP_ID = "YOUR_CAST_APP_ID" // <-- replace with your registered App ID
        val CAST_APP_ID: String =
            if (YOUR_CAST_APP_ID != "YOUR_CAST_APP_ID") YOUR_CAST_APP_ID
            else CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID
    }

    override fun getCastOptions(context: Context): CastOptions {
        val notificationOptions = NotificationOptions.Builder()
            .setTargetActivityClassName(CustomExpandedControllerActivity::class.java.name)
            .build()

        val mediaOptions = CastMediaOptions.Builder()
            .setNotificationOptions(notificationOptions)
            .setExpandedControllerActivityClassName(CustomExpandedControllerActivity::class.java.name)
            .build()

        return CastOptions.Builder()
            .setReceiverApplicationId(CAST_APP_ID)
            .setCastMediaOptions(mediaOptions)
            .build()
    }

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? {
        return emptyList()
    }
}
