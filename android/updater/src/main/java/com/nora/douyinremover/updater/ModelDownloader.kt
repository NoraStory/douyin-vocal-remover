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
class ModelDownloader(
    private val giteeToken: String = BuildConfig.GITEE_TOKEN
) {
    /**
     * 下载模型到 modelsDir。
     * @param asset 模型资产（文件名/URL/大小）
     * @param expectedSha256 期望哈希（hex，null 跳过校验）
     * @param onProgress (已下载字节, 总字节)
     * @param isCancelled 取消标志
     * @return 最终模型文件
     */
    suspend fun download(
        asset: ModelAsset,
        modelsDir: File,
        expectedSha256: String? = null,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false }
    ): File = withContext(Dispatchers.IO) {
        modelsDir.mkdirs()
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

    /** Gitee 附件直链可能需要 token 参数；GitHub release 资产直链匿名可下 */
    private fun buildUrl(url: String): String {
        if (url.contains("gitee.com") && giteeToken.isNotBlank() && !url.contains("access_token=")) {
            val sep = if ("?" in url) "&" else "?"
            return "$url${sep}access_token=$giteeToken"
        }
        return url
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
    }
}
