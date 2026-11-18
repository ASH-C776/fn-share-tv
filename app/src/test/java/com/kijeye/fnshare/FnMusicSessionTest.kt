package com.kijeye.fnshare

import com.kijeye.fnshare.data.lyrics.FnLyricsSource
import com.kijeye.fnshare.data.music.LocalMusicCatalog
import com.kijeye.fnshare.data.music.fn.FnLoginForm
import com.kijeye.fnshare.data.music.fn.FnMusicAccessCodeRequiredException
import com.kijeye.fnshare.data.music.fn.FnMusicBadCredentialException
import com.kijeye.fnshare.data.music.fn.FnMusicApi
import com.kijeye.fnshare.data.music.fn.FnMusicCatalog
import com.kijeye.fnshare.data.music.fn.FnMusicRequest
import com.kijeye.fnshare.data.music.fn.FnMusicResponse
import com.kijeye.fnshare.data.music.fn.FnMusicSession
import com.kijeye.fnshare.data.music.fn.FnMusicUnauthorizedException
import com.kijeye.fnshare.domain.model.LOCAL_MUSIC_GROUP_ID
import com.kijeye.fnshare.domain.model.MusicEntry
import com.kijeye.fnshare.domain.model.MusicSourceKind
import com.kijeye.fnshare.domain.model.fnTrackGuidOrNull
import com.kijeye.fnshare.domain.port.MusicSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话与目录层的纯 JVM 回归。重点：
 * 验证必须两次请求都成功才落盘、token 失效只重登一次、切账号不影响相册登录、
 * 两个曲库映射成同一套 UI 模型。
 */
class FnMusicSessionTest {

    private val form = FnLoginForm(
        baseUrl = "http://192.168.1.100:5666/music/api/v1",
        user = "tv",
        password = "a1234567",
        accessCode = "5625866",
        deviceId = "junit-device",
    )

    private val loginData = """{"userToken":"TK-1","user":{"guid":"u1","name":"tv"}}"""

    private class Fixture(
        val api: FnMusicApi,
        val session: FnMusicSession,
        val prefs: InMemoryMusicPrefs,
        val transport: RecordingTransport,
    )

    private fun fixture(
        responder: (FnMusicRequest) -> FnMusicResponse = { standardResponder(it) },
    ): Fixture {
        val prefs = InMemoryMusicPrefs()
        val transport = RecordingTransport(responder)
        // tokenProvider 晚于 session 构造才有效，用可空引用断开构造期的循环依赖。
        var sessionRef: FnMusicSession? = null
        val api = FnMusicApi(
            transport,
            baseUrlProvider = { form.baseUrl },
            tokenProvider = { sessionRef?.tokenOrNull() },
            accessCodeProvider = { form.accessCode },
        )
        val session = FnMusicSession(api, prefs)
        sessionRef = session
        return Fixture(api, session, prefs, transport)
    }

    private fun standardResponder(request: FnMusicRequest): FnMusicResponse = when {
        request.url.contains("password-login") -> fnOk(loginData)
        request.url.contains("playlist-detail") -> fnOk(
            """{"list":[{"guid":"T1","title":"如愿","duration":265388,"isCue":false,"artists":[{"name":"王菲"}]},""" +
                """{"guid":"T2","title":"片段","isCue":true}],"total":2}""",
        )
        request.url.contains("playlist/list") -> fnOk(
            """{"list":[{"guid":"PL1","name":"相册专用"}],"total":1}""",
        )
        request.url.contains("lyric/list") -> fnOk(
            """{"preferred":"L1","list":[{"guid":"L1","isLRC":true,"content":"[00:01.00]山野大雾里的灯"}]}""",
        )
        else -> fnOk("{}")
    }

    // --- 验证：两次都成功才落盘 ---

    @Test
    fun verifyPersistsAccountOnlyAfterPlaylistCheck() = runBlocking {
        val f = fixture()
        val result = f.session.verify(form)

        assertEquals("TK-1", result.token)
        assertEquals("tv", f.prefs.fnAccount!!.userName)
        assertEquals(form.user, f.prefs.fnAccount!!.user)
        // login + playlist/list，两次都在
        assertEquals(2, f.transport.requests.size)
        assertTrue(f.transport.requests[0].url.contains("password-login"))
        assertTrue(f.transport.requests[1].url.contains("playlist/list"))
    }

    @Test
    fun verifyDoesNotPersistWhenPlaylistIsHtml() {
        val f = fixture { request ->
            if (request.url.contains("password-login")) fnOk(loginData) else fnHtml()
        }
        assertThrows(FnMusicAccessCodeRequiredException::class.java) {
            runBlocking { f.session.verify(form) }
        }
        assertNull(f.prefs.fnAccount)
        assertNull(f.session.tokenOrNull())
    }

    @Test
    fun verifyDoesNotPersistWhenLoginIsBadCredential() {
        val f = fixture { fnJson("""{"code":120001,"msg":"bad"}""") }
        assertThrows(FnMusicBadCredentialException::class.java) {
            runBlocking { f.session.verify(form) }
        }
        assertNull(f.prefs.fnAccount)
    }

    // --- 重登：一次，且只一次 ---

    @Test
    fun ensureTokenReloginsFromSavedAccount() = runBlocking {
        val f = fixture()
        f.session.verify(form)
        f.transport.requests.clear()

        // 模拟冷启：新 session 没有 token，但账号还在盘上。
        val cold = FnMusicSession(f.api, f.prefs)
        assertEquals("TK-1", cold.ensureToken())
        assertEquals(1, f.transport.requests.size)
        assertTrue(f.transport.requests.single().url.contains("password-login"))
        // token 已在内存，第二次不再发请求。
        cold.ensureToken()
        assertEquals(1, f.transport.requests.size)
    }

