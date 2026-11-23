package com.beatraxus.app.engine

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Asks the phone once what video it can decode and show (codecs, sizes, frame rates, 10-bit / HDR profiles,
 * Dolby Vision decoder, screen HDR types) and keeps the answer, so the player can tell before or while playing
 * whether a file is within the phone's limits ([VideoCompatibility.check]).
 *
 * Run from the app's start-up thread on first open. The saved answer belongs to one system build
 * ([Build.FINGERPRINT]), so it is read again after a system update.
 */
object VideoCapabilityProbe {
    private const val TAG = "VideoCapabilityProbe"
    private const val PREFS = "beatraxus"
    private const val KEY_CAPS = "video_device_caps_v1"
    private const val KEY_FINGERPRINT = "video_device_caps_fingerprint"

    private val MIMES = listOf(
        VideoCompatibility.HEVC,
        VideoCompatibility.AVC,
        VideoCompatibility.VP9,
        VideoCompatibility.AV1,
        VideoCompatibility.DOLBY_VISION
    )

    @Volatile
    private var cached: DeviceVideoCaps? = null

    /** The result already in memory, or null before [ensure] has run in this process. */
    fun cachedOrNull(): DeviceVideoCaps? = cached

    /** Loads the saved result, or probes the phone (and saves it) on first open and after a system update. */
    @Synchronized
    fun ensure(context: Context): DeviceVideoCaps {
        cached?.let { return it }
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_CAPS, null)
        if (saved != null && prefs.getString(KEY_FINGERPRINT, null) == Build.FINGERPRINT) {
            runCatching { fromJson(saved) }.getOrNull()?.let {
                cached = it
                return it
            }
        }
        val caps = probe(context.applicationContext)
        runCatching {
            prefs.edit().putString(KEY_CAPS, toJson(caps)).putString(KEY_FINGERPRINT, Build.FINGERPRINT).apply()
        }
        cached = caps
        Log.i(TAG, "Probed video capabilities: " + describe(caps))
        return caps
    }

    fun probe(context: Context): DeviceVideoCaps {
        val byMime = mutableMapOf<String, MutableList<ProbedDecoder>>()
        var dvDecoder = false
        try {
            for (info in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
                if (info.isEncoder || info.name.endsWith(".secure", ignoreCase = true)) continue
                for (type in info.supportedTypes) {
                    val mime = type.lowercase()
                    if (mime !in MIMES) continue
                    val decoder = describeDecoder(info, type) ?: continue
                    byMime.getOrPut(mime) { mutableListOf() }.add(decoder)
                    if (mime == VideoCompatibility.DOLBY_VISION) dvDecoder = true
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Could not list the video decoders", t)
        }
        return DeviceVideoCaps(byMime, displayHdrTypes(context), dvDecoder)
    }

    private fun describeDecoder(info: MediaCodecInfo, type: String): ProbedDecoder? = try {
        val caps = info.getCapabilitiesForType(type)
        val video = caps.videoCapabilities
        if (video == null) {
            null
        } else {
            ProbedDecoder(
                name = info.name,
                hardware = isHardware(info),
                maxWidth = video.supportedWidths.upper,
                maxHeight = video.supportedHeights.upper,
                maxFps1080 = fpsAt(video, 1920, 1080),
                maxFps2160 = fpsAt(video, 3840, 2160),
                maxBitrate = video.bitrateRange.upper,
                tenBit = caps.profileLevels.any { isTenBit(type, it.profile) }
            )
        }
    } catch (t: Throwable) {
        Log.w(TAG, "Could not read ${info.name} for $type", t)
        null
    }

    private fun isHardware(info: MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= 29) {
            info.isHardwareAccelerated && !info.isSoftwareOnly
        } else {
            val n = info.name.lowercase()
            !(n.startsWith("omx.google.") || n.startsWith("c2.android.") || n.startsWith("c2.google."))
        }

    private fun fpsAt(video: MediaCodecInfo.VideoCapabilities, width: Int, height: Int): Int = try {
        if (video.isSizeSupported(width, height)) video.getSupportedFrameRatesFor(width, height).upper.toInt() else 0
    } catch (_: Throwable) {
        0
    }

    // Profile numbers written out (MediaCodecInfo.CodecProfileLevel) so they work on every API level of the build.
    private fun isTenBit(mime: String, profile: Int): Boolean = when (mime.lowercase()) {
        VideoCompatibility.HEVC -> profile == 0x02 || profile == 0x1000 || profile == 0x2000 // Main10, Main10 HDR10, HDR10+
        VideoCompatibility.VP9 -> profile == 0x04 || profile == 0x1000 || profile == 0x2000   // Profile2, 2 HDR, 2 HDR10+
        VideoCompatibility.AV1 -> profile == 0x02 || profile == 0x1000 || profile == 0x2000   // Main10, Main10 HDR10, HDR10+
        VideoCompatibility.AVC -> profile == 0x10 || profile == 0x20 || profile == 0x40       // High10, High422, High444
        VideoCompatibility.DOLBY_VISION -> true
        else -> false
    }

    @Suppress("DEPRECATION")
    private fun displayHdrTypes(context: Context): Set<String> = try {
        val dm = context.getSystemService(android.hardware.display.DisplayManager::class.java)
        val types = dm?.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.hdrCapabilities?.supportedHdrTypes ?: IntArray(0)
        types.mapNotNull {
            when (it) {
                android.view.Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> "Dolby Vision"
                android.view.Display.HdrCapabilities.HDR_TYPE_HDR10 -> "HDR10"
                android.view.Display.HdrCapabilities.HDR_TYPE_HLG -> "HLG"
                android.view.Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> "HDR10+"
                else -> null
            }
        }.toSet()
    } catch (_: Throwable) {
        emptySet()
    }

    private fun describe(c: DeviceVideoCaps): String = c.decoders.entries.joinToString("; ") { (mime, list) ->
        "$mime=" + list.joinToString(",") {
            "${it.name}[${if (it.hardware) "hw" else "sw"} ${it.maxFps1080}fps@1080 ${it.maxFps2160}fps@2160${if (it.tenBit) " 10bit" else ""}]"
        }
    } + "; screenHdr=" + c.displayHdr + "; dvDecoder=" + c.dolbyVisionDecoder

    private fun toJson(c: DeviceVideoCaps): String {
        val root = JSONObject()
        val decoders = JSONObject()
        for ((mime, list) in c.decoders) {
            val arr = JSONArray()
            for (d in list) {
                arr.put(
                    JSONObject()
                        .put("n", d.name).put("hw", d.hardware)
                        .put("w", d.maxWidth).put("h", d.maxHeight)
                        .put("f1", d.maxFps1080).put("f2", d.maxFps2160)
                        .put("br", d.maxBitrate).put("t", d.tenBit)
                )
            }
            decoders.put(mime, arr)
        }
        root.put("decoders", decoders)
        root.put("hdr", JSONArray(c.displayHdr.toList()))
        root.put("dv", c.dolbyVisionDecoder)
        return root.toString()
    }

    private fun fromJson(json: String): DeviceVideoCaps {
        val root = JSONObject(json)
        val decoders = mutableMapOf<String, List<ProbedDecoder>>()
        val obj = root.getJSONObject("decoders")
        for (mime in obj.keys()) {
            val arr = obj.getJSONArray(mime)
            decoders[mime] = (0 until arr.length()).map { i ->
                val d = arr.getJSONObject(i)
                ProbedDecoder(
                    name = d.getString("n"), hardware = d.getBoolean("hw"),
                    maxWidth = d.getInt("w"), maxHeight = d.getInt("h"),
                    maxFps1080 = d.getInt("f1"), maxFps2160 = d.getInt("f2"),
                    maxBitrate = d.getInt("br"), tenBit = d.getBoolean("t")
                )
            }
        }
        val hdrArr = root.getJSONArray("hdr")
        val hdr = (0 until hdrArr.length()).map { hdrArr.getString(it) }.toSet()
        return DeviceVideoCaps(decoders, hdr, root.getBoolean("dv"))
    }
}
