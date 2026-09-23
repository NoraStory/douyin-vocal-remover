package com.nora.douyinremover.douyin

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * 抖音 Web 会话预热与 cookie 收集（参考 kd64i/dyparse 的 warmUpDouyinCookies）：
 * 用隐形 WebView 加载抖音页面，让 WAF JS 在真实浏览器环境种下匿名会话 cookie
 * （ttwid / __ac_nonce / __ac_signature / UIFID_TEMP 等），随后 flush 并收集。
 *
 * 关键约定（UA 一致性）：WebView 的 UA 必须与 OkHttp 请求一致，否则风控视为换设备，
 * 新种下的 cookie 立即失效。
 */
class DouyinWebSession(private val context: Context) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 隐形 WebView 预热：加载页面直到出现关键 cookie（UIFID_TEMP / s_v_web_id / odin_tt）
     * 或超时。返回收集到的 cookie map（已按白名单过滤）。
     */
    suspend fun warmUp(pageUrl: String = "https://www.douyin.com/", timeoutMs: Long = 25_000): Map<String, String> =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                var resumed = false
                fun finish(cookies: Map<String, String>) {
                    if (resumed) return
                    resumed = true
                    if (continuation.isActive) continuation.resume(cookies)
                }

                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)

                // WebView provider 初始化失败（模拟器常见）时直接返回已收集的 cookie
                val webView = runCatching { WebView(appContext) }.getOrNull()
                if (webView == null) {
                    Log.w(TAG, "warmUp: WebView unavailable, returning existing cookies")
                    finish(collectCookies())
                    return@suspendCancellableCoroutine
                }
                DouyinWebSession.configure(webView)

                val deadline = System.currentTimeMillis() + timeoutMs
                fun poll(attempt: Int) {
                    val now = System.currentTimeMillis()
                    val collected = runCatching { collectCookies() }
                        .onFailure { Log.w(TAG, "collectCookies failed: ${it.message}") }
                        .getOrDefault(emptyMap())
                    val hasKeyCookie = KEY_COOKIE_NAMES.any { key -> collected.containsKey(key) }
                    if (hasKeyCookie || now >= deadline) {
                        Log.i(
                            TAG,
                            "warmUp done attempt=$attempt hasKey=$hasKeyCookie count=${collected.size}"
                        )
                        finish(collected)
                        runCatching { webView.destroy() }
                        return
                    }
                    mainHandler.postDelayed({ poll(attempt + 1) }, POLL_INTERVAL_MS)
                }

                Log.i(TAG, "warmUp loading $pageUrl")
                webView.loadUrl(pageUrl)
                mainHandler.postDelayed({ poll(0) }, POLL_INTERVAL_MS)
            }
        }

    companion object {
        const val TAG = "DouyinWebSession"
        const val POLL_INTERVAL_MS = 1_500L

        /** 与 DouyinApi.USER_AGENT 保持一致（UA 不一致会导致 cookie 失效） */
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

        /** 预热/验证时判定“已拿到关键 cookie”的字段 */
        val KEY_COOKIE_NAMES = setOf("UIFID_TEMP", "UIFID", "s_v_web_id", "odin_tt")

        @SuppressLint("SetJavaScriptEnabled")
        fun configure(webView: WebView, blockImages: Boolean = true) {
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(webView, true)
            }
            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                userAgentString = USER_AGENT
                blockNetworkImage = blockImages
                blockNetworkLoads = false
            }
        }

        /**
         * flush 后从 douyin 相关域收集 cookie，解析并按白名单过滤。
         */
        fun collectCookies(): Map<String, String> {
            val cookieManager = CookieManager.getInstance()
            cookieManager.flush()
            val result = LinkedHashMap<String, String>()
            COOKIE_DOMAINS.forEach { domain ->
                cookieManager.getCookie(domain)
                    ?.split(';')
                    ?.forEach { part ->
                        val kv = part.trim().split('=', limit = 2)
                        if (kv.size == 2 && kv[0].isNotBlank() && kv[0] in DouyinCookieStore.ALL_COOKIE_KEYS) {
                            result.putIfAbsent(kv[0], kv[1])
                        }
                    }
            }
            return result
        }

        private val COOKIE_DOMAINS = listOf(
            "https://www.douyin.com/",
            "https://douyin.com/",
            "https://www.iesdouyin.com/"
        )
    }
}
