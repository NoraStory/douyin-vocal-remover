package com.nora.douyinremover.douyin

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class DouyinTargetType {
    VIDEO,
    USER,
    NOTE
}

data class ParsedDouyinTarget(
    val type: DouyinTargetType,
    val id: String
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
data class AwemeItem(
    val desc: String = "",
    val video: AwemeVideo? = null,
    val images: List<AwemeImage> = emptyList(),
    @SerialName("aweme_id")
    val awemeId: String = "",
    @SerialName("create_time")
    val createTime: Long = 0,
    val author: AwemeAuthor = AwemeAuthor()
)

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
