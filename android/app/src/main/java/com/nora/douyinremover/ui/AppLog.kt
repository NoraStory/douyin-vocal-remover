package com.nora.douyinremover.ui

import android.os.Process
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 收集本进程最近的 logcat 输出，供用户一键复制反馈。
 * Android 4.1+ 允许应用读取自己进程的日志，无需权限。
 * 复制前对 cookie / 签名等敏感字段脱敏。
 */
object AppLog {

    fun collect(maxLines: Int = 400): String = runCatching {
        val process = ProcessBuilder(
            "logcat", "-d", "-v", "threadtime",
            "--pid=${Process.myPid()}"
        ).redirectErrorStream(true).start()

        val allLines = BufferedReader(InputStreamReader(process.inputStream))
            .useLines { it.toList() }
        process.waitFor()

        val filtered = allLines
            .filterNot { line -> NOISE_TAGS.any { line.contains(it) } }
            .map(::sanitize)
        filtered.takeLast(maxLines).joinToString("\n")
    }.getOrDefault("无法收集日志")

    /** 脱敏：cookie 值与签名值替换为 *** */
    private fun sanitize(line: String): String {
        var result = Regex("(?i)(cookie=[^;\\s]{0,200})").replace(line) { "${it.groupValues[1].substringBefore('=')}=***" }
        result = Regex("(__ac_signature=)[^\\s;]{0,120}").replace(result) { "${it.groupValues[1]}***" }
        result = Regex("(uifid=)[^\\s,;]{0,120}").replace(result) { "${it.groupValues[1]}***" }
        return result
    }

    private val NOISE_TAGS = listOf(
        "EGL_emulation", "app_time_stats", "FrameTracker", "ImeTracker",
        "InsetsController", "InputMethodManager", "Choreographer", "VRI[",
        "AssistStructure", "RemoteInputConnection", "PerfettoTrigger",
        "WindowOnBackDispatcher", "ViewTreeObserver", "InteractionJankMonitor",
        "Gralloc4", "HWUI", "AutofillManager"
    )
}
