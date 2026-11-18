package com.kijeye.fnshare.data.music.fn

import com.kijeye.fnshare.core.AppDispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * 该代码是从哪个原文件提取的：
 * 【新增实现，无函数体被搬动】。请求风格对齐 `data/fn/FnApi.kt:134-165`
 * （`Request.Builder` → `client.newCall(...).execute().use { ... }`），但把「发请求」
 * 单独抽成接口，好让协议层能用假响应在纯 JVM 单测里跑，不必起 MockWebServer。
 *
 * 对应的生命周期清理应该在原文件的哪个位置调用：
 * 【无需清理】。每次 [execute] 都 `use` 关闭响应体，不持有长连接引用、不建线程池。
 * [OkHttpClient] 由 `di/AppContainer` 以进程级单例持有，随进程回收；
 * `AppViewModel.onCleared()` 不为此新增调用。
 *
 * 线程约定：[execute] 自己切到 [AppDispatchers.io]，OkHttp 的同步 `execute()`
 * 绝不在主线程跑。调用方（`FnMusicApi`）因此不需要再包一层 withContext。
 */

data class FnMusicRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
)

data class FnMusicResponse(
    val status: Int,
    val contentType: String?,
    val body: String,
)

/** 只做「一次 HTTP 往返」。协议解析、码值判定全在 `FnMusicApi`，这里不掺和业务。 */
interface FnMusicTransport {
    suspend fun execute(request: FnMusicRequest): FnMusicResponse
}

class OkHttpFnMusicTransport(
    private val client: OkHttpClient,
) : FnMusicTransport {

    override suspend fun execute(request: FnMusicRequest): FnMusicResponse =
        withContext(AppDispatchers.io) {
            val builder = Request.Builder().url(request.url)
            request.headers.forEach { (key, value) -> builder.header(key, value) }
            when (request.method) {
                "GET" -> builder.get()
                "POST" -> builder.post(
                    (request.body ?: "{}")
                        .toRequestBody("application/json; charset=utf-8".toMediaType()),
                )
                else -> throw FnMusicProtocolException("不支持的 HTTP 方法 ${request.method}")
            }
            try {
                client.newCall(builder.build()).execute().use { response ->
                    FnMusicResponse(
                        status = response.code,
                        contentType = response.header("Content-Type"),
                        body = response.body.string(),
                    )
                }
            } catch (e: IOException) {
                throw FnMusicNetworkException("连不上飞牛音乐服务", e)
            }
        }
}
