package com.kijeye.fnshare

import com.kijeye.fnshare.data.music.fn.FnLoginForm
import com.kijeye.fnshare.data.music.fn.FnMusicAccessCodeRequiredException
import com.kijeye.fnshare.data.music.fn.FnMusicApi
import com.kijeye.fnshare.data.music.fn.FnMusicBadCredentialException
import com.kijeye.fnshare.data.music.fn.FnMusicNetworkException
import com.kijeye.fnshare.data.music.fn.FnMusicProtocolException
import com.kijeye.fnshare.data.music.fn.FnMusicRequest
import com.kijeye.fnshare.data.music.fn.FnMusicResponse
import com.kijeye.fnshare.data.music.fn.FnMusicTransport
import com.kijeye.fnshare.data.music.fn.FnMusicUnauthorizedException
import com.kijeye.fnshare.data.music.fn.fnMusicBase64
import com.kijeye.fnshare.data.music.fn.fnMusicSha256Hex
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 飞牛音乐协议层的纯 JVM 回归。全部走 [FakeTransport]，不发网络请求、不碰 Android 桩类。
 *
 * 重点钉住 2026-11-05 在真机上实测出来的几个「不报错但功能悄悄坏掉」的坑：
 * HTML 闸门（200/206 都算）、登录 120001 与业务 120001 语义不同、total 分页、
 * 歌词 preferred 指向 guid、访问码改了之后 header 要立刻跟着变。
 */
class FnMusicApiTest {

    private class FakeTransport(
        private val responder: (FnMusicRequest) -> FnMusicResponse,
    ) : FnMusicTransport {
        val requests = mutableListOf<FnMusicRequest>()
        override suspend fun execute(request: FnMusicRequest): FnMusicResponse {
            requests += request
            return responder(request)
        }
    }

    private val form = FnLoginForm(
        baseUrl = "http://192.168.1.100:5666/music/api/v1",
        user = "tv",
        password = "a1234567",
        accessCode = "5625866",
        deviceId = "junit-device",
    )

    private fun api(
        transport: FnMusicTransport,
        base: () -> String = { form.baseUrl },
        token: () -> String? = { "TOKEN" },
        code: () -> String = { form.accessCode },
    ): FnMusicApi = FnMusicApi(transport, base, token, code)

    private fun json(body: String) =
        FnMusicResponse(200, "application/json; charset=utf-8", body)

    private fun html(status: Int = 200) =
        FnMusicResponse(status, "text/html; charset=utf-8", "<!doctype html><html>gate</html>")

    private fun ok(data: String) = json("""{"code":0,"msg":"","data":$data}""")

    // --- 编码：服务端只认这两形态，写错就是「登录永远失败」 ---

    @Test
    fun passwordHashMatchesServerConvention() {
        assertEquals(
            "9626c7444717aab7a3bbdd509bcafa35a7491e9478d421b38e539a621f695edd",
            fnMusicSha256Hex("a1234567"),
        )
    }

    @Test
    fun accessCodeBase64MatchesDocument() {
        assertEquals("NTYyNTg2Ng==", fnMusicBase64("5625866"))
        assertEquals("", fnMusicBase64(""))
    }

    // --- 登录 ---

    @Test
    fun loginSendsHashedPasswordAndAllHeaders() = runBlocking {
        val transport = FakeTransport {
            ok("""{"userToken":"TK-1","user":{"guid":"u1","name":"tv","role":"member"}}""")
        }
        val result = api(transport).login(form)

        assertEquals("TK-1", result.token)
        assertEquals("tv", result.userName)
        val request = transport.requests.single()
        assertEquals("POST", request.method)
        assertTrue(request.url.endsWith("/music/api/v1/user/password-login"))
        assertEquals("NTYyNTg2Ng==", request.headers["x-access-code"])
        assertEquals("web", request.headers["x-access-source"])
        val body = JSONObject(request.body!!)
        assertEquals("tv", body.getString("username"))
        assertEquals(fnMusicSha256Hex("a1234567"), body.getString("password"))
        assertEquals("junit-device", body.getString("deviceId"))
        assertFalse(body.toString().contains("a1234567"))
    }

    @Test
    fun loginFallsBackToFormUserWhenNameMissing() = runBlocking {
        val transport = FakeTransport { ok("""{"userToken":"TK-1","user":{}}""") }
        assertEquals("tv", api(transport).login(form).userName)
    }

