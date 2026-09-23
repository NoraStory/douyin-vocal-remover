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
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay

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
    private val webSession = DouyinWebSession(context)

    suspend fun resolve(input: String): List<ResolvedMediaItem> = withContext(Dispatchers.IO) {
        // 匿名会话 cookie 过期且未登录时，先用隐形 WebView 预热（dyparse 方案）。
        // 注意顺序：必须在 ensureBaseCookies 之前检查，否则注册 ttwid 会刷新 savedAt
        // 时间戳，把“过期”状态掩盖掉，预热成为死代码。
        // 预热是尽力而为：WebView provider 异常（模拟器常见）时静默降级，不阻断解析。
        if (cookieStore.isStale() && !cookieStore.isLoggedIn()) {
            Log.i(TAG, "cookies stale, warming up via WebView")
            val warmed = runCatching { webSession.warmUp() }
                .onFailure { Log.w(TAG, "warmUp failed: ${it.message}") }
                .getOrDefault(emptyMap())
            if (warmed.isNotEmpty()) {
                cookieStore.mergeFiltered(warmed)
            } else {
                // 预热失败也刷新时间戳，避免每次解析都卡 25 秒预热
                cookieStore.save(cookieStore.load())
            }
        }

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

    /** 用户在 WebView 中完成验证码/登录后调用：合并收集到的 cookie */
    suspend fun applyWebSessionCookies(cookies: Map<String, String>) {
        withContext(Dispatchers.IO) {
            if (cookies.isNotEmpty()) {
                cookieStore.mergeFiltered(cookies)
                Log.i(TAG, "applied web session cookies: ${cookies.size} keys, loggedIn=${cookieStore.isLoggedIn()}")
            }
        }
    }

    suspend fun isLoggedIn(): Boolean = withContext(Dispatchers.IO) { cookieStore.isLoggedIn() }

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

    /* ---------- 视频解析（主通道 → 签名刷新 → aid 降级 → 退避重试 → 弹窗） ---------- */

    private suspend fun resolveVideoWithRetry(target: ParsedDouyinTarget): List<ResolvedMediaItem> {
        if (target.type == DouyinTargetType.NOTE || !target.id.matches(Regex("^[0-9]+$"))) {
            return emptyList()
        }
        val id = target.id
        val cookie = cookieStore.load()

        // 尝试链：aid=6383 → 刷新 __ac_signature 后 6383 → aid=1128（图文被过滤但视频可下）
        val chain = listOf<suspend () -> List<ResolvedMediaItem>>(
            { attemptDetail(id, "6383", cookie) },
            {
                acquireAcSignature("https://www.douyin.com/video/$id")
                attemptDetail(id, "6383", cookieStore.load())
            },
            { attemptDetail(id, "1128", cookieStore.load()) },
        )
        for (attempt in chain) {
            val items = runCatching { attempt() }.getOrNull()
            if (!items.isNullOrEmpty()) return items
        }

        // 非 Argus 的瞬时限速：1/2/5 秒退避重试主通道（jiji262 实测冷却后恢复）
        for (waitMs in longArrayOf(1_000, 2_000, 5_000)) {
            delay(waitMs)
            val items = runCatching { attemptDetail(id, "6383", cookieStore.load()) }.getOrNull()
            if (!items.isNullOrEmpty()) return items
        }

        Log.w(TAG, "detail blocked by all channels, need user verification")
        throw NeedVerificationException("触发抖音风控，请完成验证码或登录")
    }

    private suspend fun resolveUserWithRetry(target: ParsedDouyinTarget): List<ResolvedMediaItem> {
        val cookie = cookieStore.load()
        val chain = listOf<suspend () -> List<ResolvedMediaItem>>(
            { attemptUser(target, "6383", cookie) },
            {
                acquireAcSignature("https://www.douyin.com/user/${target.id}")
                attemptUser(target, "6383", cookieStore.load())
            },
            { attemptUser(target, "1128", cookieStore.load()) },
        )
        for (attempt in chain) {
            val items = runCatching { attempt() }.getOrNull()
            if (!items.isNullOrEmpty()) return items
        }
        for (waitMs in longArrayOf(1_000, 2_000, 5_000)) {
            delay(waitMs)
            val items = runCatching { attemptUser(target, "6383", cookieStore.load()) }.getOrNull()
            if (!items.isNullOrEmpty()) return items
        }
        Log.w(TAG, "post blocked by all channels, need user verification")
        throw NeedVerificationException("触发抖音风控，请完成验证码或登录")
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

    private suspend fun attemptDetail(id: String, aid: String, cookie: String): List<ResolvedMediaItem> {
        val signed = signedDetailRequest(id, aid, cookie)
        val response = executeJson<AwemeDetailResponse>(
            signed.url,
            cookie,
            "https://www.douyin.com/video/$id",
            signed.extraHeaders
        )
        val item = response.awemeDetail
        Log.i(TAG, "attemptDetail aid=$aid id=$id found=${item != null} bitRates=${item?.video?.bitRate?.size ?: 0}")
        if (item != null) return mapAwemeItem(item)
        return emptyList()
    }

    private suspend fun attemptUser(target: ParsedDouyinTarget, aid: String, cookie: String): List<ResolvedMediaItem> {
        val signed = signedPostRequest(target.id, aid, System.currentTimeMillis(), cookie)
        val response = executeJson<AwemePostResponse>(
            signed.url,
            cookie,
            "https://www.douyin.com/user/${target.id}",
            signed.extraHeaders
        )
        Log.i(TAG, "attemptUser aid=$aid id=${target.id} items=${response.awemeList.size} hasMore=${response.hasMore}")
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
        // 每个码率取第一个 URL（url_list 其余是同内容的 CDN 镜像，展开会造成重复结果）
        item.video?.bitRate?.forEach { bitRate ->
            bitRate.playAddr.urlList.firstOrNull()?.let { url ->
                result += ResolvedMediaItem(
                    title = item.desc,
                    url = normalizeUrl(url),
                    width = bitRate.playAddr.width,
                    height = bitRate.playAddr.height,
                    isWatermarkFree = bitRate.downloadAddr != null,
                    qualityLabel = "${bitRate.playAddr.width}x${bitRate.playAddr.height}"
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

    /* ---------- 签名与请求构建（指纹参数 + a_bogus + x-secsdk-web-signature） ---------- */

    private data class SignedRequest(
        val url: String,
        val extraHeaders: Map<String, String>
    )

    /**
     * 构建 detail 请求：完整浏览器指纹参数 + msToken + verifyFp/fp + a_bogus，
     * 若 cookie 中有 UIFID 系字段再叠加 x-secsdk-web-signature（Argus 门禁签名）。
     * 参考 Evil0ctal/Douyin_TikTok_Download_API 与 NanmiCoder/MediaCrawler 的 2026-09 修复。
     */
    private suspend fun signedDetailRequest(id: String, aid: String, cookie: String): SignedRequest {
        val pairs = basePairs(aid)
        pairs["aweme_id"] = id
        return buildSignedRequest(pairs, cookie, "https://www.douyin.com/aweme/v1/web/aweme/detail/")
    }

    private suspend fun signedPostRequest(secUserId: String, aid: String, maxCursor: Long, cookie: String): SignedRequest {
        val pairs = basePairs(aid)
        pairs["sec_user_id"] = secUserId
        pairs["max_cursor"] = maxCursor.toString()
        pairs["count"] = "18"
        return buildSignedRequest(pairs, cookie, "https://www.douyin.com/aweme/v1/web/aweme/post/")
    }

    /** 指纹参数集：与请求 UA（Chrome 130 / Windows）保持一致，否则是免费的风控信号 */
    private fun basePairs(aid: String): LinkedHashMap<String, String> = linkedMapOf(
        "device_platform" to "webapp",
        "aid" to aid,
        "channel" to "channel_pc_web",
        "pc_client_type" to "1",
        "version_code" to "290100",
        "version_name" to "29.1.0",
        "cookie_enabled" to "true",
        "screen_width" to "1920",
        "screen_height" to "1080",
        "browser_language" to "zh-CN",
        "browser_platform" to "Win32",
        "browser_name" to "Chrome",
        "browser_version" to "130.0.0.0",
        "browser_online" to "true",
        "engine_name" to "Blink",
        "engine_version" to "130.0.0.0",
        "os_name" to "Windows",
        "os_version" to "10",
        "cpu_core_num" to "12",
        "device_memory" to "8",
        "platform" to "PC",
        "downlink" to "10",
        "effective_type" to "4g",
        "round_trip_time" to "0"
    )

    private fun cookieMap(cookie: String): Map<String, String> =
        cookie.split(';').mapNotNull { part ->
            part.trim().split('=', limit = 2).takeIf { it.size == 2 }
                ?.let { it[0].trim() to it[1].trim() }
        }.toMap()

    private suspend fun buildSignedRequest(
        pairs: LinkedHashMap<String, String>,
        cookie: String,
        endpoint: String
    ): SignedRequest {
        val cookies = cookieMap(cookie)

        // msToken：真实流程经 webmssdk 获取；失败时假 token 同样可用（f2 的做法）
        pairs["msToken"] = cookies["msToken"] ?: randomMsToken()

        // verifyFp / fp 必须同源且来自 s_v_web_id cookie（自造会被判 Signature Not Found）
        val webId = cookies["s_v_web_id"]
        if (!webId.isNullOrBlank()) {
            pairs["verifyFp"] = webId
            pairs["fp"] = webId
        }

        // a_bogus：输入是编码后的查询串（不含 a_bogus）
        val bogusInput = encodePairs(pairs)
        val bogus = signatureEngine.aBogus(bogusInput, "", USER_AGENT_2)
        pairs["a_bogus"] = bogus

        // x-secsdk-web-signature：md5(f"{uifid}_{timestamp}_{SALT}_{query}")
        // 仅在拿到 UIFID 系 cookie 时可用（WebView 预热/验证后通常会有 UIFID_TEMP）
        val uifid = UIFID_COOKIE_NAMES.firstNotNullOfOrNull { cookies[it] }
        val headers = LinkedHashMap<String, String>()
        // MediaCrawler 2026-09-19 修复：Argus 前置校验要求该头存在；网关暂不校验值
        headers["x-tt-argus"] = "1"
        if (!uifid.isNullOrBlank()) {
            val covered = LinkedHashMap(pairs)
            if (!covered.containsKey("uifid")) covered["uifid"] = uifid
            val stamp = (System.currentTimeMillis() / 1000).toString()
            covered["timestamp"] = stamp
            val query = encodePairs(covered)
            val signature = md5("${uifid}_${stamp}_${SALT}_${query}")
            headers["uifid"] = uifid
            headers["x-secsdk-web-signature"] = signature
            headers["x-secsdk-web-expire"] = stamp
            Log.i(TAG, "websign applied, uifid=${uifid.take(12)}...")
            return SignedRequest("$endpoint?$query&x-secsdk-web-signature=$signature", headers)
        }

        Log.w(TAG, "no uifid cookie, websign skipped (x-tt-argus only)")
        return SignedRequest("$endpoint?${encodePairs(pairs)}", headers)
    }

    /** JS URLSearchParams/Python quote(safe='*-._') 语义的百分号编码 */
    private fun encodePairs(pairs: Map<String, String>): String =
        pairs.entries.joinToString("&") { "${percentEncode(it.key)}=${percentEncode(it.value)}" }

    private fun percentEncode(value: String): String {
        val sb = StringBuilder(value.length)
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val c = byte.toInt() and 0xFF
            val isAlphaNum = (c in 'a'.code..'z'.code) || (c in 'A'.code..'Z'.code) || (c in '0'.code..'9'.code)
            if (isAlphaNum || c == '*'.code || c == '-'.code || c == '.'.code || c == '_'.code || c == '~'.code) {
                sb.append(c.toChar())
            } else {
                sb.append('%')
                sb.append(HEX_DIGITS[c shr 4])
                sb.append(HEX_DIGITS[c and 0xF])
            }
        }
        return sb.toString()
    }

    private fun md5(input: String): String =
        MessageDigest.getInstance("MD5")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun randomMsToken(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        val random = SecureRandom()
        val sb = StringBuilder(128)
        repeat(128) { sb.append(chars[random.nextInt(chars.length)]) }
        return sb.toString()
    }

    private suspend inline fun <reified T> executeJson(
        url: String,
        cookie: String,
        referer: String,
        extraHeaders: Map<String, String> = emptyMap()
    ): T {
        val builder = Request.Builder()
            .url(url)
            .header("Referer", referer)
            .header("Accept", "application/json, text/plain, */*")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .header("User-Agent", USER_AGENT_2)
            .header("Cookie", cookie)
        extraHeaders.forEach { (name, value) -> builder.header(name, value) }
        val body = client.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            Log.i(TAG, "executeJson ${response.code} ${url.substringBefore('?')} bodyLen=${text.length}")
            if (!response.isSuccessful) throw ApiBlockedException(response.code, text)
            if (text.isBlank()) throw ApiBlockedException(response.code, "")
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
        /** 与指纹参数集（Chrome 130 / Windows）保持一致 */
        const val USER_AGENT_2 =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

        /**
         * x-secsdk-web-signature 的项目盐（douyin_web，project-id=34）。
         * 签名为协议兼容要求（服务端只校验 MD5），非完整性保护用途。
         */
        const val SALT = "A96D855A08C0A9707F8BEF0D9A527E4E"

        /** SDK 接受的访客 ID cookie 拼写顺序 */
        val UIFID_COOKIE_NAMES = listOf("uifid", "uifid_temp", "UIFID", "UIFID_TEMP", "UIFIDTEMP")

        val HEX_DIGITS = "0123456789ABCDEF".toCharArray()

        fun createDefaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
}

/**
 * 请求被抖音边缘网关拦截（403 ArgusSecurityPlugin / 限速 / 空响应）。
 * statusCode + body 供上层分类：Argus 拒绝（签名门禁）与瞬时限速需要不同策略。
 */
class ApiBlockedException(val statusCode: Int, val body: String) : IOException("接口被拦截: $statusCode")
