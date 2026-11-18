package com.kijeye.fnshare.data.local

import com.kijeye.fnshare.data.FnSettings
import com.kijeye.fnshare.domain.model.FnMusicAccount
import com.kijeye.fnshare.domain.model.MusicSourceKind
import com.kijeye.fnshare.domain.port.MusicPreferencesPort
import java.util.UUID

/**
 * 该代码是从哪个原文件提取的：
 * 【新增适配器，原文件零改动】。只调 `data/FnSettings.kt` 里新增的那组 `fnm_*` 属性，
 * 相册账号（`host/user/pass/code`）与 `forget()` 一个字没碰。
 *
 * 对应的生命周期清理应该在原文件的哪个位置调用：
 * 【无需清理】。只持有 `FnSettings`（SharedPreferences），无 fd、无监听器、无线程。
 * `AppViewModel.onCleared()` 不为此新增调用。[clearFnAccount] 是用户主动退出飞牛音乐，
 * 顺手把来源切回 LOCAL，避免退出后停在空列表上。
 *
 * 线程约定：同步属性，主线程读写；内部只有一次 SharedPreferences 访问，不做 IO。
 */
class PrefsMusicPreferences(
    private val delegate: FnSettings,
) : MusicPreferencesPort {

    override var sourceKind: MusicSourceKind
        get() = runCatching { MusicSourceKind.valueOf(delegate.musicSourceKind) }
            .getOrDefault(MusicSourceKind.LOCAL)
        set(value) { delegate.musicSourceKind = value.name }

    override val fnAccount: FnMusicAccount?
        get() {
            val url = delegate.fnMusicBaseUrl
            val user = delegate.fnMusicUser
            val password = delegate.fnMusicPassword
            if (url.isBlank() || user.isBlank() || password.isBlank()) return null
            return FnMusicAccount(
                baseUrl = url,
                user = user,
                password = password,
                accessCode = delegate.fnMusicAccessCode,
                deviceId = delegate.fnMusicDeviceId,
                userName = delegate.fnMusicUserName.ifBlank { user },
            )
        }

    override fun saveFnAccount(
        baseUrl: String,
        user: String,
        password: String,
        accessCode: String,
        deviceId: String,
        userName: String,
    ) {
        delegate.fnMusicBaseUrl = baseUrl.trim().trimEnd('/')
        delegate.fnMusicUser = user.trim()
        delegate.fnMusicPassword = password
        delegate.fnMusicAccessCode = accessCode.trim()
        // deviceId 只要稳定即可；首次留空时生成一个，之后每次重登都复用同一个。
        delegate.fnMusicDeviceId = deviceId.ifBlank { UUID.randomUUID().toString() }
        delegate.fnMusicUserName = userName.ifBlank { user.trim() }
    }

    override fun clearFnAccount() {
        delegate.forgetFnMusic()
        sourceKind = MusicSourceKind.LOCAL
        lastPlaylistId = null
    }

    override var lastPlaylistId: String?
        get() = delegate.fnMusicLastPlaylistId.takeIf { it.isNotBlank() }
        set(value) { delegate.fnMusicLastPlaylistId = value.orEmpty() }
}
