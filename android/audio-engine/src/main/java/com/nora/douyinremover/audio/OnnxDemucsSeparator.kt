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
    private val fp32ModelFileName: String = "htdemucs_fp32.onnx",
    private val fp16ModelFileName: String = "htdemucs_fp16.onnx"
) : AudioSeparator {
    private val ortEnvironment: OrtEnvironment by lazy {
        Log.i(TAG, "initializing OrtEnvironment")
        OrtEnvironment.getEnvironment()
    }

    private val isLowMemoryDevice: Boolean by lazy {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memInfo = android.app.ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        val totalMem = (memInfo.totalMem / (1024 * 1024)).toInt()
        // 只看真实 RAM。memoryClass 是 Java 堆上限（几十到几百 MB），
        // 与推理用的原生内存无关——16GB 旗舰机 memClass 也常低于 192MB，
        // 之前用它判定会把大内存真机误判成低内存，走 fp16+2 线程的慢速路径。
        val low = isEmulator || totalMem < 4000
        Log.i(TAG, "device: emulator=$isEmulator totalMemMB=$totalMem lowMemory=$low")
        low
    }

    private val isEmulator: Boolean by lazy {
        android.os.Build.FINGERPRINT.contains("generic") ||
            android.os.Build.MODEL.contains("Emulator") ||
            android.os.Build.MODEL.contains("sdk_gphone")
    }

    private val activeModelFileName: String by lazy {
        // fp32 模型 231MB，CPU EP 加载后 RSS 约 1.2GB，低内存设备直接被系统 OOM 杀掉；
        // fp16 模型 122MB，权重减半，低内存设备强制使用。
        // 按内存档位选首选模型；若首选未下载但另一档位已存在，直接复用已有模型，
        // 避免让用户为几百 MB 的重复下载干等（音质差异可接受）。
        val fp32Ready = File(modelsDir, fp32ModelFileName).let { it.exists() && it.length() > 0 }
        val fp16Ready = File(modelsDir, fp16ModelFileName).let { it.exists() && it.length() > 0 }
        when {
            !isLowMemoryDevice && fp32Ready -> fp32ModelFileName
            isLowMemoryDevice && fp16Ready -> fp16ModelFileName
            fp16Ready -> fp16ModelFileName
            fp32Ready -> fp32ModelFileName
            // 都未下载：按内存档位决定要下载哪个
            !isLowMemoryDevice -> fp32ModelFileName
            else -> fp16ModelFileName
        }
    }

    /** 模型已从 APK 分离，统一从 filesDir/models 读取（由 ModelDownloader 首次下载） */
    val modelsDir: File get() = File(context.filesDir, "models")

    /** 当前档位模型是否已就绪（UI 据此决定是否需要先下载模型） */
    fun isModelReady(): Boolean = File(modelsDir, activeModelFileName).let { it.exists() && it.length() > 0 }

    /** 需要的模型文件名（UI 下载卡片展示用） */
    fun requiredModelFileName(): String = activeModelFileName

    /**
     * 模型文件路径：直接指向 filesDir/models/<name>，ONNX Runtime 会 mmap 模型文件。
     * 模型缺失时抛 ModelNotReadyException，由上层引导用户先下载模型。
     */
    private val modelFilePath: String by lazy {
        val target = File(modelsDir, activeModelFileName)
        if (!target.exists() || target.length() == 0L) {
            throw ModelNotReadyException(activeModelFileName)
        }
        target.absolutePath
    }
    private val session: OrtSession by lazy {
        Log.i(TAG, "creating OrtSession model=$activeModelFileName lowMemory=$isLowMemoryDevice")
        val options = OrtSession.SessionOptions().apply {
            // NNAPI 会把整份模型复制进驱动内存（fp32 模型在 2GB 设备上直接触发系统 OOM），
            // 且本模型 92 个卷积里 80 个是 1D 卷积（Demucs 为时域模型），
            // NNAPI/XNNPACK 只加速 2D 卷积，绝大多数算子仍回退 CPU，反而引入分区拷贝开销。
            // 实测结论：全部走 ORT CPU EP 是该模型的最优解，不做 EP 切换。
            // 限制 CPU 线程数：默认按核数开线程，每个线程都有 arena 开销；
            // 大小核手机上 4 线程已能占满超大核+大核，再多反而互相抢占。
            runCatching { setIntraOpNumThreads(4) }
            // ALL_OPT 的整图优化在 x86 模拟器上对 fp16 大模型会卡死（数分钟无进展），
            // 模拟器降为基本优化；真机保持 ALL_OPT。
            setOptimizationLevel(
                if (isEmulator) OrtSession.SessionOptions.OptLevel.BASIC_OPT
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
        sampleRate: Int,
        onSegmentProgress: (suspend (Int, Int) -> Unit)?
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

                // 预计算总段数（用于进度百分比）
                val hop = SEGMENT_SAMPLES - OVERLAP_SAMPLES
                val totalSegments = ((totalFrames + hop - 1) / hop).toInt().coerceAtLeast(1)

            // 输出文件先补齐到与输入等长（后续按帧覆盖）
            outputFile.setLength(totalFrames * CHANNELS * 4L)

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
                onSegmentProgress?.invoke(segmentIndex, totalSegments)
                if (segmentIndex % 10 == 0) {
                    Log.i(TAG, "separate progress: segment=$segmentIndex/$totalSegments frame=$frameStart/$totalFrames")
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
        result.use { outputs ->
            // 注意：Result.get(int) 直接返回 OnnxValue；
            // Kotlin 会把 OnnxValue.getValue() 映射为 .value 属性（返回 float[][][]），
            // 直接 cast 成 OnnxTensor 会抛 ClassCastException（"floatlll cannot be cast..."）。
            val outputTensor = outputs.get(0) as OnnxTensor
            val raw = outputTensor.floatBuffer
            // 输出同样 channel-first [1, 2, N]，转回交错格式
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
