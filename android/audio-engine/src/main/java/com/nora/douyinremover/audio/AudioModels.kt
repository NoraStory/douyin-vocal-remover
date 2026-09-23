package com.nora.douyinremover.audio

/**
 * 处理流水线阶段（用于 UI 步骤可视化）。
 * @param weight 该阶段在总进度中的权重（0-100 区间分配）
 */
enum class ProcessStage(val label: String, val weight: Int) {
    /** 提取音频（ffmpeg 解码为 PCM） */
    EXTRACT("提取音频", 10),

    /** AI 分离人声（分段推理，占比最大） */
    SEPARATE("AI 分离人声", 75),

    /** 编码输出格式（MP3/WAV/FLAC） */
    ENCODE("编码导出", 10),

    /** 收尾（静音裁剪或复制文件） */
    FINALIZE("收尾处理", 5)
}

/**
 * 进度回调数据。
 * @param stage 当前阶段
 * @param stageProgress 当前阶段内进度 0f-1f（无法精确计算的阶段传 -1f 表示不确定）
 * @param detail 附加说明（如 "第 3/25 段"）
 */
data class ProcessProgress(
    val stage: ProcessStage,
    val stageProgress: Float,
    val detail: String = ""
) {
    /** 全局进度 0-100 */
    val overallPercent: Int
        get() {
            val before = ProcessStage.entries.takeWhile { it.ordinal < stage.ordinal }
                .sumOf { it.weight }
            val clamped = stageProgress.coerceIn(0f, 1f)
            return (before + stage.weight * clamped).toInt().coerceIn(0, 100)
        }
}

data class SeparationRequest(
    val inputPath: String,
    val outputPath: String,
    val outputFormat: String = "mp3",
    val bitrateKbps: Int = 320,
    val silenceThresholdDb: Float = -55f,
    /** 进度回调（在后台线程调用） */
    val onProgress: ((ProcessProgress) -> Unit)? = null
)

data class SeparationResult(
    val outputPath: String,
    val sampleRate: Int,
    val channels: Int,
    val durationMs: Long
)
