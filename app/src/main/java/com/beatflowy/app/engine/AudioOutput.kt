package com.beatflowy.app.engine

interface AudioOutput {
    fun init(sampleRate: Int, channels: Int): Boolean
    fun start()
    fun stop()
    fun flush()
    fun write(data: FloatArray, frameCount: Int): Int
    fun release()
}
