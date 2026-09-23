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
 * ONNX 模型下载器（模型已从 APK 分离）：
 * - 双源：Gitee 优先，失败切 GitHub（资产地址来自 Release assets）
 * - 断点续传（Range）+ 3 次重试
 * - SHA-256 校验（校验和由 release 的 sha256 清单提供，可选）
 * - 下载到 filesDir/models/<name>.tmp，校验通过后原子 rename
 */
class ModelDownloader {
    /**
     * 下载模型到 modelsDir。
     * @param asset 模型资产（文件名/URL/大小）。Gitee 单文件限 100MB，大模型以
     *   `<name>.onnx.part00/.part01/...` 分卷发布：传入 part00 资产时自动下载
     *   全部分卷并按序合并成 `<name>.onnx`。
     * @param allAssets 同一 release 的全部模型资产（用于发现分卷；单文件下载可传空）
     * @param expectedSha256 期望哈希（hex，null 跳过校验）
     * @param onProgress (已下载字节, 总字节)
     * @param isCancelled 取消标志
     * @return 最终模型文件
     */
    suspend fun download(
        asset: ModelAsset,
        modelsDir: File,
        allAssets: List<ModelAsset> = emptyList(),
        expectedSha256: String? = null,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false }
    ): File = withContext(Dispatchers.IO) {
        modelsDir.mkdirs()

        // 分卷资产（xxx.onnx.part00）：还原目标名 xxx.onnx，收集同前缀全部分卷
        val isSplit = asset.fileName.endsWith(".part00") ||
            Regex("\\.part\\d+$").containsMatchIn(asset.fileName)
        return@withContext if (isSplit) {
            val baseName = asset.fileName.substringBeforeLast(".part")
            val parts = allAssets
                .filter { it.fileName.startsWith("$baseName.part") }
                .sortedBy { it.fileName }
            require(parts.isNotEmpty()) { "分卷资产缺失：${asset.fileName}" }
            downloadMerged(baseName, parts, modelsDir, expectedSha256, onProgress, isCancelled)
        } else {
            downloadSingle(asset, modelsDir, expectedSha256, onProgress, isCancelled)
        }
    }

    /** 下载分卷并按序合并为 baseName */
    private suspend fun downloadMerged(
        baseName: String,
        parts: List<ModelAsset>,
        modelsDir: File,
        expectedSha256: String?,
        onProgress: (Long, Long) -> Unit,
        isCancelled: () -> Boolean
    ): File = withContext(Dispatchers.IO) {
        val target = File(modelsDir, baseName)
        val totalSize = parts.sumOf { it.size }.takeIf { it > 0 } ?: -1L
        var downloadedTotal = 0L

        // 已完整存在则跳过
        if (target.exists() && target.length() > 0 &&
            (totalSize <= 0 || target.length() == totalSize)
        ) {
            Log.i(TAG, "model already present: $baseName")
            return@withContext target
        }

        // 各分卷先落到独立 tmp（支持单卷断点续传），全部就绪后合并
        val partFiles = mutableListOf<File>()
        try {
            for (part in parts) {
                if (isCancelled()) throw DownloadCancelledException()
                val partFile = downloadSingle(part, modelsDir, expectedSha256 = null,
                    onProgress = { done, _ ->
                        onProgress(downloadedTotal + done, totalSize)
                    },
                    isCancelled = isCancelled)
                partFiles.add(partFile)
                downloadedTotal += partFile.length()
            }

            // 合并：顺序拼接进最终 tmp，再原子 rename
            val mergedTmp = File(modelsDir, "$baseName.tmp")
            java.io.FileOutputStream(mergedTmp).use { out ->
                for (pf in partFiles) {
                    pf.inputStream().use { it.copyTo(out, 256 * 1024) }
                }
            }
            if (expectedSha256 != null) {
                val actual = sha256Of(mergedTmp)
                require(actual.equals(expectedSha256, ignoreCase = true)) {
                    "SHA-256 mismatch for $baseName"
                }
            }
            if (target.exists()) target.delete()
            if (!mergedTmp.renameTo(target)) {
                mergedTmp.copyTo(target, overwrite = true)
                mergedTmp.delete()
            }
            // 清理分卷
            partFiles.forEach { it.delete() }
            onProgress(target.length(), target.length())
            target
        } finally {
            // 合并失败时也清理已下载分卷（下次重来）
            if (!target.exists()) partFiles.forEach { runCatching { it.delete() } }
        }
    }

    /** 单文件下载（断点续传 + 重试 + SHA-256） */
    private suspend fun downloadSingle(
        asset: ModelAsset,
        modelsDir: File,
        expectedSha256: String?,
        onProgress: (Long, Long) -> Unit,
        isCancelled: () -> Boolean
    ): File = withContext(Dispatchers.IO) {
        val target = File(modelsDir, asset.fileName)
        if (target.exists() && target.length() == asset.size && asset.size > 0) {
            Log.i(TAG, "model already present: ${asset.fileName}")
            return@withContext target
        }

        val tmp = File(modelsDir, asset.fileName + ".tmp")
        var lastError: Exception? = null
        repeat(MAX_RETRIES) { attempt ->
            if (isCancelled()) throw DownloadCancelledException()
            try {
                if (attempt > 0) {
                    Log.w(TAG, "model download retry #$attempt for ${asset.fileName}")
                }
                downloadOnce(asset, tmp, onProgress, isCancelled)
                if (expectedSha256 != null) {
                    val actual = sha256Of(tmp)
                    require(actual.equals(expectedSha256, ignoreCase = true)) {
                        "SHA-256 mismatch for ${asset.fileName}"
                    }
                }
                if (target.exists()) target.delete()
                if (!tmp.renameTo(target)) {
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }
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

    private fun downloadOnce(
        asset: ModelAsset,
        tmp: File,
        onProgress: (Long, Long) -> Unit,
        isCancelled: () -> Boolean
    ) {
        var downloaded = if (tmp.exists()) tmp.length() else 0L
        val url = buildUrl(asset.url)
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            if (downloaded > 0) setRequestProperty("Range", "bytes=$downloaded-")
        }
        conn.connect()
        val code = conn.responseCode
        require(code == HttpURLConnection.HTTP_OK || code == HttpURLConnection.HTTP_PARTIAL) {
            "HTTP $code"
        }
        if (code == HttpURLConnection.HTTP_OK) downloaded = 0 // 无续传支持，重头写

        val total = if (code == 206) {
            val range = conn.getHeaderField("Content-Range")
            range?.substringAfterLast('/')?.toLongOrNull() ?: -1L
        } else {
            conn.contentLengthLong.takeIf { it > 0 }?.let { it + downloaded } ?: -1L
        }

        conn.inputStream.use { input ->
            FileOutputStream(tmp, downloaded > 0).use { out ->
                val buffer = ByteArray(128 * 1024)
                var lastReport = 0L
                while (true) {
                    if (isCancelled()) {
                        conn.disconnect()
                        throw DownloadCancelledException()
                    }
                    val read = input.read(buffer)
                    if (read == -1) break
                    out.write(buffer, 0, read)
                    downloaded += read
                    if (downloaded - lastReport > 1024 * 1024) {
                        lastReport = downloaded
                        onProgress(downloaded, total)
                    }
                }
                out.flush()
                onProgress(downloaded, total)
            }
        }
        conn.disconnect()
    }

    /** 公开仓库的附件直链匿名可下，无需令牌（令牌编译进 APK 会被反编译提取） */
    private fun buildUrl(url: String): String = url

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
    }
}
