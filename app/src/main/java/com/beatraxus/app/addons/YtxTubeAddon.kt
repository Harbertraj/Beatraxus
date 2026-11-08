package com.beatraxus.app.addons

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * YTXTube add-on (https://github.com/Koustubh12345/YTXTube, GPL-3.0).
 *
 * This is a launcher: Beatraxus contains none of YTXTube's code. Tapping the card opens the separately installed
 * YTXTube app, so you get that app exactly as it is (its own player, Shorts, lyrics, downloads and settings).
 * A YouTube link handed to [play] is opened straight in YTXTube. If the app is not installed, the card says so and
 * the button opens the download page.
 */
class YtxTubeAddon(
    override val id: String,
    override val displayName: String,
    @Suppress("unused") private val authType: String
) : MusicServiceAddon {

    override val description = "Ad-free YouTube & YouTube Music — opens the YTXTube app"
    override val brandColor = Color(0xFFE50914)
    override val icon: ImageVector = Icons.Default.PlayCircle
    override val capability = AddonCapability.LAUNCH

    private val _state = MutableStateFlow(AddonConnectionState.NOT_CONNECTED)
    override val connectionState: StateFlow<AddonConnectionState> = _state.asStateFlow()

    private fun isInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(YtxTubeLinks.PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private fun refresh(context: Context): Boolean {
        val installed = isInstalled(context)
        _state.value = if (installed) AddonConnectionState.CONNECTED else AddonConnectionState.NOT_INSTALLED
        return installed
    }

    override fun connect(context: Context) {
        refresh(context.applicationContext)
    }

    override fun disconnect() {
        _state.value = AddonConnectionState.NOT_CONNECTED
    }

    override fun play(context: Context, query: String?) {
        // Checked again on every tap: the app may have been installed (or removed) since the card was last drawn.
        if (!refresh(context)) {
            open(context, Intent(Intent.ACTION_VIEW, Uri.parse(YtxTubeLinks.RELEASES_URL)))
            return
        }
        val link = YtxTubeLinks.videoLink(query)
        val intent = if (link != null) {
            Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(YtxTubeLinks.PACKAGE)
        } else {
            context.packageManager.getLaunchIntentForPackage(YtxTubeLinks.PACKAGE)
                ?: Intent(Intent.ACTION_MAIN).setClassName(YtxTubeLinks.PACKAGE, "${YtxTubeLinks.PACKAGE}.MainActivity")
        }
        if (!open(context, intent)) _state.value = AddonConnectionState.ERROR
    }

    override fun buttonLabel() =
        if (_state.value == AddonConnectionState.CONNECTED) "OPEN" else "GET APP"

    private fun open(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
