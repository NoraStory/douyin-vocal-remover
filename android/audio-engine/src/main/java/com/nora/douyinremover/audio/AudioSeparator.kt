package com.nora.douyinremover.audio

/**
 * 音频分离器。流式接口：输入/输出均为交错 f32le PCM 文件，
 * 实现内部按段读取推理，避免整曲载入内存导致 OOM。
 */
interface AudioSeparator {
    suspend fun separateToInstrumentalFile(
        inputPcmPath: String,
        outputPcmPath: String,
        channels: Int,
        sampleRate: Int
    )
}
