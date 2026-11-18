package com.kijeye.fnshare

import com.kijeye.fnshare.data.music.fn.FnMusicApi
import com.kijeye.fnshare.data.music.fn.FnMusicRequest
import com.kijeye.fnshare.data.music.fn.FnMusicResponse
import com.kijeye.fnshare.data.music.fn.FnMusicTransport
import com.kijeye.fnshare.data.music.fn.defaultFnMusicBaseUrl
import com.kijeye.fnshare.data.music.fn.fnMusicStreamGateReason
import com.kijeye.fnshare.data.music.fn.withFnMusicAuth
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放流认证与 HTML 闸门的纯 JVM 回归。
 * 这些判定全部来自 2026-11-05 对 `http://192.168.1.100:5666/music/api/v1` 的实测，
 * 尤其是「206 + text/html」——只判状态码会把它当成一次成功的音频响应。
 */
class FnMusicStreamRulesTest {

    private class NoopTransport : FnMusicTransport {
        override suspend fun execute(request: FnMusicRequest): FnMusicResponse =
            FnMusicResponse(200, "application/json", "{}")
    }

    private fun request() = Request.Builder()
        .url("http://192.168.1.100:5666/music/api/v1/track/stream?guid=t1")
        .header("Authorization", "ALBUM_OLD_TOKEN")
        .header("Range", "bytes=0-1023")
        .build()

    @Test
    fun authHeadersOverwriteSameNameAndKeepRange() {
        val out = request().withFnMusicAuth(
            mapOf(
                "Authorization" to "FN_TOKEN",
                "x-access-code" to "NTYyNTg2Ng==",
                "x-access-source" to "web",
            ),
        )
        assertEquals("FN_TOKEN", out.header("Authorization"))
        assertEquals("NTYyNTg2Ng==", out.header("x-access-code"))
        assertEquals("web", out.header("x-access-source"))
        assertEquals("bytes=0-1023", out.header("Range"))
        // header() 覆盖而不是 addHeader() 追加，否则相册 token 会一起发过去。
        assertEquals(listOf("FN_TOKEN"), out.headers("Authorization"))
    }

    @Test
    fun authHeadersAreReadFreshOnEveryCall() {
        var token: String? = "TOKEN_A"
        var code = "1111"
        val api = FnMusicApi(
            transport = NoopTransport(),
            baseUrlProvider = { "http://nas:5666/music/api/v1" },
            tokenProvider = { token },
            accessCodeProvider = { code },
        )
        assertEquals("TOKEN_A", api.authHeaders()["Authorization"])
        token = "TOKEN_B"
        code = "2222"
        assertEquals("TOKEN_B", api.authHeaders()["Authorization"])
        assertEquals("MjIyMg==", api.authHeaders()["x-access-code"])
    }

    @Test
    fun gateRejectsHtmlEvenWith206() {
        val reason = fnMusicStreamGateReason(206, "text/html; charset=utf-8")
        assertTrue(reason.orEmpty().contains("访问码"))
    }

    @Test
    fun gateRejectsHtmlWith200() {
        assertTrue(fnMusicStreamGateReason(200, "text/html") != null)
    }

    @Test
    fun gateRejectsJsonErrorBody() {
        assertTrue(fnMusicStreamGateReason(200, "application/json") != null)
    }

    @Test
    fun gateRejects401And403EvenWithAudioContentType() {
        assertTrue(fnMusicStreamGateReason(401, "audio/flac") != null)
        assertTrue(fnMusicStreamGateReason(403, "audio/flac") != null)
    }

    @Test
    fun gateAllowsRealAudioStreams() {
        assertNull(fnMusicStreamGateReason(206, "audio/flac"))
        assertNull(fnMusicStreamGateReason(200, "audio/mpeg"))
        assertNull(fnMusicStreamGateReason(206, "application/octet-stream"))
        // 有些服务端不带 Content-Type，宁可放行由 ExoPlayer 兜底，也不能误拦。
        assertNull(fnMusicStreamGateReason(206, null))
    }

    @Test
    fun defaultBaseUrlDerivesFromAlbumHost() {
        assertEquals(
            "http://192.168.1.100:5666/music/api/v1",
            defaultFnMusicBaseUrl("http://192.168.1.100:5666"),
        )
        assertEquals(
            "http://jfnos.local:5666/music/api/v1",
            defaultFnMusicBaseUrl("https://jfnos.local:8443/photo/api"),
        )
        assertEquals(
            "http://192.168.1.100:5666/music/api/v1",
            defaultFnMusicBaseUrl("192.168.1.100"),
        )
        // 空地址交给 API 层抛「地址为空」，不在纯函数里造一个假 host。
        assertEquals("", defaultFnMusicBaseUrl("  "))
    }

    @Test
    fun streamUrlKeepsGuidAndSuffix() {
        val api = FnMusicApi(
            transport = NoopTransport(),
            baseUrlProvider = { "http://nas:5666/music/api/v1/" },
            tokenProvider = { "T" },
            accessCodeProvider = { "" },
        )
        assertEquals(
            "http://nas:5666/music/api/v1/track/stream?guid=abc",
            api.streamUrl("abc"),
        )
    }
}
