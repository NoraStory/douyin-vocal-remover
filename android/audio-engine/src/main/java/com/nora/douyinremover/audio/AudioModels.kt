package com.nora.douyinremover.audio

data class SeparationRequest(
    val inputPath: String,
    val outputPath: String,
    val outputFormat: String = "mp3",
    val bitrateKbps: Int = 320,
    val silenceThresholdDb: Float = -55f
)

data class SeparationResult(
    val outputPath: String,
    val sampleRate: Int,
    val channels: Int,
    val durationMs: Long
)
