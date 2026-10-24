package com.beatraxus.app.model

/** The kind of high dynamic range a video uses. */
enum class HdrKind { NONE, HDR10, HLG, DOLBY_VISION }

/**
 * What the video file says about its HDR format. Pure maths on the stream facts, so it can be unit tested.
 *
 * [dolbyVisionProfile] is the Dolby Vision profile number (5, 7, 8, ...) when the codec string tells it.
 * HDR10+ is not listed here: its extra scene-by-scene data sits inside the stream and Android does not report it
 * to apps, so an HDR10+ file shows up as HDR10.
 */
data class HdrInfo(val kind: HdrKind, val dolbyVisionProfile: Int? = null) {
    val isHdr: Boolean get() = kind != HdrKind.NONE

    /** Short text for the badge in the player's top bar, null for SDR. */
    val badge: String?
        get() = when (kind) {
            HdrKind.NONE -> null
            HdrKind.HDR10 -> "HDR10"
            HdrKind.HLG -> "HLG"
            HdrKind.DOLBY_VISION -> "DV"
        }

    val label: String
        get() = when (kind) {
            HdrKind.NONE -> "SDR"
            HdrKind.HDR10 -> "HDR10"
            HdrKind.HLG -> "HLG"
            HdrKind.DOLBY_VISION -> dolbyVisionProfile?.let { "Dolby Vision (profile $it)" } ?: "Dolby Vision"
        }

    /** Profile 5 has no HDR10 layer to fall back on: without a Dolby Vision decoder the colours come out wrong. */
    val needsDolbyVisionDecoder: Boolean get() = kind == HdrKind.DOLBY_VISION && dolbyVisionProfile == 5

    /** One line about what the Dolby Vision profile means for playback, null when there is nothing to add. */
    val profileNote: String?
        get() = if (kind != HdrKind.DOLBY_VISION) null else when (dolbyVisionProfile) {
            5 -> "Profile 5 has no HDR10 layer, so it needs a Dolby Vision decoder"
            7 -> "Profile 7 is dual layer; its HDR10 base layer plays without a Dolby Vision decoder"
            8 -> "Profile 8 is single layer with an HDR10, SDR or HLG base"
            9 -> "Profile 9 is AVC based with an SDR base"
            10 -> "Profile 10 is AV1 based"
            else -> null
        }

    companion object {
        val NONE = HdrInfo(HdrKind.NONE)
    }
}

object HdrFormat {
    // Media3 constants, written out so this file needs no Android / Media3 classes.
    private const val TRANSFER_ST2084 = 6
    private const val TRANSFER_HLG = 7
    private const val DV_MIME = "video/dolby-vision"
    private val DV_CODEC_PREFIXES = listOf("dvhe.", "dvh1.", "dvav.", "dva1.", "dav1.")

    fun detect(mimeType: String?, codecs: String?, colorTransfer: Int, colorSpace: Int = -1): HdrInfo {
        val dvCodec = codecs?.split(',')?.map { it.trim() }
            ?.firstOrNull { token -> DV_CODEC_PREFIXES.any { token.startsWith(it, ignoreCase = true) } }
        if (mimeType.equals(DV_MIME, ignoreCase = true) || dvCodec != null) {
            return HdrInfo(HdrKind.DOLBY_VISION, dvCodec?.let(::dolbyVisionProfile))
        }
        return when (colorTransfer) {
            TRANSFER_ST2084 -> HdrInfo(HdrKind.HDR10)
            TRANSFER_HLG -> HdrInfo(HdrKind.HLG)
            else -> HdrInfo.NONE
        }
    }

    /** "dvhe.08.06" -> 8. Null when the string has no readable profile. */
    fun dolbyVisionProfile(codec: String): Int? =
        codec.split('.').getOrNull(1)?.trim()?.toIntOrNull()
}
