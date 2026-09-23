package com.nora.douyinremover.douyin

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class DouyinTargetType {
    VIDEO,
    USER,
    NOTE,
    /** 分享口令文本（无明文链接，"复制打开抖音"文案），走短码试探 + 标题搜索 */
    SHARE_TEXT
}

data class ParsedDouyinTarget(
    val type: DouyinTargetType,
    val id: String,
    /** 口令标题（《xxx》部分，去掉 # 标签） */
    val shareTitle: String? = null,
    /** 口令作者（【xxx的作品】） */
    val shareAuthor: String? = null
)

data class ResolvedMediaItem(
    val title: String,
    val url: String,
    val width: Int? = null,
    val height: Int? = null,
    val isImage: Boolean = false,
    val isWatermarkFree: Boolean = false,
    val qualityLabel: String
)

@Serializable
data class AwemeDetailResponse(
    @SerialName("aweme_detail")
    val awemeDetail: AwemeItem? = null,
    @SerialName("status_code")
    val statusCode: Int = 0
)

@Serializable
data class AwemePostResponse(
    @SerialName("aweme_list")
    val awemeList: List<AwemeItem> = emptyList(),
    @SerialName("max_cursor")
    val maxCursor: Long = 0,
    @SerialName("has_more")
    val hasMore: Int = 0
)

@Serializable
data class GeneralSearchResponse(
    val data: List<SearchItem> = emptyList(),
    @SerialName("status_code")
    val statusCode: Int = 0,
    @SerialName("status_msg")
    val statusMsg: String? = null
)

@Serializable
data class SearchItem(
    @SerialName("aweme_info")
    val awemeInfo: AwemeItem? = null,
    @SerialName("aweme_mix_info")
    val awemeMixInfo: AwemeMixInfo? = null
)

@Serializable
data class AwemeMixInfo(
    @SerialName("mix_items")
    val mixItems: List<AwemeItem> = emptyList()
)

@Serializable
data class AwemeItem(
    val desc: String = "",
    val video: AwemeVideo? = null,
    // 抖音接口对纯视频会返回 "images": null，需显式容错（默认值只对字段缺失生效，对显式 null 无效）
    @SerialName("images")
    val imagesRaw: List<AwemeImage>? = null,
    @SerialName("aweme_id")
    val awemeId: String = "",
    @SerialName("create_time")
    val createTime: Long = 0,
    val author: AwemeAuthor = AwemeAuthor()
) {
    val images: List<AwemeImage> get() = imagesRaw.orEmpty()
}

@Serializable
data class AwemeVideo(
    @SerialName("bit_rate")
    val bitRate: List<AwemeBitRate> = emptyList(),
    @SerialName("play_addr")
    val playAddr: AwemePlayAddress = AwemePlayAddress()
)

@Serializable
data class AwemeBitRate(
    @SerialName("play_addr")
    val playAddr: AwemePlayAddress = AwemePlayAddress(),
    @SerialName("download_addr")
    val downloadAddr: AwemePlayAddress? = null
)

@Serializable
data class AwemePlayAddress(
    @SerialName("url_list")
    val urlList: List<String> = emptyList(),
    val width: Int = 0,
    val height: Int = 0
)

@Serializable
data class AwemeImage(
    @SerialName("url_list")
    val urlList: List<String> = emptyList(),
    val width: Int = 0,
    val height: Int = 0
)

@Serializable
data class AwemeAuthor(
    val nickname: String = ""
)
