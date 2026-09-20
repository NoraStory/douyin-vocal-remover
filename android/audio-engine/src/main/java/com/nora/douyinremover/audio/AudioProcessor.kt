package com.nora.douyinremover.audio

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
        val encodedPath = File(workDir, "instrumental_encoded.${request.outputFormat}").absolutePath

        val extracted = encoder.extractPcm(request.inputPath, rawPath)
        val pcm = readFloatPcm(rawPath)
        val instrumental = separator.separateToInstrumental(pcm, extracted.channels, extracted.sampleRate)
        writeFloatPcm(rawPath, instrumental)
        encoder.encodePcm(
            rawPath = rawPath,
            outputPath = encodedPath,
            format = request.outputFormat,
            bitrateKbps = request.bitrateKbps,
            sampleRate = extracted.sampleRate,
            channels = extracted.channels
        )

        if (request.silenceThresholdDb < 0f) {
            encoder.trimSilence(encodedPath, request.outputPath, request.silenceThresholdDb)
        } else {
            File(encodedPath).copyTo(File(request.outputPath), overwrite = true)
        }

        File(rawPath).delete()
        File(encodedPath).delete()
        SeparationResult(
            outputPath = request.outputPath,
            sampleRate = extracted.sampleRate,
            channels = extracted.channels,
            durationMs = extracted.durationMs
        )
    }

    private fun readFloatPcm(path: String): FloatArray {
        val bytes = File(path).readBytes()
        val values = FloatArray(bytes.size / 4)
        for (index in values.indices) {
            val bitOffset = index * 4
            val bits = ((bytes[bitOffset].toInt() and 0xff)) or
                ((bytes[bitOffset + 1].toInt() and 0xff) shl 8) or
                ((bytes[bitOffset + 2].toInt() and 0xff) shl 16) or
                ((bytes[bitOffset + 3].toInt() and 0xff) shl 24)
            values[index] = Float.fromBits(bits)
        }
        return values
    }

    private fun writeFloatPcm(path: String, values: FloatArray) {
        val bytes = ByteArray(values.size * 4)
        values.forEachIndexed { index, value ->
            val bits = value.toRawBits()
            bytes[index * 4] = bits.toByte()
            bytes[index * 4 + 1] = (bits shr 8).toByte()
            bytes[index * 4 + 2] = (bits shr 16).toByte()
            bytes[index * 4 + 3] = (bits shr 24).toByte()
        }
        File(path).writeBytes(bytes)
    }
}
