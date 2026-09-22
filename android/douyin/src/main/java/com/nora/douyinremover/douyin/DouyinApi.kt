package com.nora.douyinremover.douyin

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

class DouyinApi(
    context: Context,
    private val client: OkHttpClient = createDefaultClient()
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val signatureEngine = JsSignatureEngine(context)
    private val cookieStore = DouyinCookieStore(context)

    suspend fun resolve(input: String): List<ResolvedMediaItem> = withContext(Dispatchers.IO) {
        // 首次请求前确保已注册 ttwid，否则抖音接口容易被风控拦截
        ensureTtwid()

        // 解析分享文本/链接；短链（v.douyin.com）会被解析成 VIDEO + 原始短链 URL
        var target = DouyinTargetParser().parse(input) ?: return@withContext emptyList()

        // 短链场景：target.id 是 v.douyin.com 短链（非纯数字），需先跟随重定向拿到真实页面地址
        if (target.type == DouyinTargetType.VIDEO && !target.id.matches(Regex("^[0-9]+$"))) {
            val redirectedUrl = followRedirect(target.id)
            target = DouyinTargetParser().parse(redirectedUrl)
                ?: throw IOException("短链解析失败")
        }

        val cookie = cookieStore.load()
        when (target.type) {
            DouyinTargetType.VIDEO,
            DouyinTargetType.NOTE -> resolveDetail(target, cookie)
            DouyinTargetType.USER -> resolveUser(target, cookie)
        }
    }

    private suspend fun ensureTtwid() {
        val cookie = cookieStore.load()
        if (!cookie.contains("ttwid=", ignoreCase = true)) {
            registerTtwid()
        }
    }

    suspend fun registerTtwid() {
        val request = Request.Builder()
            .url("https://ttwid.bytedance.com/ttwid/union/register/")
            .post(
                """{"region":"union","aid":1768,"needFid":false,"service":"www.ixigua.com","migrate_info":{"ticket":"","source":"source"},"cbUrlProtocol":"https","union":true}"""
                    .toRequestBody("application/json".toMediaType())
            )
            .header("User-Agent", USER_AGENT_2)
            .build()
        client.newCall(request).execute().use { response ->
            response.headers("Set-Cookie").forEach { cookieStore.saveHeaderValue(it) }
        }
    }

    suspend fun download(url: String, headers: Map<String, String>): ByteArray = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(url).header("Referer", "https://www.douyin.com/")
        headers.forEach { (name, value) -> builder.header(name, value) }
        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("下载失败: ${response.code}")
            response.body?.bytes() ?: throw IOException("下载内容为空")
        }
    }

    suspend fun followRedirect(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("Host", "www.douyin.com")
            .header("User-Agent", USER_AGENT_2)
            .build()
        client.newCall(request).execute().use { response ->
            val location = response.header("Location")
                ?: extractRedirectFromHtml(response.body?.string().orEmpty())
                ?: throw IOException("短链解析失败")
            resolveRedirectLocation(url, location)
        }
    }

    private fun resolveRedirectLocation(baseUrl: String, location: String): String {
        return when {
            location.startsWith("http://") || location.startsWith("https://") -> location
            location.startsWith("//") -> "https:$location"
            location.startsWith("/") -> {
                val origin = runCatching { URL(baseUrl) }.getOrNull()
                    ?.let { "${it.protocol}://${it.host}" } ?: "https://v.douyin.com"
                "$origin$location"
            }
            else -> {
                val origin = runCatching { URL(baseUrl) }.getOrNull()
                    ?.let { "${it.protocol}://${it.host}" } ?: "https://v.douyin.com"
                "$origin/$location"
            }
        }
    }

    private suspend fun resolveDetail(target: ParsedDouyinTarget, cookie: String): List<ResolvedMediaItem> {
        if (target.type == DouyinTargetType.VIDEO && target.id.matches(Regex("^[0-9]+$"))) {
            val query = signedDetailQuery(target.id)
            val url = "https://www.douyin.com/aweme/v1/web/aweme/detail/?$query"
            val response = executeJson<AwemeDetailResponse>(
                url,
                cookie,
                "https://www.douyin.com/video/${target.id}"
            )
            val item = response.awemeDetail
            if (item != null) return mapAwemeItem(item)
        }
        return emptyList()
    }

    private suspend fun resolveUser(target: ParsedDouyinTarget, cookie: String): List<ResolvedMediaItem> {
        val query = signedPostQuery(target.id, System.currentTimeMillis())
        val url = "https://www.douyin.com/aweme/v1/web/aweme/post/?$query"
        val response = executeJson<AwemePostResponse>(
            url,
            cookie,
            "https://www.douyin.com/user/${target.id}"
        )
        return response.awemeList.flatMap(::mapAwemeItem)
    }

    private fun mapAwemeItem(item: AwemeItem): List<ResolvedMediaItem> {
        val result = mutableListOf<ResolvedMediaItem>()
        item.video?.bitRate?.forEach { bitRate ->
            bitRate.playAddr.urlList.forEachIndexed { index, url ->
                result += ResolvedMediaItem(
                    title = item.desc,
                    url = normalizeUrl(url),
                    width = bitRate.playAddr.width,
                    height = bitRate.playAddr.height,
                    isWatermarkFree = bitRate.downloadAddr != null,
                    qualityLabel = "${bitRate.playAddr.width}x${bitRate.playAddr.height} #${index + 1}"
                )
            }
        }
        item.images.forEachIndexed { index, image ->
            image.urlList.firstOrNull()?.let { url ->
                result += ResolvedMediaItem(
                    title = item.desc,
                    url = normalizeUrl(url),
                    width = image.width,
                    height = image.height,
                    isImage = true,
                    qualityLabel = "图片 ${index + 1}"
                )
            }
        }
        return result
    }

    private suspend fun signedDetailQuery(id: String): String {
        val params = "aid=6383&aweme_id=$id&cookie_enabled=true&platform=PC"
        val bogus = signatureEngine.aBogus(params, "", USER_AGENT_2)
        return "$params&a_bogus=${urlEncode(bogus)}"
    }

    private suspend fun signedPostQuery(secUserId: String, maxCursor: Long): String {
        val params = "aid=6383&sec_user_id=${urlEncode(secUserId)}&max_cursor=$maxCursor&count=18&cookie_enabled=true&platform=PC"
        val bogus = signatureEngine.aBogus(params, "", USER_AGENT_2)
        return "$params&a_bogus=${urlEncode(bogus)}"
    }

    private suspend inline fun <reified T> executeJson(url: String, cookie: String, referer: String): T {
        val request = Request.Builder()
            .url(url)
            .header("Referer", referer)
            .header("Host", "www.douyin.com")
            .header("User-Agent", USER_AGENT_2)
            .header("Cookie", cookie)
            .build()
        val body = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("接口请求失败: ${response.code}")
            response.body?.string() ?: throw IOException("接口响应为空")
        }
        return json.decodeFromString(body)
    }

    private fun extractRedirectFromHtml(html: String): String? {
        // <a href="https://www.iesdouyin.com/...">
        Regex("<a href=\"(https?://[^\"]+)\"").find(html)
            ?.groupValues?.get(1)?.let { return it }
        // <meta http-equiv="refresh" content="0;url=https://...">
        Regex("(?i)content=\"\\d+\\s*;\\s*url=([^\"'>]+)\"").find(html)
            ?.groupValues?.get(1)?.trim('\'', '"')?.let { return it }
        return null
    }

    private fun normalizeUrl(url: String): String =
        when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("http://") -> url.replaceFirst("http://", "https://")
            else -> url
        }

    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.toString())

    private companion object {
        const val USER_AGENT_2 =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36 Edg/126.0.0.0"

        fun createDefaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
}
