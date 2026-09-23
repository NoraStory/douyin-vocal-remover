package com.nora.douyinremover.updater

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** 一次版本检测结果 */
data class UpdateInfo(
    /** 远端最新版本号，如 "1.5.0"（tag 的 v 前缀已去掉） */
    val latestVersion: String,
    /** APK 资产下载地址 */
    val apkUrl: String,
    /** APK 资产大小（字节），未知为 -1 */
    val apkSize: Long,
    /** Release 说明（markdown 文本） */
    val releaseNotes: String,
    /** 命中的源："gitee" 或 "github" */
    val source: String
)

/** 模型资产下载地址 */
data class ModelAsset(
    val fileName: String,
    val url: String,
    val size: Long
)

/**
 * 双源版本检测：Gitee 优先（国内直连快），失败/超时自动切 GitHub。
 * Release 资产约定：
 *  - APK：douyin-vocal-remover-v{version}.apk
 *  - 模型（仅首个发布上传，后续复用）：htdemucs_fp32.onnx / htdemucs_fp16.onnx
 */
class UpdateChecker(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun checkLatest(): UpdateInfo? = withContext(Dispatchers.IO) {
        checkGitee() ?: checkGithub()
    }

    /** 拉取模型资产列表（从最新 release 的 assets 中筛选 .onnx） */
    suspend fun fetchModelAssets(): List<ModelAsset> = withContext(Dispatchers.IO) {
        val giteeBody = if (giteeConfigured()) {
            runCatching { fetchJson(giteeLatestUrl()) }.getOrNull()
        } else null
        val githubBody = runCatching { fetchJson(githubLatestUrl()) }.getOrNull()
        fetchReleaseAssets(giteeBody, githubBody)
    }

    // ---- Gitee ----

    private fun giteeConfigured(): Boolean =
        BuildConfig.GITEE_OWNER.isNotBlank() && BuildConfig.GITEE_REPO.isNotBlank()

    private fun giteeLatestUrl(): String =
        // 公开仓库匿名可读，不带令牌（令牌编译进 APK 会被反编译提取）
        "https://gitee.com/api/v5/repos/${BuildConfig.GITEE_OWNER}/${BuildConfig.GITEE_REPO}/releases/latest"

    private fun checkGitee(): UpdateInfo? {
        if (!giteeConfigured()) return null
        return runCatching { parseLatest(fetchJson(giteeLatestUrl()), source = "gitee") }.getOrNull()
    }

    // ---- GitHub ----

    private fun checkGithub(): UpdateInfo? {
        val url = githubLatestUrl()
        return runCatching { parseLatest(fetchJson(url), source = "github") }.getOrNull()
    }

    private fun githubLatestUrl(): String =
        "https://api.github.com/repos/${BuildConfig.GITHUB_OWNER}/${BuildConfig.GITHUB_REPO}/releases/latest"

    // ---- 解析 ----

    private fun fetchJson(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .build()
        client.newCall(request).execute().use { response ->
            require(response.isSuccessful) { "HTTP ${response.code}" }
            return response.body.string()
        }
    }

    /** Gitee 与 GitHub 的 release JSON 结构基本一致：tag_name / body / assets[] */
    private fun parseLatest(body: String, source: String): UpdateInfo? {
        val root = json.parseToJsonElement(body).jsonObject
        val tag = root["tag_name"]?.jsonPrimitive?.content ?: return null
        val version = tag.removePrefix("v")
        val notes = root["body"]?.jsonPrimitive?.content.orEmpty()
        val assets = root["assets"]?.jsonArray ?: return UpdateInfo(version, "", -1, notes, source)
        // 资产名匹配 douyin-vocal-remover-vX.Y.Z.apk（Gitee 字段同样叫 browser_download_url）
        val apkAsset = assets.asSequence()
            .map { it.jsonObject }
            .firstOrNull { (it["name"]?.jsonPrimitive?.content ?: "").endsWith(".apk") }
        return UpdateInfo(
            latestVersion = version,
            apkUrl = apkAsset?.get("browser_download_url")?.jsonPrimitive?.content.orEmpty(),
            apkSize = apkAsset?.get("size")?.jsonPrimitive?.content?.toLongOrNull() ?: -1L,
            releaseNotes = notes,
            source = source
        )
    }

    private fun fetchReleaseAssets(vararg bodies: String?): List<ModelAsset> {
        for (body in bodies) {
            if (body.isNullOrBlank()) continue
            val parsed = runCatching {
                val root = json.parseToJsonElement(body).jsonObject
                val tag = root["tag_name"]?.jsonPrimitive?.content ?: return@runCatching emptyList()
                (root["assets"]?.jsonArray ?: return@runCatching emptyList()).map { el ->
                    val obj = el.jsonObject
                    ModelAsset(
                        fileName = obj["name"]?.jsonPrimitive?.content.orEmpty(),
                        url = obj["browser_download_url"]?.jsonPrimitive?.content.orEmpty(),
                        size = obj["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: -1L
                    )
                }.filter { it.fileName.startsWith("htdemucs_") && it.url.isNotBlank() && tag.isNotBlank() }
            }.getOrDefault(emptyList())
            if (parsed.isNotEmpty()) return parsed
        }
        return emptyList()
    }

    companion object {
        /**
         * 语义化版本比较：返回正数表示 remote 更新，0 相同，负数本地更新。
         * 只比较数字段（1.4.1 vs 1.5.0），忽略预发布后缀。
         */
        fun compareVersions(local: String, remote: String): Int {
            fun parts(v: String) = v.removePrefix("v").substringBefore('-')
                .split('.').map { it.toIntOrNull() ?: 0 }
            val a = parts(local); val b = parts(remote)
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
                if (x != y) return x.compareTo(y)
            }
            return 0
        }

        /**
         * 是否强制更新：落后至少一个大版本（minor +1 及以上，如 1.4.x → 1.5.0）。
         * 仅 patch 落后（1.4.0 → 1.4.1）为普通更新。
         */
        fun isForceUpdate(local: String, remote: String): Boolean {
            fun parts(v: String) = v.removePrefix("v").substringBefore('-')
                .split('.').map { it.toIntOrNull() ?: 0 }
            val a = parts(local); val b = parts(remote)
            val localMinor = a.getOrElse(1) { 0 }
            val remoteMinor = b.getOrElse(1) { 0 }
            val localMajor = a.getOrElse(0) { 0 }
            val remoteMajor = b.getOrElse(0) { 0 }
            return remoteMajor > localMajor || (remoteMajor == localMajor && remoteMinor > localMinor)
        }
    }
}
