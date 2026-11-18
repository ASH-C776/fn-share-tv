package com.kijeye.fnshare.data.music.fn

import com.kijeye.fnshare.domain.model.FnMusicSignInOutcome
import com.kijeye.fnshare.domain.port.FnMusicAuthPort
import com.kijeye.fnshare.domain.port.MusicPreferencesPort
import java.util.UUID

/**
 * 该代码是从哪个原文件提取的：
 * 【新增适配器，原文件零改动】。把 `FnMusicSession` 的异常语言翻译成
 * `domain/model/FnMusicSignInOutcome`，这样 ViewModel 只依赖 domain port，
 * 不会因为一个 `when` 分支就 import 六个 data 层异常类。
 *
 * 对应的生命周期清理应该在原文件的哪个位置调用：
 * 【无需清理】。无协程作用域、无监听器、无 fd；`verify()` 的 IO 由 session/api
 * 自己调度，随调用结束。播放器与歌词轮询的清理仍在 `ui/AppViewModel.onCleared()`。
 *
 * 线程约定：[verify] 内部经 session 切到 Dispatchers.IO；属性都是同步字段读。
 */
class FnMusicAuthAdapter(
    private val session: FnMusicSession,
    private val prefs: MusicPreferencesPort,
    private val initialBaseUrl: () -> String,
) : FnMusicAuthPort {

    override val loggedIn: Boolean get() = session.loggedIn
    override val accountName: String get() = session.accountName
    override val baseUrl: String get() = prefs.fnAccount?.baseUrl.orEmpty()
    override val savedUser: String get() = prefs.fnAccount?.user.orEmpty()
    override val defaultBaseUrl: String get() = initialBaseUrl()

    override suspend fun verify(
        url: String,
        user: String,
        password: String,
        accessCode: String,
    ): FnMusicSignInOutcome {
        val form = FnLoginForm(
            baseUrl = url.trim(),
            user = user.trim(),
            password = password,
            accessCode = accessCode.trim(),
            /**
             * 实测：deviceId 传空串时服务端回 `{"code":100001,"msg":"unknown error"}`，
             * 非空才 code:0。首次登录还没有存档，必须**在发请求之前**就地生成一个，
             * 不能等 PrefsMusicPreferences.saveFnAccount() 再补——那已经在登录之后了。
             * 生成的值会随表单一路进 saveFnAccount，之后每次重登复用同一个。
             */
            deviceId = prefs.fnAccount?.deviceId?.takeIf { it.isNotBlank() }
                ?: UUID.randomUUID().toString(),
        )
        return try {
            val result = session.verify(form)
            FnMusicSignInOutcome.Success(result.userName)
        } catch (e: FnMusicAccessCodeRequiredException) {
            FnMusicSignInOutcome.NeedAccessCode
        } catch (e: FnMusicBadCredentialException) {
            FnMusicSignInOutcome.BadCredential
        } catch (e: FnMusicNetworkException) {
            FnMusicSignInOutcome.Network
        } catch (e: FnMusicUnauthorizedException) {
            FnMusicSignInOutcome.Other(MSG_UNAUTHORIZED)
        } catch (e: Exception) {
            FnMusicSignInOutcome.Other(e.message?.take(MAX_DETAIL).orEmpty().ifBlank { MSG_UNKNOWN })
        }
    }

    override fun signOut() = session.signOut()

    private companion object {
        const val MSG_UNAUTHORIZED = "登录已过期，请重新验证"
        const val MSG_UNKNOWN = "验证失败"
        const val MAX_DETAIL = 80
    }
}
