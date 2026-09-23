package com.nora.douyinremover.douyin

import java.net.URL

/**
 * 抖音输入解析：支持
 * 1. 明文链接（视频/图文/用户主页/短链 v.douyin.com）
 * 2. 纯数字视频 ID
 * 3. 用户 sec_user_id
 * 4. 分享口令文本（"7.10 复制打开抖音，看看【xxx的作品】《标题》... A@T.lc kCh:/"）
 *
 * 口令说明：末尾 "A@T.lc kCh:/" 是抖音服务端签发的口令令牌（@T.l 结构恒定），
 * 本地无法离线还原成短链（公开调研结论），因此提取【作者】+《标题》走标题搜索兜底，
 * 同时保留短码做 v.douyin.com 廉价试探。
 */
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

        // 无明文链接：先判断是否是分享口令文本
        if (url == null) {
            return parseShareText(value)
        }

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

    /**
     * 分享口令文本解析：识别「复制打开抖音」文案或 @T.l 令牌。
     * 提取作者、标题、短码候选。标题提取规则：
     * 【xxx的作品】之后、第一个 # 标签或 "- 抖音" 之前的内容（含《》书名号）。
     */
    private fun parseShareText(text: String): ParsedDouyinTarget? {
        val isShareCode = text.contains("复制打开抖音") ||
            text.contains("复制此链接") ||
            SHARE_TOKEN_REGEX.containsMatchIn(text)

        if (!isShareCode) {
            // 非口令非链接：按用户 ID 处理（保持旧行为）
            return ParsedDouyinTarget(DouyinTargetType.USER, text)
        }

        val author = AUTHOR_REGEX.find(text)?.groupValues?.get(1)?.trim()?.take(60)
        val title = TITLE_REGEX.find(text)?.groupValues?.get(1)
            ?.substringBefore('#')
            ?.substringBefore("- 抖音")
            ?.trim()
            ?.take(120)
        // 短码在 @T.l 令牌之后（如 "A@T.lc kCh:/" 的 kCh），
        // 不能全文本提取，否则日期 "11/19" 的 19 会被误判为短码
        val tokenMatch = SHARE_TOKEN_REGEX.find(text)
        val afterToken = tokenMatch?.let { text.substring(it.range.last + 1) }.orEmpty()
        val shortCode = SHORT_CODE_REGEX.find(afterToken)?.groupValues?.get(1)

        return ParsedDouyinTarget(
            type = DouyinTargetType.SHARE_TEXT,
            id = shortCode ?: "",
            shareTitle = title,
            shareAuthor = author
        )
    }

    private fun extractUrl(text: String): String? {
        val match = Regex("https?://[^\\s]+").find(text) ?: return null
        // 去掉链接末尾可能粘连的中英文标点/符号
        return match.value.trimEnd { it in ".,;:)》】」'\"!?。，；：！？" }
    }

    private companion object {
        /** 口令令牌：X@T.lY 结构（@T.l 恒定，首尾字符可变，如 A@T.lc / y@T.lC / B@T.lC） */
        val SHARE_TOKEN_REGEX = Regex("[0-9A-Za-z]@T\\.l[0-9A-Za-z]")

        /** 【作者的作品】 */
        val AUTHOR_REGEX = Regex("【([^】]{1,60})的作品】")

        /** 《标题》…（首个 # 或 - 抖音 前的内容） */
        val TITLE_REGEX = Regex("的作品】(.{1,200}?)(?:#|\\s*-\\s*抖音)")

        /** 口令短码候选：kCh:/ 形式 */
        val SHORT_CODE_REGEX = Regex("([0-9A-Za-z]{1,6})\\s*:")
    }
}
