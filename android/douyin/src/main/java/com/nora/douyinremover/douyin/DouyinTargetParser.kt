package com.nora.douyinremover.douyin

import java.net.URL

class DouyinTargetParser {
    fun parse(input: String): ParsedDouyinTarget? {
        val value = input.trim()
        if (value.isEmpty()) return null

        // 分享文本常带前后缀（如「文案 https://v.douyin.com/xxxx/ 复制此链接」），
        // 先从中提取出真正的链接再解析，避免直接 URL() 抛异常而误判为 USER 类型。
        val link = extractUrl(value) ?: value

        if (link.matches(Regex("^[0-9]+$"))) {
            return ParsedDouyinTarget(DouyinTargetType.VIDEO, link)
        }

        val url = runCatching { URL(link) }.getOrNull()
            ?: return ParsedDouyinTarget(DouyinTargetType.USER, link)
        val host = url.host.orEmpty()
        val path = url.path.orEmpty()

        val modalId = url.query?.let { query ->
            Regex("(?:^|&)modal_id=([^&]+)").find(query)?.groupValues?.get(1)
        }
        if (modalId != null) {
            return ParsedDouyinTarget(DouyinTargetType.VIDEO, modalId)
        }

        if (host.endsWith("douyin.com") || host == "v.douyin.com") {
            Regex("/(?:video|note)/(\\d+)").find(path)?.let {
                return ParsedDouyinTarget(
                    if (path.contains("/note/")) DouyinTargetType.NOTE else DouyinTargetType.VIDEO,
                    it.groupValues[1]
                )
            }
            Regex("/user/([^/?]+)").find(path)?.let {
                return ParsedDouyinTarget(DouyinTargetType.USER, it.groupValues[1])
            }
            if (host == "v.douyin.com") {
                return ParsedDouyinTarget(DouyinTargetType.VIDEO, link)
            }
        }

        return null
    }

    private fun extractUrl(text: String): String? {
        val match = Regex("https?://[^\\s]+").find(text) ?: return null
        // 去掉链接末尾可能粘连的中英文标点/符号
        return match.value.trimEnd { it in ".,;:)》】」'\"!?。，；：！？" }
    }
}