    @Test
    fun loginWith120001IsBadCredentialNotTokenExpired() {
        val transport = FakeTransport { json("""{"code":120001,"msg":"bad"}""") }
        assertThrows(FnMusicBadCredentialException::class.java) {
            runBlocking { api(transport).login(form) }
        }
    }

    @Test
    fun loginWithHtmlIsAccessCodeRequired() {
        val transport = FakeTransport { html() }
        assertThrows(FnMusicAccessCodeRequiredException::class.java) {
            runBlocking { api(transport).login(form) }
        }
    }

    @Test
    fun loginWithoutUserTokenIsProtocolError() {
        val transport = FakeTransport { ok("""{"user":{}}""") }
        assertThrows(FnMusicProtocolException::class.java) {
            runBlocking { api(transport).login(form) }
        }
    }

    // --- 响应闸门 ---

    @Test
    fun htmlOnBusinessCallIsAccessCodeRequired() {
        val transport = FakeTransport { html() }
        assertThrows(FnMusicAccessCodeRequiredException::class.java) {
            runBlocking { api(transport).playlists() }
        }
    }

    @Test
    fun htmlWith206IsStillAccessCodeRequired() {
        // 播放流缺访问码时实测就是 206 + text/html，状态码看着像成功。
        val transport = FakeTransport { html(206) }
        assertThrows(FnMusicAccessCodeRequiredException::class.java) {
            runBlocking { api(transport).playlists() }
        }
    }

    @Test
    fun http401IsUnauthorized() {
        val transport = FakeTransport {
            FnMusicResponse(401, "application/json", """{"code":99999}""")
        }
        assertThrows(FnMusicUnauthorizedException::class.java) {
            runBlocking { api(transport).playlists() }
        }
    }

    @Test
    fun business99999IsUnauthorized() {
        val transport = FakeTransport { json("""{"code":99999,"msg":"INVALID TOKEN"}""") }
        assertThrows(FnMusicUnauthorizedException::class.java) {
            runBlocking { api(transport).playlists() }
        }
    }

    @Test
    fun business120001IsUnauthorized() {
        val transport = FakeTransport { json("""{"code":120001,"msg":"expired"}""") }
        assertThrows(FnMusicUnauthorizedException::class.java) {
            runBlocking { api(transport).playlists() }
        }
    }

    @Test
    fun nonSuccessJsonIsNetworkError() {
        val transport = FakeTransport {
            FnMusicResponse(500, "application/json", """{"code":0,"data":{}}""")
        }
        assertThrows(FnMusicNetworkException::class.java) {
            runBlocking { api(transport).playlists() }
        }
    }

    @Test
    fun missingTokenBlocksBusinessCallBeforeAnyRequest() {
        val transport = FakeTransport { ok("""{"list":[]}""") }
        assertThrows(FnMusicUnauthorizedException::class.java) {
            runBlocking { api(transport, token = { null }).playlists() }
        }
        assertEquals(0, transport.requests.size)
    }

    // --- 歌单 ---

    @Test
    fun playlistsUseRealResponseShape() = runBlocking {
        val transport = FakeTransport {
            ok(
                """{"list":[{"guid":"PL1","name":"相册专用","coverId":"x"},""" +
                    """{"guid":"PL2","name":""}],"total":2}""",
            )
        }
        val list = api(transport).playlists()
        assertEquals(2, list.size)
        assertEquals("PL1", list[0].guid)
        assertEquals("相册专用", list[0].name)
        assertEquals("未命名歌单", list[1].name)
        val request = transport.requests.single()
        assertTrue(request.url.endsWith("/playlist/list"))
        assertEquals("TOKEN", request.headers["Authorization"])
        assertEquals("NTYyNTg2Ng==", request.headers["x-access-code"])
        assertEquals("web", request.headers["x-access-source"])
    }

    // --- 曲目分页（不读 total 会静默截断） ---

    @Test
    fun playlistTracksPaginatesUntilTotal() = runBlocking {
        val transport = FakeTransport { request ->
            val page = Regex("page=(\\d+)").find(request.url)!!.groupValues[1].toInt()
            val count = when (page) { 1 -> 50; 2 -> 50; else -> 20 }
            val items = (1..count).joinToString(",") {
                """{"guid":"g$page-$it","title":"曲$it","duration":1000,"isCue":false,"artists":[{"name":"王菲"}]}"""
            }
            ok("""{"list":[$items],"total":120}""")
        }
        val tracks = api(transport).playlistTracks("PL1")

        assertEquals(120, tracks.size)
        assertEquals(3, transport.requests.size)
        assertEquals("曲1", tracks[0].title)
        assertEquals("王菲", tracks[0].artist)
        assertEquals(1000L, tracks[0].durationMs)
        assertTrue(transport.requests[0].url.contains("playlistGUID=PL1"))
        assertTrue(transport.requests[2].url.contains("page=3"))
    }

