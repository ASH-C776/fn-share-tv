package com.kijeye.fnshare

import com.kijeye.fnshare.data.music.fn.FnMusicApi
import com.kijeye.fnshare.data.music.fn.FnMusicAuthAdapter
import com.kijeye.fnshare.data.music.fn.FnMusicNetworkException
import com.kijeye.fnshare.data.music.fn.FnMusicRequest
import com.kijeye.fnshare.data.music.fn.FnMusicSession
import com.kijeye.fnshare.domain.model.FnMusicSignInOutcome
import com.kijeye.fnshare.domain.model.MusicSourceKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 登录弹窗背后那一层异常 → 结果分类的回归：UI 只需要按分支取一句人话。 */
class FnMusicAuthAdapterTest {

    private class Fixture(
        responder: (FnMusicRequest) -> com.kijeye.fnshare.data.music.fn.FnMusicResponse,
    ) {
        val prefs = InMemoryMusicPrefs()
        val transport = RecordingTransport(responder)
        private var sessionRef: FnMusicSession? = null
        // provider 与 di/AppContainer 的真实接线保持一致：验证中先读 pending 表单。
        val api = FnMusicApi(
            transport = transport,
            baseUrlProvider = { sessionRef?.pendingBaseUrl() ?: BASE },
            tokenProvider = { sessionRef?.tokenOrNull() },
            accessCodeProvider = {
                sessionRef?.pendingAccessCode() ?: prefs.fnAccount?.accessCode.orEmpty()
            },
        )
        // api 的 tokenProvider 要读 session，session 构造又要 api：
        // 用可空引用把环拆开，provider 只在真正发请求时才求值。
        val session = FnMusicSession(api, prefs).also { sessionRef = it }
        val auth = FnMusicAuthAdapter(session, prefs) { DEFAULT_URL }
    }

    private fun fixture(responder: (FnMusicRequest) -> com.kijeye.fnshare.data.music.fn.FnMusicResponse) =
        Fixture(responder)

    private fun standard(request: FnMusicRequest) = when {
        request.url.contains("password-login") -> fnOk(LOGIN_DATA)
        request.url.contains("playlist/list") -> fnOk("""{"list":[],"total":0}""")
        else -> fnOk("{}")
    }

    @Test
    fun successSavesAccountAndReportsName() = runBlocking {
        val f = fixture(::standard)
        val outcome = f.auth.verify(BASE, "tv", "pass", "5625866")
        assertEquals(FnMusicSignInOutcome.Success("音乐电视"), outcome)
        assertFalse(outcome.failed)
        assertTrue(f.auth.loggedIn)
        assertEquals("音乐电视", f.auth.accountName)
        assertEquals(BASE, f.auth.baseUrl)
        assertEquals("tv", f.auth.savedUser)
        // 登录 + 歌单列表，两次都真发了才算验证通过。
        assertEquals(2, f.transport.requests.size)
        // 空 deviceId 会让服务端回 100001 unknown error（实测），首次登录也必须带上。
        val loginBody = f.transport.requests.first { it.url.contains("password-login") }.body.orEmpty()
        assertTrue(loginBody.contains(Regex("\"deviceId\":\"[^\"]+\"")))
    }

    @Test
    fun verifySendsFormAccessCodeOnPlaylistCall() = runBlocking {
        val f = fixture(::standard)
        f.auth.verify(BASE, "tv", "pass", "5625866")
        val playlist = f.transport.requests.first { it.url.contains("playlist/list") }
        // 这一步发生在凭据落盘之前，只能来自表单；带空码会命中 HTML 闸门。
        assertEquals("NTYyNTg2Ng==", playlist.headers["x-access-code"])
        assertEquals("TOKEN", playlist.headers["Authorization"])
    }

    @Test
    fun pendingFormClearedAfterFailedVerify() = runBlocking {
        val f = fixture { fnHtml() }
        f.auth.verify(BASE, "tv", "pass", "5625866")
        assertEquals(null, f.session.pendingAccessCode())
        assertEquals(null, f.session.pendingBaseUrl())
    }

    @Test
    fun reVerifyReusesTheSameDeviceId() = runBlocking {
        val f = fixture(::standard)
        f.auth.verify(BASE, "tv", "pass", "5625866")
        f.auth.verify(BASE, "tv", "pass", "5625866")
        val ids = f.transport.requests
            .filter { it.url.contains("password-login") }
            .mapNotNull { it.body?.let(DEVICE_ID::find)?.groupValues?.get(1) }
        assertEquals(2, ids.size)
        assertEquals(ids[0], ids[1])
    }

    @Test
    fun htmlGateMapsToNeedAccessCodeAndDoesNotSave() = runBlocking {
        val f = fixture { fnHtml() }
        assertEquals(FnMusicSignInOutcome.NeedAccessCode, f.auth.verify(BASE, "tv", "pass", ""))
        assertFalse(f.auth.loggedIn)
    }

    @Test
    fun badCredentialMapsToBadCredential() = runBlocking {
        val f = fixture { fnJson("""{"code":120001,"msg":"bad"}""") }
        assertEquals(FnMusicSignInOutcome.BadCredential, f.auth.verify(BASE, "tv", "wrong", ""))
    }

    @Test
    fun networkFailureMapsToNetwork() = runBlocking {
        val f = fixture { throw FnMusicNetworkException("timeout") }
        assertEquals(FnMusicSignInOutcome.Network, f.auth.verify(BASE, "tv", "pass", ""))
    }

    @Test
    fun htmlOnSecondRequestStillMeansAccessCodeNotSuccess() = runBlocking {
        val f = fixture { request ->
            if (request.url.contains("password-login")) fnOk(LOGIN_DATA) else fnHtml(200)
        }
        assertEquals(FnMusicSignInOutcome.NeedAccessCode, f.auth.verify(BASE, "tv", "pass", ""))
        // 登录过了但歌单被闸门挡下时绝不落盘，否则设置页会谎报「已登录」。
        assertFalse(f.auth.loggedIn)
    }

    @Test
    fun signOutClearsMusicCredentialsButKeepsSourceLocal() = runBlocking {
        val f = fixture(::standard)
        f.auth.verify(BASE, "tv", "pass", "")
        f.prefs.sourceKind = MusicSourceKind.FN
        f.auth.signOut()
        assertFalse(f.auth.loggedIn)
        assertEquals("", f.auth.savedUser)
        assertEquals(MusicSourceKind.LOCAL, f.prefs.sourceKind)
        assertEquals(DEFAULT_URL, f.auth.defaultBaseUrl)
    }

    private companion object {
        const val BASE = "http://nas:5666/music/api/v1"
        const val DEFAULT_URL = "http://192.168.1.100:5666/music/api/v1"
        const val LOGIN_DATA = """{"userToken":"TOKEN","user":{"guid":"g","name":"音乐电视"}}"""
        val DEVICE_ID = Regex("\"deviceId\":\"([^\"]*)\"")
    }
}
