package com.kijeye.fnshare.data.music.fn

import okhttp3.Request
import java.io.IOException

/**
 * 该代码是从哪个原文件提取的：
 * 【新增实现，无函数体被搬动】。播放流的认证与「HTML 闸门」判定抽成纯函数，
 * 是为了能在纯 JVM 单测里钉住 2026-11-05 实测到的两个坑，不必起设备或 MockWebServer。
 *
 * 对应的生命周期清理应该在原文件的哪个位置调用：
 * 【无需清理】。无状态；[FnMusicStreamGateException] 只带一句给人看的提示。
 *
 * 线程约定：放行 OkHttp 的 IO 线程；[withFnMusicAuth] 每次调用都重新问一遍
 * `FnMusicApi.authHeaders()`，所以会话重登换 token、设置页改访问码后立即生效。
 */

/**
 * 播放流的三个 header 必须**逐个覆盖**（用 `header` 而非 `addHeader`）。
 *
 * 实测：缺 `x-access-code` 时 `/track/stream` 不是 403，而是 **HTTP 206 + text/html
 * 的整页网页**。ExoPlayer 会把 206 当成功、把网页交给解码器，最后只报一句看不懂的
 * 解码错误；拦截器据此在响应阶段拦下来，给用户「需要访问码」这句人话。
 */
fun Request.withFnMusicAuth(headers: Map<String, String>): Request =
    newBuilder().apply {
        headers.forEach { (key, value) -> header(key, value) }
    }.build()

/** 该响应根本不是音频流时返回一句可读原因，正常音频返回 null。 */
fun fnMusicStreamGateReason(status: Int, contentType: String?): String? = when {
    status == 401 || status == 403 -> "访问码或登录已失效（HTTP $status）"
    contentType?.contains("text/html", ignoreCase = true) == true ->
        "需要访问码，或访问码不正确（服务返回了网页，HTTP $status）"
    contentType?.contains("json", ignoreCase = true) == true ->
        "播放流返回了错误 JSON（HTTP $status）"
    // Content-Type 缺失时放行：正常 206 的 FLAC/MP3 流有些服务端不带这个头，
    // 误拦会比漏拦更糟。真出问题由 ExoPlayer 的 onPlayerError 兜底。
    else -> null
}

/** 故意用 IOException：OkHttpDataSource 只把 IOException 转成可预期的 IO 播放错误。 */
class FnMusicStreamGateException(message: String) : IOException(message)
