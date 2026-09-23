package com.nora.douyinremover.updater

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * APK 下载器：流式写文件 + Range 断点续传 + 进度回调。
 * 下载到同目录 .tmp，完成后原子 rename，避免半成品被误装。
 */
class ApkDownloader(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
) {
    /**
     * 下载 APK 到 targetFile。若存在同名 .tmp 且服务端支持 Range，则从已下载字节续传。
     * @param onProgress (已下载字节, 总字节；总字节未知为 -1)
     * @return 下载完成的文件
     * @throws DownloadCancelledException 用户取消
     */
    suspend fun download(
        url: String,
        targetFile: File,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false }
    ): File = withContext(Dispatchers.IO) {
        targetFile.parentFile?.mkdirs()
        val tmp = File(targetFile.parentFile, targetFile.name + ".tmp")
        var downloaded = if (tmp.exists()) tmp.length() else 0L

        // 预检总大小
        var totalSize = -1L
        runCatching {
            client.newCall(Request.Builder().url(url).head().build()).execute().use { resp ->
                if (resp.isSuccessful) totalSize = resp.header("Content-Length")?.toLongOrNull() ?: -1L
            }
        }
        if (totalSize in 1 until downloaded) {
            // 远端已变短（新版本资产），续传无意义，重来
            tmp.delete(); downloaded = 0
        }

        val requestBuilder = Request.Builder().url(url).get()
        if (downloaded > 0) requestBuilder.header("Range", "bytes=$downloaded-")

        client.newCall(requestBuilder.build()).execute().use { response ->
            require(response.isSuccessful || response.code == 206) { "下载失败 HTTP ${response.code}" }
            if (response.code == 200) downloaded = 0 // 服务端不支持 Range，从头写
            val body = response.body
            val contentLength = body.contentLength()
            val total: Long = if (response.code == 206 && totalSize > 0) totalSize
                else if (contentLength > 0) contentLength + downloaded else -1L

            body.source().use { source ->
                java.io.FileOutputStream(tmp, downloaded > 0).use { out ->
                    val sink = okio.Buffer()
                    var lastReport = 0L
                    while (true) {
                        if (isCancelled()) throw DownloadCancelledException()
                        val read = source.read(sink, 64 * 1024)
                        if (read == -1L) break
                        sink.writeTo(out)
                        downloaded += read
                        if (downloaded - lastReport > 256 * 1024) {
                            lastReport = downloaded
                            onProgress(downloaded, total)
                        }
                    }
                    out.flush()
                    onProgress(downloaded, total)
                }
            }
        }
        if (targetFile.exists()) targetFile.delete()
        if (!tmp.renameTo(targetFile)) {
            tmp.copyTo(targetFile, overwrite = true)
            tmp.delete()
        }
        targetFile
    }
}

class DownloadCancelledException : Exception("下载已取消")

/** 取消标志句柄：UI 层持有并置 true 即可中断下载 */
class CancelFlag {
    @Volatile
    var cancelled: Boolean = false
    fun isCancelled() = cancelled
}
