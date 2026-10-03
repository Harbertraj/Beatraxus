package com.beatraxus.app.ui.utils

import android.content.Context
import android.os.Build
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider

/**
 * Call from INSIDE a Compose `Dialog { ... }` content.
 *
 *  - Removes the default dark dim of the dialog window (no dark shade at all).
 *  - On Android 12+ blurs whatever is behind the dialog window (real "frosted" backdrop).
 *
 * Pass [radiusDp] = 0 to only remove the dim (use this when the screen behind the dialog
 * is already blurred in-app, e.g. NowPlayingScreen).
 */
@Composable
fun DialogBlurBehind(radiusDp: Int = 22) {
    val view = LocalView.current
    DisposableEffect(view, radiusDp) {
        val window = (view.parent as? DialogWindowProvider)?.window
        if (window != null) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window.setDimAmount(0f)
            if (radiusDp > 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                val lp = window.attributes
                lp.blurBehindRadius = (radiusDp * view.resources.displayMetrics.density).toInt()
                window.attributes = lp
            }
        }
        onDispose { }
    }
}

/**
 * True when the OS can blur behind a dialog window (Android 12+ and cross-window blur
 * enabled; some devices disable it in battery-saver / developer options).
 */
@Composable
fun rememberWindowBlurSupported(): Boolean {
    val context = LocalContext.current
    return remember {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
                ?.isCrossWindowBlurEnabled == true
    }
}
