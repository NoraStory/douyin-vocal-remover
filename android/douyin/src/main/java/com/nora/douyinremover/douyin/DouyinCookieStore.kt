package com.nora.douyinremover.douyin

import android.content.Context
import android.content.SharedPreferences

class DouyinCookieStore(context: Context) {
    private val preferences: SharedPreferences =
        context.getSharedPreferences("douyin_cookies", Context.MODE_PRIVATE)

    fun load(): String = preferences.getString(KEY_COOKIE, "") ?: ""

    fun save(cookie: String) {
        preferences.edit().putString(KEY_COOKIE, cookie).apply()
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

    private fun parse(cookie: String): Map<String, String> {
        if (cookie.isBlank()) return emptyMap()
        return cookie.split(';').mapNotNull { part ->
            part.split('=', limit = 2).takeIf { it.size == 2 }
                ?.let { it[0].trim() to it[1].trim() }
        }.toMap()
    }

    private fun format(values: Map<String, String>): String =
        values.entries.joinToString("; ") { "${it.key}=${it.value}" }

    private companion object {
        const val KEY_COOKIE = "cookie"
    }
}
