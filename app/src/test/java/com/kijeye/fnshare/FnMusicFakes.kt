package com.kijeye.fnshare

import com.kijeye.fnshare.data.music.fn.FnMusicRequest
import com.kijeye.fnshare.data.music.fn.FnMusicResponse
import com.kijeye.fnshare.data.music.fn.FnMusicTransport
import com.kijeye.fnshare.domain.model.FnMusicAccount
import com.kijeye.fnshare.domain.model.MusicSourceKind
import com.kijeye.fnshare.domain.port.MusicPreferencesPort

/** 记录每次请求，按 URL 返回固定响应；协议层与会话层的测试共用，不发网络。 */
class RecordingTransport(
    private val responder: (FnMusicRequest) -> FnMusicResponse,
) : FnMusicTransport {
    val requests = mutableListOf<FnMusicRequest>()
    override suspend fun execute(request: FnMusicRequest): FnMusicResponse {
        requests += request
        return responder(request)
    }
}

fun fnJson(body: String) = FnMusicResponse(200, "application/json; charset=utf-8", body)

fun fnOk(data: String) = fnJson("""{"code":0,"msg":"","data":$data}""")

fun fnHtml(status: Int = 200) =
    FnMusicResponse(status, "text/html; charset=utf-8", "<!doctype html>gate")

/** 内存版偏好，避开 SharedPreferences / AndroidKeyStore 的 Android 桩。 */
class InMemoryMusicPrefs : MusicPreferencesPort {

    override var sourceKind: MusicSourceKind = MusicSourceKind.LOCAL
    private var saved: FnMusicAccount? = null
    override var lastPlaylistId: String? = null

    override val fnAccount: FnMusicAccount? get() = saved

    override fun saveFnAccount(
        baseUrl: String,
        user: String,
        password: String,
        accessCode: String,
        deviceId: String,
        userName: String,
    ) {
        saved = FnMusicAccount(baseUrl, user, password, accessCode, deviceId, userName)
    }

    override fun clearFnAccount() {
        saved = null
        sourceKind = MusicSourceKind.LOCAL
        lastPlaylistId = null
    }
}
