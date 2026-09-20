package com.beatraxus.app.utils

import kotlinx.coroutines.sync.Mutex

object VideoBackgroundWork {
    val mutex = Mutex()
}
