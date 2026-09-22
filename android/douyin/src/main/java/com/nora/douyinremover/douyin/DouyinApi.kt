package com.nora.douyinremover.douyin

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * 抖音解析，流程参考 48tools（github.com/duan602728596/48tools）：
 *
 * 1. 注册 ttwid（ttwid.bytedance.com/union/register）
 * 2. 直接调 detail/post API（cookie: ttwid + passport_csrf_token）
 * 3. 若被风控（403/验证码页），请求一次目标页 HTML 拿 __ac_nonce，
 *    用本地 acrawler SDK 计算 __ac_signature 存入 cookie 后重试 API
 * 4. 仍失败则解析页面 HTML 内嵌的 RENDER_DATA 兜底
 */
class DouyinApi(
    context: Context,
    private val client: OkHttpClient = createDefaultClient()
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val signatureEngine = JsSignatureEngine(context)
    private val cookieStore = DouyinCookieStore(context)

    suspend fun resolve(input: String): List<ResolvedMediaItem> = withContext(Dispatchers.IO) {
        ensureBaseCookies()

        // 解析分享文本/链接；短链（v.douyin.com）会被解析成 VIDEO + 原始短链 URL
        var target = DouyinTargetParser().parse(input) ?: return@withContext emptyList()

        // 短链场景：target.id 是 v.douyin.com 短链（非纯数字），需先跟随重定向拿到真实页面地址
        if (target.type == DouyinTargetType.VIDEO && !target.id.matches(Regex("^[0-9]+$"))) {
            val redirectedUrl = followRedirect(target.id)
            target = DouyinTargetParser().parse(redirectedUrl)
                ?: throw IOException("短链解析失败")
        }

        when (target.type) {
            DouyinTargetType.VIDEO,
            DouyinTargetType.NOTE -> resolveVideoWithRetry(target)
            DouyinTargetType.USER -> resolveUserWithRetry(target)
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

    /* ---------- 基础 cookie ---------- */

    private suspend fun ensureBaseCookies() {
        val cookie = cookieStore.load()
        if (!cookie.contains("ttwid=", ignoreCase = true)) {
            registerTtwid()
        }
        if (!cookie.contains("passport_csrf_token=", ignoreCase = true)) {
            // 48tools 默认生成 32 位随机 passport_csrf_token
            cookieStore.saveHeaderValue("passport_csrf_token=${randomString(32)}")
            cookieStore.saveHeaderValue("passport_csrf_token_default=${randomString(32)}")
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
        Log.i(TAG, "registerTtwid done, cookie=${cookieStore.load().take(80)}")
    }

    /* ---------- 视频解析（接口优先 → 签名重试 → HTML 兜底） ---------- */

    private suspend fun resolveVideoWithRetry(target: ParsedDouyinTarget): List<ResolvedMediaItem> {
        if (target.type == DouyinTargetType.NOTE || !target.id.matches(Regex("^[0-9]+$"))) {
            return emptyList()
        }
        // 第一次：直接调接口
        val first = runCatching { resolveDetail(target, cookieStore.load()) }.getOrNull()
        if (!first.isNullOrEmpty()) return first

        // 第二次：请求视频页拿 __ac_nonce，本地计算 __ac_signature 后重试
        Log.i(TAG, "detail first attempt failed, acquiring __ac_signature")
        acquireAcSignature("https://www.douyin.com/video/${target.id}")
        val second = runCatching { resolveDetail(target, cookieStore.load()) }.getOrNull()
        if (!second.isNullOrEmpty()) return second

        // 第三次：解析页面 HTML 里的 RENDER_DATA
        Log.i(TAG, "detail second attempt failed, falling back to page HTML")
        return resolveFromPageHtml("https://www.douyin.com/video/${target.id}")
    }

    private suspend fun resolveUserWithRetry(target: ParsedDouyinTarget): List<ResolvedMediaItem> {
        // 第一次：直接调接口
        val first = runCatching { resolveUser(target, cookieStore.load()) }.getOrNull()
        if (!first.isNullOrEmpty()) return first

        // 第二次：签名重试
        Log.i(TAG, "post first attempt failed, acquiring __ac_signature")
        acquireAcSignature("https://www.douyin.com/user/${target.id}")
        val second = runCatching { resolveUser(target, cookieStore.load()) }.getOrNull()
        if (!second.isNullOrEmpty()) return second

        // 第三次：页面 HTML 兜底
        Log.i(TAG, "post second attempt failed, falling back to page HTML")
        return resolveFromPageHtml("https://www.douyin.com/user/${target.id}")
    }

    /**
     * 请求一次页面 HTML：WAF 会通过 Set-Cookie 下发 __ac_nonce，
     * 用本地 acrawler SDK 计算 __ac_signature 一并存入 cookie（等价于浏览器自动 reload 的行为）。
     * 注意：必须裸请求（不带 ttwid），带 ttwid 时 WAF 直接返回 acrawler JS 页而不下发 nonce。
     */
    private suspend fun acquireAcSignature(pageUrl: String) {
        val request = Request.Builder()
            .url(pageUrl)
            .header("User-Agent", USER_AGENT_2)
            .header("Cookie", "__ac_referer=__ac_blank")
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            val setCookies = response.headers("Set-Cookie")
            Log.i(
                TAG,
                "acquireAcSignature ${response.code} bodyLen=${body.length} " +
                    "isCaptcha=${body.contains("验证码中间页")} setCookieCount=${setCookies.size} " +
                    "setCookieHead=${setCookies.firstOrNull()?.take(60)}"
            )
            var nonce: String? = null
            setCookies.forEach { value ->
                if (value.startsWith("__ac_nonce=")) {
                    nonce = value.substringBefore(';').substringAfter("__ac_nonce=")
                }
                cookieStore.saveHeaderValue(value)
            }
            if (nonce == null) {
                // 有时 nonce 不在 Set-Cookie 而在页面里
                Regex("__ac_nonce[\"'=:\\s]+([0-9a-f]+)").find(body)?.let { nonce = it.groupValues[1] }
            }
            if (nonce != null) {
                val signature = signatureEngine.acrawlerSign("", nonce!!)
                cookieStore.saveHeaderValue("__ac_nonce=$nonce")
                cookieStore.saveHeaderValue("__ac_signature=$signature")
                Log.i(TAG, "acquired __ac_signature=${signature.take(24)}...")
            } else {
                Log.w(TAG, "no __ac_nonce found on page")
            }
        }
    }

    /* ---------- API 请求 ---------- */

    private suspend fun resolveDetail(target: ParsedDouyinTarget, cookie: String): List<ResolvedMediaItem> {
        val query = signedDetailQuery(target.id)
        val url = "https://www.douyin.com/aweme/v1/web/aweme/detail/?$query"
        val response = executeJson<AwemeDetailResponse>(
            url,
            cookie,
            "https://www.douyin.com/video/${target.id}"
        )
        val item = response.awemeDetail
        Log.i(TAG, "resolveDetail id=${target.id} found=${item != null} bitRates=${item?.video?.bitRate?.size ?: 0}")
        if (item != null) return mapAwemeItem(item)
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
        Log.i(TAG, "resolveUser id=${target.id} items=${response.awemeList.size} hasMore=${response.hasMore}")
        return response.awemeList.flatMap(::mapAwemeItem)
    }

    /** 页面 HTML 兜底：解析 RENDER_DATA 里的视频/图集数据 */
    private suspend fun resolveFromPageHtml(pageUrl: String): List<ResolvedMediaItem> {
        val request = Request.Builder()
            .url(pageUrl)
            .header("User-Agent", USER_AGENT_2)
            .header("Cookie", cookieStore.load())
            .build()
        client.newCall(request).execute().use { response ->
            val html = response.body?.string().orEmpty()
            Log.i(TAG, "resolveFromPageHtml ${response.code} bodyLen=${html.length}")
            if (!response.isSuccessful || html.contains("验证码中间页")) {
                throw IOException("触发风控验证码，请稍后重试")
            }
            return parseRenderData(html)
        }
    }

    private fun parseRenderData(html: String): List<ResolvedMediaItem> {
        val decoded = runCatching {
            java.net.URLDecoder.decode(html, StandardCharsets.UTF_8.toString())
        }.getOrDefault(html)
        // 从 RENDER_DATA JSON 里提取 bit_rate 的 url_list
        val items = mutableListOf<ResolvedMediaItem>()
        val descMatch = Regex("\"desc\":\"([^\"]{0,120}?)\"").find(decoded)
        val title = descMatch?.groupValues?.get(1) ?: ""
        Regex("\\{\"url_list\":\\[(\"[^\"]+\"[^\\]]*)\\],\"width\":(\\d+),\"height\":(\\d+)\\}").findAll(decoded)
            .forEach { m ->
                val urls = Regex("\"(https?:[^\"]+)\"").findAll(m.groupValues[1])
                    .map { it.groupValues[1].replace("\\u002F", "/").replace("\\/", "/") }
                    .toList()
                val w = m.groupValues[2].toIntOrNull()
                val h = m.groupValues[3].toIntOrNull()
                urls.firstOrNull()?.let { url ->
                    items += ResolvedMediaItem(
                        title = title,
                        url = normalizeUrl(url),
                        width = w,
                        height = h,
                        qualityLabel = "${w ?: 0}x${h ?: 0}"
                    )
                }
            }
        Log.i(TAG, "parseRenderData items=${items.size}")
        return items
    }

    /* ---------- 数据映射 ---------- */

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

    /* ---------- 签名与工具 ---------- */

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
            .header("Accept", "application/json, text/plain, */*")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .header("User-Agent", USER_AGENT_2)
            .header("Cookie", cookie)
            .build()
        val body = client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            Log.i(TAG, "executeJson ${response.code} ${url.substringBefore('?')} bodyLen=${text.length}")
            if (!response.isSuccessful) throw IOException("接口请求失败: ${response.code}")
            if (text.isBlank()) throw IOException("接口响应为空（可能被风控拦截）")
            text
        }
        return try {
            json.decodeFromString(body)
        } catch (e: SerializationException) {
            Log.e(TAG, "JSON 解析失败: ${e.message?.take(300)}; body head=${body.take(200)}")
            throw IOException("响应解析失败: ${e.message?.take(120)}")
        }
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

    private fun randomString(length: Int): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        val random = SecureRandom()
        val sb = StringBuilder(length)
        repeat(length) { sb.append(chars[random.nextInt(chars.length)]) }
        return sb.toString()
    }

    private companion object {
        const val TAG = "DouyinApi"
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
