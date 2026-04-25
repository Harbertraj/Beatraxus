package com.beatflowy.app.engine

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

class AudioTrackOutput : AudioOutput {
    private var audioTrack: AudioTrack? = null
    private var sampleRate = 44_100
    private var channels = 2
    private var totalFramesWritten = 0L
    private var playbackHeadWraps = 0L
    private var lastPlaybackHeadPosition = 0

    override fun init(sampleRate: Int, channels: Int): Boolean {
        this.sampleRate = sampleRate
        this.channels = channels
        val channelConfig = when (channels) {
            1 -> AudioFormat.CHANNEL_OUT_MONO
            2 -> AudioFormat.CHANNEL_OUT_STEREO
            else -> AudioFormat.CHANNEL_OUT_STEREO
        }
        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_FLOAT)
        val bufferSize = maxOf(minBuffer * 4, sampleRate * channels * 4 / 5)

        if (bufferSize <= 0) return false

        try {
            audioTrack?.let {
                it.stop()
                it.release()
            }
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build())
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfig)
                    .build())
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            totalFramesWritten = 0L
            playbackHeadWraps = 0L
            lastPlaybackHeadPosition = 0
        } catch (e: Exception) {
            return false
        }
        
        return audioTrack?.state == AudioTrack.STATE_INITIALIZED
    }

    override fun start() { 
        try {
            audioTrack?.play() 
        } catch (e: Exception) {}
    }

    override fun stop() { 
        try {
            audioTrack?.pause()
            audioTrack?.flush()
            totalFramesWritten = 0L
            playbackHeadWraps = 0L
            lastPlaybackHeadPosition = 0
        } catch (e: Exception) {}
    }

    override fun flush() {
        try {
            audioTrack?.flush()
            totalFramesWritten = 0L
            playbackHeadWraps = 0L
            lastPlaybackHeadPosition = 0
        } catch (e: Exception) {}
    }

    override fun release() { 
        audioTrack?.let {
            try {
                it.stop()
                it.release()
            } catch (e: Exception) {}
        }
        audioTrack = null 
    }

    override fun write(data: FloatArray, offsetInSamples: Int, frameCount: Int): Int {
        val track = audioTrack ?: return 0
        return try {
            val writtenSamples = track.write(data, offsetInSamples, frameCount * channels, AudioTrack.WRITE_BLOCKING)
            val writtenFrames = if (writtenSamples > 0) writtenSamples / channels else writtenSamples
            if (writtenFrames > 0) {
                totalFramesWritten += writtenFrames.toLong()
            }
            writtenFrames
        } catch (e: Exception) {
            0
        }
    }

    override fun playbackPositionFrames(): Long {
        val track = audioTrack ?: return 0L
        return try {
            val head = track.playbackHeadPosition
            if (head < lastPlaybackHeadPosition) {
                playbackHeadWraps++
            }
            lastPlaybackHeadPosition = head
            (playbackHeadWraps shl 32) + (head.toLong() and 0xFFFFFFFFL)
        } catch (_: Exception) {
            0L
        }
    }

    override fun outputSampleRate(): Int = sampleRate

    override fun outputPathLabel(): String {
        return if (sampleRate > 48_000) "Hi-Res" else "AudioTrack"
    }

    override fun estimatedLatencyMs(): Int {
        val queuedFrames = (totalFramesWritten - playbackPositionFrames()).coerceAtLeast(0L)
        if (sampleRate <= 0) return 0
        return ((queuedFrames * 1000L) / sampleRate).toInt()
    }
}
