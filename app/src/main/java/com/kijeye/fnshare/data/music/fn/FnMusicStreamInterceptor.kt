package com.kijeye.fnshare.data.music.fn

import okhttp3.Interceptor
import okhttp3.Response

/**
 * 该代码是从哪个原文件提取的：
 * 【新增实现，无函数体被搬动】。只服务 `/track/stream` 这一条请求链；
 * 业务接口的 header 由 `FnMusicApi` 自己写进 `FnMusicRequest`，不走这里。
 *
 * 对应的生命周期清理应该在原文件的哪个位置调用：
 * 【无需清理】。拦截器无状态；异常路径上显式 `response.close()` 关掉响应体。
 * 播放器与 OkHttpClient 的清理位置见 `engine/impl/FnMusicPlayer.kt` 与 `di/AppContainer.kt`。
 *
 * 线程约定：OkHttp 的 IO 线程。`api.authHeaders()` 是纯字段读，可在任意线程取。
 *
 * ⚠️ 不能把三个 header 在构造播放器时用 `setDefaultRequestProperties` 固定下来：
 * token 会过期、访问码会在设置页被改，固定值会让「重登成功后仍然放不出声」。
 */
class FnMusicStreamInterceptor(
    private val api: FnMusicApi,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val authed = chain.request().withFnMusicAuth(api.authHeaders())
        val response = chain.proceed(authed)
        val reason = fnMusicStreamGateReason(response.code, response.header("Content-Type"))
        if (reason != null) {
            response.close()
            throw FnMusicStreamGateException(reason)
        }
        return response
    }
}