    @Test
    fun playlistTracksStopsAtSafetyCap() = runBlocking {
        val items = (1..50).joinToString(",") {
            """{"guid":"g$it","title":"T","duration":1}"""
        }
        val transport = FakeTransport { ok("""{"list":[$items],"total":100000}""") }
        val tracks = api(transport).playlistTracks("PL1")

        assertEquals(2_000, tracks.size)
        assertEquals(40, transport.requests.size)
    }

    @Test
    fun playlistTracksSkipsBlankGuidAndKeepsCueFlag() = runBlocking {
        val transport = FakeTransport {
            ok("""{"list":[{"guid":"","title":"空"},""" +
                """{"guid":"G1","title":"片段","isCue":true}],"total":2}""")
        }
        val tracks = api(transport).playlistTracks("PL1")
        assertEquals(1, tracks.size)
        assertTrue(tracks[0].isCue)
    }

    // --- 歌词：preferred 是 guid，不是文档写的 id ---

    @Test
    fun lyricsPickPreferredGuid() = runBlocking {
        val transport = FakeTransport {
            ok(
                """{"preferred":"L2","list":[""" +
                    """{"guid":"L1","isLRC":true,"content":"[00:01.00]第一份","offset":0},""" +
                    """{"guid":"L2","isLRC":true,"content":"[00:02.00]第二份","offset":0}]}""",
            )
        }
        val lyric = api(transport).lyrics("TRACK1")
        assertEquals("L2", lyric!!.guid)
        assertTrue(lyric.content.contains("第二份"))
        assertTrue(lyric.isLrc)
        assertTrue(transport.requests.single().url.contains("trackGUID=TRACK1"))
    }

    @Test
    fun lyricsFallBackToFirstLrcWhenPreferredMissing() = runBlocking {
        val transport = FakeTransport {
            ok(
                """{"preferred":"NOPE","list":[""" +
                    """{"guid":"L1","isLRC":false,"content":"[00:01.00]非LRC"},""" +
                    """{"guid":"L2","isLRC":true,"content":"[00:02.00]LRC","offset":0}]}""",
            )
        }
        assertEquals("L2", api(transport).lyrics("T")!!.guid)
    }

    @Test
    fun lyricsFallBackToFirstNonEmptyForLegacyFormatField() = runBlocking {
        val transport = FakeTransport {
            ok("""{"list":[{"id":"L1","format":"lrc","content":"[00:01.00]老格式"}]}""")
        }
        assertTrue(api(transport).lyrics("T")!!.content.contains("老格式"))
    }

    @Test
    fun lyricsReturnNullWhenNothingUsable() {
        val transport = FakeTransport { ok("""{"preferred":"","list":[{"guid":"L1","content":""}]}""") }
        assertNull(runBlocking { api(transport).lyrics("T") })
    }

    // --- 动态凭据：设置页改码 / session 重登后必须立刻生效 ---

    @Test
    fun authHeadersReflectProviderChanges() {
        var token: String? = "OLD"
        var code = "111111"
        val musicApi = api(
            FakeTransport { ok("""{"list":[]}""") },
            token = { token },
            code = { code },
        )
        assertEquals("OLD", musicApi.authHeaders()["Authorization"])
        assertEquals(fnMusicBase64("111111"), musicApi.authHeaders()["x-access-code"])

        token = "NEW"
        code = "5625866"
        assertEquals("NEW", musicApi.authHeaders()["Authorization"])
        assertEquals("NTYyNTg2Ng==", musicApi.authHeaders()["x-access-code"])
    }

    @Test
    fun streamUrlUsesCurrentBaseUrl() {
        var base = "http://nas-a:5666/music/api/v1"
        val musicApi = api(FakeTransport { ok("{}") }, base = { base })
        assertEquals("http://nas-a:5666/music/api/v1/track/stream?guid=G1", musicApi.streamUrl("G1"))
        base = "http://nas-b:5666/music/api/v1/"
        assertEquals("http://nas-b:5666/music/api/v1/track/stream?guid=G1", musicApi.streamUrl("G1"))
    }

    @Test
    fun emptyBaseUrlIsRejectedWithReadableError() {
        val musicApi = api(FakeTransport { ok("{}") }, base = { "" })
        assertThrows(FnMusicNetworkException::class.java) { runBlocking { musicApi.playlists() } }
    }
}
