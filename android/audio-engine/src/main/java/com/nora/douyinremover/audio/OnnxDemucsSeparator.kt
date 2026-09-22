package com.nora.douyinremover.audio

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Collections

/**
 * ONNX Demucs 人声分离。流式分段处理：
 * 输入/输出均为交错 f32le PCM 文件，每次只读入一个推理段（约 7.8 秒），
 * 分离结果带重叠混合后写回输出文件，内存占用恒定在约 10MB。
 */
class OnnxDemucsSeparator(
    private val context: Context,
    private val modelAssetPath: String = "models/htdemucs_fp32.onnx",
    private val fp16ModelAssetPath: String = "models/htdemucs_fp16.onnx",
    private val useNnapi: Boolean = true
) : AudioSeparator {
    private val ortEnvironment: OrtEnvironment by lazy {
        Log.i(TAG, "initializing OrtEnvironment")
        OrtEnvironment.getEnvironment()
    }

    private val isLowMemoryDevice: Boolean by lazy {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memClass = activityManager.memoryClass // 应用可用堆，MB
        val memInfo = android.app.ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        val totalMem = (memInfo.totalMem / (1024 * 1024)).toInt()
        val low = isEmulator || totalMem < 4000 || memClass < 192
        Log.i(TAG, "device: emulator=$isEmulator totalMemMB=$totalMem memClassMB=$memClass lowMemory=$low")
        low
    }

    private val isEmulator: Boolean by lazy {
        android.os.Build.FINGERPRINT.contains("generic") ||
            android.os.Build.MODEL.contains("Emulator") ||
            android.os.Build.MODEL.contains("sdk_gphone")
    }

    private val activeModelAsset: String by lazy {
        // fp32 模型 243MB，CPU EP 加载后 RSS 约 1.2GB，低内存设备直接被系统 OOM 杀掉；
        // fp16 模型 128MB，权重减半，低内存设备强制使用。
        if (isLowMemoryDevice) fp16ModelAssetPath else modelAssetPath
    }

    /**
     * 模型文件较大，不能 readBytes() 读进 Java 堆（直接 OOM）。
     * 先把 assets 里的模型拷到 filesDir，再用文件路径创建 Session，
     * ONNX Runtime 会 mmap 模型文件，不占用 Java 堆。
     */
    private val modelFilePath: String by lazy {
        val modelsDir = File(context.filesDir, "models").apply { mkdirs() }
        val assetName = File(activeModelAsset).name
        val target = File(modelsDir, assetName)
        if (!target.exists() || target.length() == 0L) {
            context.assets.open(activeModelAsset).use { input ->
                val tmp = File(modelsDir, "$assetName.tmp")
                tmp.outputStream().use { output -> input.copyTo(output, BUFFER_SIZE) }
                if (!tmp.renameTo(target)) {
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }
            }
        }
        target.absolutePath
    }
    private val session: OrtSession by lazy {
        Log.i(TAG, "creating OrtSession model=$activeModelAsset lowMemory=$isLowMemoryDevice")
        val options = OrtSession.SessionOptions().apply {
            // NNAPI 会把整份模型复制进驱动内存（fp32 模型在 2GB 设备上直接触发系统 OOM），
            // 模拟器/低内存设备回退 CPU EP；NNAPI 只在内存充裕的真机上启用。
            if (useNnapi && !isLowMemoryDevice) {
                runCatching { addNnapi() }
            }
            // 限制 CPU 线程数：默认按核数开线程，每个线程都有 arena 开销
            runCatching { setIntraOpNumThreads(if (isLowMemoryDevice) 2 else 4) }
            // ALL_OPT 的整图优化在 x86 模拟器上对 fp16 大模型会卡死（数分钟无进展），
            // 低内存设备降为基本优化；真机保持 ALL_OPT。
            setOptimizationLevel(
                if (isLowMemoryDevice) OrtSession.SessionOptions.OptLevel.BASIC_OPT
                else OrtSession.SessionOptions.OptLevel.ALL_OPT
            )
        }
        val s = ortEnvironment.createSession(modelFilePath, options)
        Log.i(TAG, "OrtSession created")
        s
    }

    override suspend fun separateToInstrumentalFile(
        inputPcmPath: String,
        outputPcmPath: String,
        channels: Int,
        sampleRate: Int
    ): Unit {
        require(channels == CHANNELS) { "当前模型需要 2 声道" }
        require(sampleRate == SAMPLE_RATE) { "当前模型需要 44100Hz" }

        // x86 模拟器上 ORT 加载 fp16 大模型会永久阻塞（Session 创建线程冻结，CPU 零增长），
        // 无法在模拟器完成推理；直接抛出明确错误，由上层提示用户在真机上使用。
        // 注意：guard 必须在触碰任何 ORT 类（含 OrtEnvironment）之前执行，
        // 否则 ORT 原生库加载本身就会卡死线程。
        if (isLowMemoryDevice && isEmulator) {
            Log.w(TAG, "emulator detected, skipping ONNX separation")
            throw UnsupportedOperationException(
                "模拟器无法运行本地人声分离（ONNX 推理在 x86 模拟器上不可用），请在真机上使用"
            )
        }

        return withContext(Dispatchers.Default) {
            val inputFile = RandomAccessFile(inputPcmPath, "r")
            val outputFile = RandomAccessFile(outputPcmPath, "rw")
            try {
                val totalFrames = (inputFile.length() / 4 / CHANNELS).toLong()
                if (totalFrames <= 0) return@withContext

            // 输出文件先补齐到与输入等长（后续按帧覆盖）
            outputFile.setLength(totalFrames * CHANNELS * 4L)

            val hop = SEGMENT_SAMPLES - OVERLAP_SAMPLES
            // 段缓冲：交错读取的原始段 + 交错的分离结果（各约 2.5MB float）
            val segment = FloatArray(SEGMENT_SAMPLES * CHANNELS)
            val mixed = FloatArray(SEGMENT_SAMPLES * CHANNELS)
            val weights = FloatArray(SEGMENT_SAMPLES)
            val byteBuffer = ByteBuffer.allocate(SEGMENT_SAMPLES * CHANNELS * 4)
                .order(ByteOrder.LITTLE_ENDIAN)

            var segmentIndex = 0
            var frameStart = 0L
            while (frameStart < totalFrames) {
                val framesThisSegment = minOf(SEGMENT_SAMPLES.toLong(), totalFrames - frameStart).toInt()

                // 读取一段（不足部分补零）
                java.util.Arrays.fill(segment, 0f)
                byteBuffer.clear()
                byteBuffer.limit(framesThisSegment * CHANNELS * 4)
                inputFile.seek(frameStart * CHANNELS * 4L)
                inputFile.readFully(byteBuffer.array(), 0, framesThisSegment * CHANNELS * 4)
                byteBuffer.rewind()
                byteBuffer.asFloatBuffer().get(segment, 0, framesThisSegment * CHANNELS)

                // 推理
                val separated = inferSingle(segment)
                java.util.Arrays.fill(mixed, 0f)
                java.util.Arrays.fill(weights, 0f)

                // 重叠混合：把本段结果与输出文件中相邻段已写入的部分做平均。
                // 为避免随机读写整段，采用“读-改-写”本段覆盖区间。
                val outStart = frameStart * CHANNELS
                val outFrames = framesThisSegment
                val existing = FloatArray(outFrames * CHANNELS)
                val existingBuf = ByteBuffer.allocate(outFrames * CHANNELS * 4)
                    .order(ByteOrder.LITTLE_ENDIAN)
                outputFile.seek(outStart * 4L)
                outputFile.readFully(existingBuf.array(), 0, outFrames * CHANNELS * 4)
                existingBuf.rewind()
                existingBuf.asFloatBuffer().get(existing)

                for (i in 0 until outFrames) {
                    weights[i] += 1f
                    for (c in 0 until CHANNELS) {
                        val segIdx = i * CHANNELS + c
                        val mixedIdx = i + c * SEGMENT_SAMPLES
                        existing[i * CHANNELS + c] += separated[mixedIdx]
                    }
                }

                val outBuf = ByteBuffer.allocate(outFrames * CHANNELS * 4)
                    .order(ByteOrder.LITTLE_ENDIAN)
                val outFloats = outBuf.asFloatBuffer()
                for (i in 0 until outFrames) {
                    val w = weights[i]
                    for (c in 0 until CHANNELS) {
                        outFloats.put(existing[i * CHANNELS + c] / w)
                    }
                }
                outputFile.seek(outStart * 4L)
                outputFile.write(outBuf.array(), 0, outFrames * CHANNELS * 4)

                frameStart += hop
                segmentIndex++
                if (segmentIndex % 10 == 0) {
                    Log.i(TAG, "separate progress: segment=$segmentIndex frame=$frameStart/$totalFrames")
                }
            }
            Log.i(TAG, "separate done: segments=$segmentIndex")
        } finally {
            runCatching { inputFile.close() }
            runCatching { outputFile.close() }
        }
        }
    }

    private fun inferSingle(segment: FloatArray): FloatArray {
        // 模型输入为 [1, 2, N]（channel-first），文件里是交错格式（frame-first），需重排
        val input = FloatArray(SEGMENT_SAMPLES * CHANNELS)
        for (frame in 0 until SEGMENT_SAMPLES) {
            input[frame] = segment[frame * CHANNELS]
            input[SEGMENT_SAMPLES + frame] = segment[frame * CHANNELS + 1]
        }

        val inputTensor = OnnxTensor.createTensor(
            ortEnvironment,
            FloatBuffer.wrap(input),
            longArrayOf(1, CHANNELS.toLong(), SEGMENT_SAMPLES.toLong())
        )
        val result = session.run(Collections.singletonMap(INPUT_NAME, inputTensor))
        result.use {
            val raw = (it[0].value as OnnxTensor).floatBuffer
            // 输出同样 channel-first，转回交错格式
            val output = FloatArray(SEGMENT_SAMPLES * CHANNELS)
            val left = FloatArray(SEGMENT_SAMPLES)
            val right = FloatArray(SEGMENT_SAMPLES)
            raw.get(left, 0, SEGMENT_SAMPLES)
            raw.get(right, 0, SEGMENT_SAMPLES)
            for (frame in 0 until SEGMENT_SAMPLES) {
                output[frame * CHANNELS] = left[frame]
                output[frame * CHANNELS + 1] = right[frame]
            }
            return output
        }
    }

    private companion object {
        const val TAG = "OnnxDemucsSeparator"
        const val SAMPLE_RATE = 44100
        const val CHANNELS = 2
        const val SEGMENT_SECONDS = 7.8f
        const val OVERLAP_SECONDS = 0.25f
        const val SEGMENT_SAMPLES = (SAMPLE_RATE * SEGMENT_SECONDS).toInt()
        const val OVERLAP_SAMPLES = (SAMPLE_RATE * OVERLAP_SECONDS).toInt()
        const val INPUT_NAME = "input"
        const val BUFFER_SIZE = 1 shl 16
    }
}
