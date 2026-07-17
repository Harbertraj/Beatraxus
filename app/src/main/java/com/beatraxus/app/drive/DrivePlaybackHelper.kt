package com.beatraxus.app.drive

import android.content.Intent
import kotlinx.coroutines.flow.MutableSharedFlow

object DrivePlaybackHelper {
    val authRecoveryFlow = MutableSharedFlow<Intent>(extraBufferCapacity = 1)
    val errorState = MutableSharedFlow<String>(extraBufferCapacity = 1)
}

object DropboxPlaybackHelper {
    val errorState = MutableSharedFlow<String>(extraBufferCapacity = 1)
}

object OneDrivePlaybackHelper {
    val errorState = MutableSharedFlow<String>(extraBufferCapacity = 1)
}

object BoxPlaybackHelper {
    val errorState = MutableSharedFlow<String>(extraBufferCapacity = 1)
}

object NextcloudPlaybackHelper {
    val errorState = MutableSharedFlow<String>(extraBufferCapacity = 1)
}
