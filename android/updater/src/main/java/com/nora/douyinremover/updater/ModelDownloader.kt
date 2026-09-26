package com.nora.douyinremover.updater

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * ONNX 模型下载器（模型已从 APK 分离）。
 *
 * 直链方案，不依赖 Release API（Gitee 匿名 API 限流极严，不可用）：
 * 模型资产固定挂在 [UpdateChecker.ModelCatalog.MODELS_TAG] 这个 Release 上，
 * 下载地址为可预测的标准直链：
 *  1. Gitee 分卷（国内直连快）：`{file}.part00/.part01/...`（单文件 100MB 限制），
 *     逐卷下载合并；part00 404 则视为 Gitee 不可用
 *  2. GitHub 完整文件兜底
 * 完整性：合并后校验字节数 + SHA-256（清单随应用内置，模型不变则不变）。
 */
class ModelDownloader {

    /**
     * 下载模型到 modelsDir/<modelFileName>。
     * 源优先级：R2 完整文件（配置了 r2.baseUrl）→ Gitee 分卷合并 → GitHub 完整文件。
     * @param modelFileName 如 "htdemucs_fp32.onnx"（由 separator.requiredModelFileName() 给出）
     * @param onProgress (已下载字节, 总字节)
     * @param isCancelled 取消标志
     * @throws DownloadCancelledException 用户取消
     */
    suspend fun downloadModel(
        modelFileName: String,
        modelsDir: File,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false }
    ): File = withContext(Dispatchers.IO) {
        modelsDir.mkdirs()
        val info = UpdateChecker.ModelCatalog.info(modelFileName)
            ?: throw IllegalArgumentException("未知模型文件: $modelFileName")
        val target = File(modelsDir, modelFileName)

        // 已存在且字节数吻合：无需下载
        if (target.exists() && target.length() == info.sizeBytes) {
            Log.i(TAG, "model already present: $modelFileName")
            return@withContext target
        }

        // 主源：Cloudflare R2（完整文件，无分卷）
        UpdateChecker.ModelCatalog.r2Url(modelFileName)?.let { r2 ->
            try {
                return@withContext downloadFull(modelFileName, r2, modelsDir, info, onProgress, isCancelled)
            } catch (e: DownloadCancelledException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "r2 model download failed: ${e.message}, fallback")
            }
        }

        try {
            downloadFromParts(modelFileName, modelsDir, info, onProgress, isCancelled)
        } catch (e: PartsUnavailableException) {
            Log.i(TAG, "gitee parts unavailable (${e.message}), fallback to github")
            downloadFull(
                modelFileName,
                UpdateChecker.ModelCatalog.githubUrl(modelFileName),
                modelsDir, info, onProgress, isCancelled
            )
        }
    }

    // ---- Gitee 分卷 ----

    /** Gitee 分卷不可用（part00 404 等），触发 GitHub 兜底 */
    private class PartsUnavailableException(message: String) : Exception(message)

    private suspend fun downloadFromParts(
        modelFileName: String,
        modelsDir: File,
        info: UpdateChecker.ModelCatalog.ModelInfo,
        onProgress: (Long, Long) -> Unit,
        isCancelled: () -> Boolean
    ): File = withContext(Dispatchers.IO) {
        // 断点续传粒度 = 单个分卷：完成一个分卷落一个 marker 文件（.partNN），
        // 未完成的分卷用 .partNN.dl 续传。全部就绪后合并 + 校验。
        val mergedTmp = File(modelsDir, "$modelFileName.tmp")
        var downloadedTotal = 0L
        var lastReport = 0L
        val partFiles = mutableListOf<File>()

        fun report() {
            if (downloadedTotal - lastReport > 1024 * 1024) {
                lastReport = downloadedTotal
                onProgress(downloadedTotal, info.sizeBytes)
            }
        }

        var index = 0
        while (index <= MAX_PARTS) {
            if (isCancelled()) throw DownloadCancelledException()
            val partName = "$modelFileName.part%02d".format(index)
            val partDone = File(modelsDir, partName)
            if (partDone.exists() && partDone.length() > 0) {
                partFiles.add(partDone)
                downloadedTotal += partDone.length()
                report()
                index++
                continue
            }
            val partUrl = UpdateChecker.ModelCatalog.giteePartUrl(modelFileName, index)
            val partDl = File(modelsDir, "$partName.dl")
            try {
                httpDownloadToFile(
                    url = partUrl, dest = partDl, resume = partDl.exists(),
                    onDelta = { delta ->
                        downloadedTotal += delta
                        report()
                    },
                    isCancelled = isCancelled
                )
            } catch (e: Http404Exception) {
                if (index == 0) {
                    throw PartsUnavailableException("gitee part00 404")
                }
                break // 分卷取完
            }
            if (!partDl.renameTo(partDone)) {
                partDl.copyTo(partDone, overwrite = true)
                partDl.delete()
            }
            partFiles.add(partDone)
            downloadedTotal = partFiles.sumOf { it.length() }
            report()
            index++
        }

        require(partFiles.isNotEmpty()) { "没有下载到任何分卷" }

        // 合并 + 校验 + 原子落位
        FileOutputStream(mergedTmp).use { out ->
            for (pf in partFiles) pf.inputStream().use { it.copyTo(out, 256 * 1024) }
        }
        verify(mergedTmp, info, modelFileName)
        val target = File(modelsDir, modelFileName)
        if (target.exists()) target.delete()
        if (!mergedTmp.renameTo(target)) {
            mergedTmp.copyTo(target, overwrite = true)
            mergedTmp.delete()
        }
        partFiles.forEach { it.delete() }
        onProgress(target.length(), info.sizeBytes)
        Log.i(TAG, "model merged from ${partFiles.size} parts: $modelFileName")
        target
    }

    // ---- 完整文件下载（R2 主源 / GitHub 兜底共用）----

    private suspend fun downloadFull(
        modelFileName: String,
        url: String,
        modelsDir: File,
        info: UpdateChecker.ModelCatalog.ModelInfo,
        onProgress: (Long, Long) -> Unit,
        isCancelled: () -> Boolean
    ): File = withContext(Dispatchers.IO) {
        val target = File(modelsDir, modelFileName)
        val tmp = File(modelsDir, "$modelFileName.dl")
        var have = if (tmp.exists()) tmp.length() else 0L
        if (have >= info.sizeBytes && info.sizeBytes > 0) {
            tmp.delete(); have = 0
        }
        var lastReport = 0L
        var lastError: Exception? = null
        repeat(MAX_RETRIES) { attempt ->
            if (isCancelled()) throw DownloadCancelledException()
            try {
                if (attempt > 0) Log.w(TAG, "model download retry #$attempt")
                have = if (tmp.exists()) tmp.length() else 0L
                lastReport = have
                httpDownloadToFile(
                    url = url, dest = tmp, resume = tmp.exists(),
                    onDelta = { delta ->
                        have += delta
                        if (have - lastReport > 1024 * 1024) {
                            lastReport = have
                            onProgress(have, info.sizeBytes)
                        }
                    },
                    isCancelled = isCancelled
                )
                verify(tmp, info, modelFileName)
                if (target.exists()) target.delete()
                if (!tmp.renameTo(target)) {
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }
                onProgress(target.length(), info.sizeBytes)
                return@withContext target
            } catch (e: DownloadCancelledException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "model download attempt failed: ${e.message}")
            }
        }
        throw lastError ?: IllegalStateException("模型下载失败")
    }

    // ---- HTTP 基础 ----

    private class Http404Exception : Exception("HTTP 404")

    /**
     * 下载 URL 到 dest（支持 Range 续传）。
     * 404 抛 [Http404Exception]；其余非 2xx 抛异常（可重试）。
     */
    private fun httpDownloadToFile(
        url: String,
        dest: File,
        resume: Boolean,
        onDelta: (Long) -> Unit,
        isCancelled: () -> Boolean
    ) {
        UrlGuard.requireSafe(url)
        var have = if (resume && dest.exists()) dest.length() else 0L
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "douyin-vocal-remover")
            if (have > 0) setRequestProperty("Range", "bytes=$have-")
        }
        try {
            val code = conn.responseCode
            when {
                code == HttpURLConnection.HTTP_NOT_FOUND -> throw Http404Exception()
                code == HttpURLConnection.HTTP_OK -> have = 0 // 服务端不支持续传，重头写
                code != HttpURLConnection.HTTP_PARTIAL ->
                    throw IllegalStateException("HTTP $code")
            }
            conn.inputStream.use { input ->
                val out = FileOutputStream(dest, have > 0)
                try {
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        if (isCancelled()) throw DownloadCancelledException()
                        val read = input.read(buffer)
                        if (read == -1) break
                        out.write(buffer, 0, read)
                        have += read
                        onDelta(read.toLong())
                    }
                    out.flush()
                } finally {
                    out.close()
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun verify(file: File, info: UpdateChecker.ModelCatalog.ModelInfo, name: String) {
        require(file.length() == info.sizeBytes) {
            "$name 字节数不符（${file.length()} != ${info.sizeBytes}）"
        }
        val actual = sha256Of(file)
        require(actual.equals(info.sha256, ignoreCase = true)) {
            "$name SHA-256 校验失败，下载已损坏，请重试"
        }
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val TAG = "ModelDownloader"
        const val MAX_RETRIES = 3
        const val MAX_PARTS = 32
    }
}
