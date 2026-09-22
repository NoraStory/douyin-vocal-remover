package com.nora.douyinremover.audio

interface MediaEncoder {
    suspend fun extractPcm(inputPath: String, outputRawPath: String): ExtractedAudio

    suspend fun encodePcm(
        rawPath: String,
        outputPath: String,
        format: String,
        bitrateKbps: Int,
        sampleRate: Int,
        channels: Int
    ): Unit

    suspend fun trimSilence(
        inputPath: String,
        outputPath: String,
        thresholdDb: Float,
        bitrateKbps: Int
    ): Unit
}

data class ExtractedAudio(
    val sampleRate: Int,
    val channels: Int,
    val durationMs: Long
)
