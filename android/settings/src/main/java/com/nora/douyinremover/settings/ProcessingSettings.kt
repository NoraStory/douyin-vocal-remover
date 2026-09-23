package com.nora.douyinremover.settings

enum class AudioOutputFormat {
    MP3_320,
    MP3_256,
    MP3_128,
    WAV,
    FLAC
}

enum class InferenceBackend {
    AUTO,
    NNAPI,
    CPU
}

data class ProcessingSettings(
    val outputFormat: AudioOutputFormat = AudioOutputFormat.MP3_320,
    val deleteSourceAfterSuccess: Boolean = false,
    val keepHistory: Boolean = true,
    val inferenceBackend: InferenceBackend = InferenceBackend.AUTO,
    val silenceThresholdDb: Float = -55f,
    val outputDirectoryUri: String? = null,
    val proxyUrl: String? = null,
    /** 开屏使用说明：用户勾选"下次不再显示"后为 false（页头问号仍可重新打开） */
    val showGuideOnLaunch: Boolean = true
)
