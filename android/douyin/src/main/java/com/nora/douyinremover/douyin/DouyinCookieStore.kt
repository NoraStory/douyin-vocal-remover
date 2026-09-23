package com.nora.douyinremover.douyin

import android.content.Context
import android.content.SharedPreferences

/**
 * 抖音 cookie 存储：SharedPreferences 持久化 + 白名单过滤 + 24 小时过期 + 登录态判断。
 *
 * 白名单参考 kd64i/dyparse 的匿名会话字段，并补上 ArgusSecurityPlugin 点名要求的
 * UIFID/UIFID_TEMP（403 "Uifid Not Found" 的关键字段，只能由通过挑战的浏览器获得）。
 * 登录后额外保留 session 系 cookie，登录态请求可豁免匿名风控限流。
 */
class DouyinCookieStore(context: Context) {
    private val preferences: SharedPreferences =
        context.getSharedPreferences("douyin_cookies", Context.MODE_PRIVATE)

    fun load(): String = preferences.getString(KEY_COOKIE, "") ?: ""

    fun savedAt(): Long = preferences.getLong(KEY_SAVED_AT, 0L)

    fun save(cookie: String) {
        preferences.edit()
            .putString(KEY_COOKIE, cookie)
            .putLong(KEY_SAVED_AT, System.currentTimeMillis())
            .apply()
    }

    /**
     * 合并一批 cookie（来自 WebView CookieManager），只保留白名单字段；
     * 已存在的值不覆盖（避免用新 session 的 __ac_signature 覆盖掉成对的旧 nonce）。
     */
    fun mergeFiltered(cookies: Map<String, String>) {
        val current = parse(load()).toMutableMap()
        cookies.forEach { (key, value) ->
            if (key in ALL_COOKIE_KEYS && key !in current) {
                current[key] = value
            }
        }
        save(format(current))
    }

    fun saveHeaderValue(value: String) {
        val current = parse(load()).toMutableMap()
        value.substringBefore(';').split('=', limit = 2).let {
            if (it.size == 2) current[it[0].trim()] = it[1].trim()
        }
        save(format(current))
    }

    fun clear() {
        preferences.edit().clear().apply()
    }

    /** 匿名会话 cookie 24 小时过期；已登录（session cookie 有效）视为不过期 */
    fun isStale(maxAgeMs: Long = DEFAULT_MAX_AGE_MS): Boolean {
        if (isLoggedIn()) return false
        val saved = savedAt()
        return saved <= 0L || System.currentTimeMillis() - saved > maxAgeMs
    }

    fun isLoggedIn(): Boolean {
        val cookie = load()
        return SESSION_KEY_NAMES.any { cookie.contains("$it=") }
    }

    private fun parse(cookie: String): Map<String, String> {
        if (cookie.isBlank()) return emptyMap()
        return cookie.split(';').mapNotNull { part ->
            part.split('=', limit = 2).takeIf { it.size == 2 }
                ?.let { it[0].trim() to it[1].trim() }
        }.toMap()
    }

    private fun format(values: Map<String, String>): String =
        values.entries.joinToString("; ") { "${it.key}=${it.value}" }

    internal companion object {
        const val KEY_COOKIE = "cookie"
        const val KEY_SAVED_AT = "saved_at"
        const val DEFAULT_MAX_AGE_MS = 24L * 60 * 60 * 1000

        /** 匿名会话字段（dyparse 白名单 + UIFID 系） */
        val ANONYMOUS_COOKIE_KEYS = setOf(
            "ttwid", "msToken", "__ac_nonce", "__ac_signature", "odin_tt",
            "UIFID", "UIFID_TEMP", "s_v_web_id", "passport_csrf_token",
            "passport_csrf_token_default", "home_csrf_token", "__ac_referer"
        )

        /** 登录态字段 */
        val SESSION_COOKIE_KEYS = setOf(
            "sessionid", "sessionid_ss", "sid_tt", "sid_guard", "sid_ucp_v1",
            "uid_tt", "uid_tt_ss", "sso_uid_tt", "sso_uid_tt_ss",
            "passport_auth_status", "LOGIN_STATUS", "store-region", "store-region-src",
            "multi_sids", "isg", "webapp_session_key", "passport_assist_user",
            "bd_ticket_guard_client_data", "bd_ticket_guard_client_web_domain",
            "bd_ticket_guard_regenerate_keys_time", "strategyABtestKey",
            "download_guide", "volume_info", "is_dash_user", "hevc_supported",
            "dy_sheight", "dy_swidth", "device_web_cpu_core", "device_web_memory_size"
        )

        val ALL_COOKIE_KEYS = ANONYMOUS_COOKIE_KEYS + SESSION_COOKIE_KEYS
        /** 存在任一即视为已登录 */
        val SESSION_KEY_NAMES = setOf("sessionid", "sessionid_ss", "sid_tt")
    }
}
