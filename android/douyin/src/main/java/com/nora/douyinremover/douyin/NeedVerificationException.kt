package com.nora.douyinremover.douyin

import java.io.IOException

/**
 * 解析请求被抖音风控拦截（403 ArgusSecurityPlugin 或验证码中间页）且自动重试全部失败。
 * IP 维度限流无法靠换 cookie 绕过，需要用户在 WebView 中完成验证码或登录后重试。
 */
class NeedVerificationException(message: String) : IOException(message)
