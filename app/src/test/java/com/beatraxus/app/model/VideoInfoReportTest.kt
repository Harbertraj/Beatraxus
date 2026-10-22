package com.beatraxus.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoInfoReportTest {

    private val video = VideoStreamInfo("video/avc", "avc1.640028", 1920, 1080, 23.976f, 8_500_000)
    private val audio = AudioStreamInfo("audio/eac3", null, 6, 48000, 640_000, "eng", "English")
    private val data = VideoInfoData(
        fileName = "Movie.mkv", location = "/storage/emulated/0/Movies", containerMime = "video/x-matroska",
        sizeBytes = 4_294_967_296L, durationMs = 7_200_000L, isStreamed = false,
        video = video, audio = audio, videoDecoder = "c2.qti.avc.decoder", audioDecoder = "c2.android.eac3.decoder",
        droppedFrames = 3, renderedFrames = 997
    )

    private fun value(sections: List<InfoSection>, section: String, label: String): String? =
        sections.first { it.title == section }.rows.firstOrNull { it.label == label }?.value

    @Test fun fpsKeepsDecimalsOnlyWhenNeeded() {
        assertEquals("23.976 fps", VideoInfoReport.formatFps(23.976f))
        assertEquals("25 fps", VideoInfoReport.formatFps(25f))
        assertEquals("29.97 fps", VideoInfoReport.formatFps(29.97f))
        assertEquals("60 fps", VideoInfoReport.formatFps(60.0001f))
        assertEquals(VideoInfoReport.UNKNOWN, VideoInfoReport.formatFps(0f))
    }

    @Test fun bitrateUsesMbpsAboveOneMillion() {
        assertEquals("8.50 Mbps", VideoInfoReport.formatBitrate(8_500_000))
        assertEquals("640 kbps", VideoInfoReport.formatBitrate(640_000))
        assertEquals(VideoInfoReport.UNKNOWN, VideoInfoReport.formatBitrate(-1))
    }

    @Test fun sizeDurationSampleRateChannels() {
        assertEquals("4.00 GB", VideoInfoReport.formatSize(4_294_967_296L))
        assertEquals("1.0 MB", VideoInfoReport.formatSize(1_048_576L))
        assertEquals("2:00:00", VideoInfoReport.formatDuration(7_200_000))
        assertEquals("3:05", VideoInfoReport.formatDuration(185_000))
        assertEquals("44.1 kHz", VideoInfoReport.formatSampleRate(44100))
        assertEquals("48 kHz", VideoInfoReport.formatSampleRate(48000))
        assertEquals("6 (5.1)", VideoInfoReport.channelLabel(6))
        assertEquals("5", VideoInfoReport.channelLabel(5))
    }

    @Test fun codecNames() {
        assertEquals("H.265 (HEVC)", VideoInfoReport.videoCodecName("video/hevc"))
        assertEquals("AV1", VideoInfoReport.videoCodecName("video/av01"))
        assertEquals("FOO", VideoInfoReport.videoCodecName("video/foo"))
        assertNull(VideoInfoReport.videoCodecName(null))
        assertEquals("Dolby Digital Plus with Atmos", VideoInfoReport.audioCodecName("audio/eac3-joc"))
        assertEquals("AAC (HE-AAC)", VideoInfoReport.audioCodecName("audio/mp4a-latm", "mp4a.40.5"))
        assertEquals("AAC", VideoInfoReport.audioCodecName("audio/mp4a-latm", "mp4a.40.2"))
    }

    @Test fun profilesAreReadFromTheCodecString() {
        assertEquals("High, level 4.0", VideoInfoReport.codecProfile("video/avc", "avc1.640028"))
        assertEquals("Main, level 3.1", VideoInfoReport.codecProfile("video/avc", "avc1.4d401f"))
        assertEquals("Main 10, level 5.1", VideoInfoReport.codecProfile("video/hevc", "hvc1.2.4.L153.B0"))
        assertEquals("Main, level 4.0", VideoInfoReport.codecProfile("video/hevc", "hev1.1.6.L120.B0"))
        assertNull(VideoInfoReport.codecProfile("video/avc", "garbage"))
        assertNull(VideoInfoReport.codecProfile("video/av01", "av01.0.08M.08"))
        assertNull(VideoInfoReport.codecProfile("video/avc", null))
    }

    @Test fun containerFromMimeThenExtension() {
        assertEquals("Matroska (MKV)", VideoInfoReport.containerName("video/x-matroska", "a.bin"))
        assertEquals("MP4", VideoInfoReport.containerName(null, "clip.m4v"))
        assertEquals("OGV", VideoInfoReport.containerName(null, "clip.ogv"))
        assertNull(VideoInfoReport.containerName(null, "noextension"))
    }

    @Test fun resolutionClass() {
        assertEquals("1920 x 1080 (1080p)", VideoInfoReport.resolutionLabel(1920, 1080))
        assertEquals("3840 x 2160 (4K)", VideoInfoReport.resolutionLabel(3840, 2160))
        assertEquals("1920 x 800 (1080p)", VideoInfoReport.resolutionLabel(1920, 800)) // scope film
        assertEquals("640 x 360 (SD)", VideoInfoReport.resolutionLabel(640, 360))
    }

    @Test fun dynamicRange() {
        assertEquals("HDR10 / PQ, BT.2020", VideoInfoReport.dynamicRange("video/hevc", 6, 6))
        assertEquals("HLG, BT.2020", VideoInfoReport.dynamicRange("video/hevc", 7, 6))
        assertEquals("Dolby Vision", VideoInfoReport.dynamicRange("video/dolby-vision", -1, -1))
        assertEquals("SDR, BT.709", VideoInfoReport.dynamicRange("video/avc", 3, 1))
        assertEquals("SDR", VideoInfoReport.dynamicRange("video/avc", -1, -1))
    }

    @Test fun decoderKind() {
        assertEquals("software", VideoInfoReport.decoderKind("c2.android.avc.decoder"))
        assertEquals("software", VideoInfoReport.decoderKind("OMX.google.h264.decoder"))
        assertEquals("software", VideoInfoReport.decoderKind("ffmpeg"))
        assertEquals("hardware", VideoInfoReport.decoderKind("c2.qti.avc.decoder"))
        assertEquals("hardware", VideoInfoReport.decoderKind("OMX.MTK.VIDEO.DECODER.AVC"))
    }

    @Test fun bitrateFallsBackToAnEstimate() {
        val noRate = video.copy(bitrate = -1)
        assertEquals("8.50 Mbps", VideoInfoReport.videoBitrateText(video, 9_000_000, 640_000))
        assertEquals("~8.36 Mbps (estimated)", VideoInfoReport.videoBitrateText(noRate, 9_000_000, 640_000))
        assertEquals("6.00 Mbps (peak)", VideoInfoReport.videoBitrateText(noRate.copy(peakBitrate = 6_000_000), 9_000_000, 640_000))
        assertEquals(VideoInfoReport.UNKNOWN, VideoInfoReport.videoBitrateText(noRate, 0, 0))
    }

    @Test fun overallBitrateNeedsSizeAndDuration() {
        assertEquals(0L, VideoInfoReport.overallBitrate(0, 1000))
        assertEquals(0L, VideoInfoReport.overallBitrate(1000, 0))
        assertEquals(8_000_000L, VideoInfoReport.overallBitrate(1_000_000, 1000))
    }

    @Test fun fullReport() {
        val s = VideoInfoReport.sections(data)
        assertEquals(listOf("General", "Video", "Audio"), s.map { it.title })
        assertEquals("Matroska (MKV)", value(s, "General", "Container"))
        assertEquals("H.264 (AVC)", value(s, "Video", "Codec"))
        assertEquals("High, level 4.0", value(s, "Video", "Profile"))
        assertEquals("23.976 fps", value(s, "Video", "Frame rate"))
        assertEquals("8.50 Mbps", value(s, "Video", "Bitrate"))
        assertEquals("c2.qti.avc.decoder (hardware)", value(s, "Video", "Decoder"))
        assertEquals("3 of 1000", value(s, "Video", "Dropped frames"))
        assertEquals("Dolby Digital Plus (E-AC-3)", value(s, "Audio", "Codec"))
        assertEquals("6 (5.1)", value(s, "Audio", "Channels"))
        assertEquals("English, eng", value(s, "Audio", "Track"))
    }

    @Test fun missingStreamsAreReportedNotCrashed() {
        val s = VideoInfoReport.sections(data.copy(video = null, audio = null, sizeBytes = 0, durationMs = 0))
        assertEquals("No video stream", value(s, "Video", "Stream"))
        assertEquals("No audio stream", value(s, "Audio", "Stream"))
        assertTrue(VideoInfoReport.toPlainText(s).contains("Video\nStream: No video stream"))
    }
}
