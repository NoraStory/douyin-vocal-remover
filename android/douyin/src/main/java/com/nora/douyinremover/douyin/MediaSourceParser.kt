package com.nora.douyinremover.douyin

interface MediaSourceParser {
    suspend fun parse(input: String): List<ResolvedMediaItem>
}
