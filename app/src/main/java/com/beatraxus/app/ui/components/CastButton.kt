package com.beatraxus.app.ui.components

import android.content.Context
import android.content.ContextWrapper
import android.content.res.ColorStateList
import android.graphics.PorterDuff
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentActivity
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.framework.CastButtonFactory

/**
 * @param tint Color applied to the cast glyph itself. Callers drive this from
 * [com.beatraxus.app.cast.CastManager] state: black by default, white once a cast
 * device is detected (available and/or connected) — no background shape is drawn here,
 * that's left entirely to the caller's modifier.
 */
@Composable
fun CastButton(
    modifier: Modifier = Modifier,
    tint: Color = Color.Black
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            MediaRouteButton(context).apply {
                CastButtonFactory.setUpMediaRouteButton(context, this)
            }
        },
        update = { button ->
            // TODO: Find a way to tint the button programmatically in this version of MediaRouter
            // button.setRemoteIndicatorDrawableTintList(ColorStateList.valueOf(tint.toArgb()))
        }
    )
}