    @Test
    fun ensureTokenFailsClearlyWithoutSavedAccount() {
        val f = fixture()
        assertThrows(FnMusicUnauthorizedException::class.java) {
            runBlocking { f.session.ensureToken() }
        }
        assertEquals(0, f.transport.requests.size)
    }

    @Test
    fun withAuthRetriesExactlyOnceAfterTokenExpiry() = runBlocking {
        var armExpiry = false
        var expiredCalls = 0
        val f = fixture { request ->
            when {
                request.url.contains("password-login") -> fnOk(loginData)
                request.url.contains("playlist/list") -> {
                    if (armExpiry && expiredCalls++ == 0) {
                        fnJson("""{"code":99999,"msg":"INVALID TOKEN"}""")
                    } else {
                        fnOk("""{"list":[],"total":0}""")
                    }
                }
                else -> fnOk("{}")
            }
        }
        f.session.verify(form)
        f.transport.requests.clear()
        armExpiry = true

        val groups = FnMusicCatalog(f.api, f.session).listGroups()
        assertTrue(groups.isEmpty())
        // token 已在内存：business(失败) → 重登 login → business(成功)，共 3 次。
        assertEquals(3, f.transport.requests.size)
        assertEquals(1, f.transport.requests.count { it.url.contains("password-login") })
    }

    @Test
    fun withAuthDoesNotLoopWhenStillUnauthorized() {
        val f = fixture { request ->
            when {
                request.url.contains("password-login") -> fnOk(loginData)
                else -> fnJson("""{"code":99999,"msg":"INVALID TOKEN"}""")
            }
        }
        // 直接给一份已保存账号，跳过 verify（verify 本身也要打一次业务接口）。
        f.prefs.saveFnAccount(
            form.baseUrl, form.user, form.password, form.accessCode, form.deviceId, "tv",
        )
        assertThrows(FnMusicUnauthorizedException::class.java) {
            runBlocking { FnMusicCatalog(f.api, f.session).listGroups() }
        }
        // 初次 ensureToken + 一次重试重登，绝不能变成无限循环。
        assertEquals(2, f.transport.requests.count { it.url.contains("password-login") })
    }

    @Test
    fun signOutClearsAccountAndReturnsToLocalSource() = runBlocking {
        val f = fixture()
        f.session.verify(form)
        f.prefs.sourceKind = MusicSourceKind.FN
        f.prefs.lastPlaylistId = "fnpl:PL1"

        f.session.signOut()
        assertNull(f.prefs.fnAccount)
        assertNull(f.session.tokenOrNull())
        assertFalse(f.session.loggedIn)
        assertEquals(MusicSourceKind.LOCAL, f.prefs.sourceKind)
        assertNull(f.prefs.lastPlaylistId)
    }

    // --- 目录映射：UI 只看统一模型 ---

    @Test
    fun fnCatalogMapsPlaylistsAndFiltersCueTracks() = runBlocking {
        val f = fixture()
        f.session.verify(form)
        val catalog = FnMusicCatalog(f.api, f.session)

        val groups = catalog.listGroups()
        assertEquals(MusicSourceKind.FN, catalog.kind)
        assertEquals(1, groups.size)
        assertEquals("相册专用", groups[0].title)
        assertTrue(groups[0].id.startsWith("fnpl:"))

        val tracks = catalog.listTracks(groups[0].id)
        assertEquals(1, tracks.size) // isCue=true 的那首被过滤
        assertEquals("fn:T1", tracks[0].id)
        assertEquals("T1", fnTrackGuidOrNull(tracks[0].id))
        assertTrue(tracks[0].title.contains("王菲"))
        assertEquals(265388L, tracks[0].durationMs)
    }

    @Test
    fun fnCatalogIgnoresNonPlaylistGroupId() = runBlocking {
        val f = fixture()
        f.session.verify(form)
        assertTrue(FnMusicCatalog(f.api, f.session).listTracks(LOCAL_MUSIC_GROUP_ID).isEmpty())
        assertEquals(0, f.transport.requests.count { it.url.contains("playlist-detail") })
    }

    @Test
    fun localCatalogKeepsMusicEntryIdentity() = runBlocking {
        val entry = MusicEntry(id = "file:/music/a.flac", title = "a", durationMs = 1000)
        val source = object : MusicSource {
            var scans = 0
            override val requiredPermission: String = ""
            override fun hasPermission(): Boolean = true
            override suspend fun scan(): List<MusicEntry> {
                scans++
                return listOf(entry)
            }
        }
        val catalog = LocalMusicCatalog(source)
        val groups = catalog.listGroups()
        assertEquals(MusicSourceKind.LOCAL, catalog.kind)
        assertEquals(LOCAL_MUSIC_GROUP_ID, groups.single().id)
        assertEquals(entry, catalog.listTracks(groups.single().id).single())
        assertTrue(catalog.listTracks("fnpl:whatever").isEmpty())
    }

    // --- 歌词：同一套 LyricsSource 契约 ---

    @Test
    fun fnLyricsSourceParsesLrcForFnTrack() = runBlocking {
        val f = fixture()
        f.session.verify(form)
        val doc = FnLyricsSource(f.api, f.session).load("fn:T1")
        assertNotNull(doc)
        assertEquals("山野大雾里的灯", doc!!.lines.single().text)
        assertEquals(1000L, doc.lines.single().timeMs)
    }

    @Test
    fun fnLyricsSourceIgnoresLocalTrackIdsWithoutAnyRequest() = runBlocking {
        val f = fixture()
        f.session.verify(form)
        f.transport.requests.clear()
        assertNull(FnLyricsSource(f.api, f.session).load("file:/music/a.flac"))
        assertEquals(0, f.transport.requests.size)
    }
}
