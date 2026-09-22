package com.nora.douyinremover.audio

import android.util.Log
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AudioProcessor(
    private val encoder: MediaEncoder,
    private val separator: AudioSeparator
) {
    suspend fun process(request: SeparationRequest): SeparationResult = withContext(Dispatchers.IO) {
        val workDir = File(request.outputPath).parentFile
            ?: throw IllegalStateException("输出路径无效")
        workDir.mkdirs()

        val rawPath = File(workDir, "extracted.f32le").absolutePath
        val separatedPath = File(workDir, "instrumental.f32le").absolutePath
        val encodedPath = File(workDir, "instrumental_encoded.${request.outputFormat}").absolutePath

        Log.i(TAG, "stage=extract begin ${request.inputPath}")
        val extracted = encoder.extractPcm(request.inputPath, rawPath)
        Log.i(TAG, "stage=extract done durationMs=${extracted.durationMs}")
        try {
            // 流式分段分离：输入/输出都走文件，任意时刻内存中只保留一个推理段（约 5MB）
            Log.i(TAG, "stage=separate begin")
            separator.separateToInstrumentalFile(rawPath, separatedPath, extracted.channels, extracted.sampleRate)
            Log.i(TAG, "stage=separate done")
            encoder.encodePcm(
                rawPath = separatedPath,
                outputPath = encodedPath,
                format = request.outputFormat,
                bitrateKbps = request.bitrateKbps,
                sampleRate = extracted.sampleRate,
                channels = extracted.channels
            )
            Log.i(TAG, "stage=encode done")

            if (request.silenceThresholdDb < 0f) {
                encoder.trimSilence(encodedPath, request.outputPath, request.silenceThresholdDb, request.bitrateKbps)
            } else {
                File(encodedPath).copyTo(File(request.outputPath), overwrite = true)
            }
            Log.i(TAG, "stage=finalize done -> ${request.outputPath}")
        } finally {
            File(rawPath).delete()
            File(separatedPath).delete()
            File(encodedPath).delete()
        }

        SeparationResult(
            outputPath = request.outputPath,
            sampleRate = extracted.sampleRate,
            channels = extracted.channels,
            durationMs = extracted.durationMs
        )
    }

    private companion object {
        const val TAG = "AudioProcessor"
    }
}
