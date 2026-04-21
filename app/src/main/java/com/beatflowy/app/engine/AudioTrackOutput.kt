package com.beatflowy.app.engine

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

class AudioTrackOutput : AudioOutput {
    private var audioTrack: AudioTrack? = null
    private var channels = 2

    override fun init(sampleRate: Int, channels: Int): Boolean {
        this.channels = channels
        val channelConfig = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val bufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_FLOAT) * 2

        if (bufferSize <= 0) return false

        try {
            audioTrack?.release()
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
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
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
            audioTrack?.stop() 
        } catch (e: Exception) {}
    }

    override fun release() { 
        audioTrack?.release()
        audioTrack = null 
    }

    override fun write(data: FloatArray, frameCount: Int): Int {
        return audioTrack?.write(data, 0, frameCount * channels, AudioTrack.WRITE_BLOCKING) ?: 0
    }
}
