package com.nora.douyinremover.audio

import java.io.IOException

/**
 * 视频没有可用的音频轨道（无声视频，或音频编码不受支持导致 ffmpeg 输出无流）。
 * 上层应自动降级尝试其他码率/地址，全部失败后向用户展示友好提示。
 */
class NoAudioTrackException(message: String) : IOException(message)
