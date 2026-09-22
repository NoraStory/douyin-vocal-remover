package com.nora.douyinremover.audio

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class FfmpegMediaEncoder : MediaEncoder {
    override suspend fun extractPcm(inputPath: String, outputRawPath: String): ExtractedAudio =
        withContext(Dispatchers.IO) {
            val command = listOf(
                "-y",
                "-i", inputPath,
                "-vn",
                "-ac", "2",
                "-ar", "44100",
                "-f", "f32le",
                outputRawPath
            )
        execute(command)
        val sampleRate = 44100
        val channels = 2
        val bytes = File(outputRawPath).length()
        // f32le: 每采样 4 字节；时长 = 字节数 / 4(字节/采样) / 声道数 / 采样率 * 1000
        val durationMs = bytes * 1000L / (4L * channels * sampleRate)
        ExtractedAudio(
            sampleRate = sampleRate,
            channels = channels,
            durationMs = durationMs
        )
    }

    override suspend fun encodePcm(
        rawPath: String,
        outputPath: String,
        format: String,
        bitrateKbps: Int,
        sampleRate: Int,
        channels: Int
    ) = withContext(Dispatchers.IO) {
        val codecArgs = when (format.lowercase()) {
            "mp3" -> listOf("-codec:a", "libmp3lame", "-b:a", "${bitrateKbps}k")
            "wav" -> listOf("-codec:a", "pcm_s16le")
            "flac" -> listOf("-codec:a", "flac")
            else -> throw IllegalArgumentException("不支持的输出格式: $format")
        }
        execute(
            listOf(
                "-y",
                "-f", "f32le",
                "-ar", sampleRate.toString(),
                "-ac", channels.toString(),
                "-i", rawPath
            ) + codecArgs + outputPath
        )
    }

    override suspend fun trimSilence(
        inputPath: String,
        outputPath: String,
        thresholdDb: Float,
        bitrateKbps: Int
    ) = withContext(Dispatchers.IO) {
        // 根据输出文件扩展名决定重编码方式；silenceremove 滤镜必须重编码，-c:a copy 会被忽略/报错
        val format = File(outputPath).extension.lowercase()
        val codecArgs = when (format) {
            "mp3" -> listOf("-codec:a", "libmp3lame", "-b:a", "${bitrateKbps}k")
            "wav" -> listOf("-codec:a", "pcm_s16le")
            "flac" -> listOf("-codec:a", "flac")
            else -> throw IllegalArgumentException("不支持的输出格式: $format")
        }
        execute(
            listOf(
                "-y",
                "-i", inputPath,
                "-af", "silenceremove=start_periods=1:stop_periods=1:start_threshold=${thresholdDb}dB:stop_threshold=${thresholdDb}dB"
            ) + codecArgs + outputPath
        )
    }

    private fun execute(arguments: List<String>) {
        val command = arguments.joinToString(" ") { quoteArgument(it) }
        val session = FFmpegKit.execute(command)
        if (!ReturnCode.isSuccess(session.returnCode)) {
            throw IllegalStateException(
                "FFmpeg 执行失败: ${session.allLogsAsString}"
            )
        }
    }

    private fun quoteArgument(value: String): String {
        if (value.none { it.isWhitespace() }) return value
        return "\"" + value.replace("\"", "\\\"") + "\""
    }
}
