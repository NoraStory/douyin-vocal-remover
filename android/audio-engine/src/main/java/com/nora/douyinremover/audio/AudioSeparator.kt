package com.nora.douyinremover.audio

interface AudioSeparator {
    suspend fun separateToInstrumental(
        pcm: FloatArray,
        channels: Int,
        sampleRate: Int
    ): FloatArray
}
