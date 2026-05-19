package com.beatflowy.app.drive

import android.content.Intent
import kotlinx.coroutines.flow.MutableSharedFlow

object DrivePlaybackHelper {

    val authRecoveryFlow = MutableSharedFlow<Intent>(extraBufferCapacity = 1)
    val errorState = MutableSharedFlow<String>(extraBufferCapacity = 1)

}
