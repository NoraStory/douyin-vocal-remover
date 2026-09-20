package com.nora.douyinremover.audio

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer
import java.util.Collections

class OnnxDemucsSeparator(
    context: Context,
    private val modelAssetPath: String = "models/htdemucs_fp32.onnx",
    private val useNnapi: Boolean = true
) : AudioSeparator {
    private val ortEnvironment = OrtEnvironment.getEnvironment()
    private val modelBytes by lazy {
        context.assets.open(modelAssetPath).use { it.readBytes() }
    }
    private val session: OrtSession by lazy {
        val options = OrtSession.SessionOptions().apply {
            if (useNnapi) {
                runCatching { addNnapi() }
            }
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        ortEnvironment.createSession(modelBytes, options)
    }

    override suspend fun separateToInstrumental(
        pcm: FloatArray,
        channels: Int,
        sampleRate: Int
    ): FloatArray = withContext(Dispatchers.Default) {
        require(channels == CHANNELS) { "当前模型需要 2 声道" }
        require(sampleRate == SAMPLE_RATE) { "当前模型需要 44100Hz" }
        if (pcm.size < SEGMENT_SAMPLES) return@withContext inferSingle(pcm)

        val hop = SEGMENT_SAMPLES - OVERLAP_SAMPLES
        val output = FloatArray(pcm.size)
        val weights = FloatArray(pcm.size)
        var start = 0
        while (start < pcm.size) {
            val end = minOf(start + SEGMENT_SAMPLES, pcm.size)
            val segment = FloatArray(SEGMENT_SAMPLES)
            for (index in start until end) {
                segment[index - start] = pcm[index]
            }
            val separated = inferSingle(segment)
            for (index in 0 until minOf(end - start, separated.size)) {
                val global = start + index
                output[global] += separated[index]
                weights[global] += 1f
            }
            start += hop
        }
        for (index in output.indices) {
            if (weights[index] > 0f) output[index] /= weights[index]
        }
        output
    }

    private fun inferSingle(segment: FloatArray): FloatArray {
        val input = FloatArray(SEGMENT_SAMPLES * CHANNELS)
        for (channel in 0 until CHANNELS) {
            for (index in 0 until SEGMENT_SAMPLES) {
                val target = channel * SEGMENT_SAMPLES + index
                input[target] = segment.getOrElse(target) { 0f }
            }
        }

        val inputTensor = OnnxTensor.createTensor(
            ortEnvironment,
            FloatBuffer.wrap(input),
            longArrayOf(1, CHANNELS.toLong(), SEGMENT_SAMPLES.toLong())
        )
        val result = session.run(Collections.singletonMap(INPUT_NAME, inputTensor))
        val outputTensor = result.use { it[0].value as OnnxTensor }
        val raw = outputTensor.floatBuffer.array()
        val output = FloatArray(SEGMENT_SAMPLES * CHANNELS)
        for (channel in 0 until CHANNELS) {
            for (index in 0 until SEGMENT_SAMPLES) {
                val target = channel * SEGMENT_SAMPLES + index
                output[target] = raw.getOrElse(target) { 0f }
            }
        }
        return output
    }

    private companion object {
        const val SAMPLE_RATE = 44100
        const val CHANNELS = 2
        const val SEGMENT_SECONDS = 7.8f
        const val OVERLAP_SECONDS = 0.25f
        const val SEGMENT_SAMPLES = (SAMPLE_RATE * SEGMENT_SECONDS).toInt()
        const val OVERLAP_SAMPLES = (SAMPLE_RATE * OVERLAP_SECONDS).toInt()
        const val INPUT_NAME = "input"
    }
}
