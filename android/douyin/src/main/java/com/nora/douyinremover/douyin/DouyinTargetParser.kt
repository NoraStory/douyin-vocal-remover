package com.nora.douyinremover.douyin

import java.net.URL

class DouyinTargetParser {
    fun parse(input: String): ParsedDouyinTarget? {
        val value = input.trim()
        if (value.isEmpty()) return null

        if (value.matches(Regex("^[0-9]+$"))) {
            return ParsedDouyinTarget(DouyinTargetType.VIDEO, value)
        }

        val url = runCatching { URL(value) }.getOrNull()
            ?: return ParsedDouyinTarget(DouyinTargetType.USER, value)
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
                return ParsedDouyinTarget(DouyinTargetType.VIDEO, value)
            }
        }

        return null
    }
}
