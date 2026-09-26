package com.nora.douyinremover.updater

import java.net.InetAddress
import java.net.URI

/**
 * 出站请求 URL 防护：仅允许 https + 固定域名白名单，
 * 且解析结果不得指向环回/私有/链路本地/保留地址（防 SSRF 与 DNS rebinding）。
 */
internal object UrlGuard {
    private val ALLOWED_HOSTS = setOf("gitee.com", "github.com")

    /** 校验并返回规范化 URI；不合规直接抛异常 */
    fun requireSafe(url: String): URI {
        val uri = URI(url)
        require(uri.scheme?.lowercase() == "https") { "仅允许 https 请求: $url" }
        val host = uri.host?.lowercase()?.trimEnd('.')
            ?: throw IllegalArgumentException("URL 缺少 host: $url")
        require(host in ALLOWED_HOSTS) { "host 不在白名单: $host" }
        // host 是字面白名单域名（非 IP 字面量），再校验其解析结果不指向内网段
        val addresses = runCatching { InetAddress.getAllByName(host) }.getOrNull().orEmpty()
        for (addr in addresses) {
            val bad = addr.isLoopbackAddress || addr.isSiteLocalAddress ||
                addr.isLinkLocalAddress || addr.isAnyLocalAddress ||
                addr.isMulticastAddress
            require(!bad) { "host 解析到非公网地址: $host -> ${addr.hostAddress}" }
        }
        return uri
    }
}
