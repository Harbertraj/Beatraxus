package com.beatflowy.app.drive

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

object DrivePlaybackHelper {

    fun buildDriveDataSourceFactory(credential: GoogleAccountCredential): DataSource.Factory {
        return OkHttpDataSource.Factory(
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    // We need a fresh token. credential.token might trigger a network call.
                    // Since this is called by ExoPlayer's loading thread, it's usually okay to block if needed,
                    // but we should ideally handle token refresh properly.
                    val token = runBlocking {
                        withContext(Dispatchers.IO) {
                            credential.token
                        }
                    }
                    val request = chain.request().newBuilder()
                        .addHeader("Authorization", "Bearer $token")
                        .build()
                    chain.proceed(request)
                }
                .build()
        )
    }
}
