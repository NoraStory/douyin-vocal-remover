package com.nora.douyinremover.douyin

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

class JsSignatureEngine(context: Context) {
    private val webView = WebView(context.applicationContext)

    suspend fun aBogus(params: String, data: String = "", userAgent: String): String {
        return evaluate("window.bdms.init._v[2].p[42](0,1,6,${jsonString(params)},${jsonString(data)},${jsonString(userAgent)})")
    }

    suspend fun acrawlerSign(arg1: String, arg2: String): String {
        return evaluate("window.byted_acrawler.sign(${jsonString(arg1)},[${jsonString(arg2)}])")
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun ensureLoaded() {
        if (loaded) return
        withContext(Dispatchers.Main) {
            webView.settings.javaScriptEnabled = true
            webView.loadUrl(SIGNATURE_ASSET_URL)
        }
        loaded = true
    }

    private suspend fun evaluate(script: String): String {
        ensureLoaded()
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                webView.evaluateJavascript(script) { result ->
                    if (continuation.isActive) {
                        continuation.resume(cleanJsonValue(result))
                    }
                }
            }
        }
    }

    private fun cleanJsonValue(value: String): String =
        value.trim().removeSurrounding("\"").replace("\\n", "").replace("\\\"", "\"")

    private fun jsonString(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private companion object {
        const val SIGNATURE_ASSET_URL = "file:///android_asset/js/douyin_signature.html"
    }

    private var loaded = false
}
