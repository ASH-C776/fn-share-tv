package com.kijeye.fnshare.data.music.fn

import com.kijeye.fnshare.core.AppDispatchers
import com.kijeye.fnshare.domain.model.FnMusicAccount
import com.kijeye.fnshare.domain.port.MusicPreferencesPort
import kotlinx.coroutines.withContext

/**
 * 该代码是从哪个原文件提取的：
 * 【新增实现，无函数体被搬动】。替代「复用相册账号 + 无限重登」的做法：
 * 音乐子账户独立、token 只在内存、冷启重登一次、业务调用失败最多再重登一次。
 *
 * 对应的生命周期清理应该在原文件的哪个位置调用：
 * 【无需清理】。只有内存里的一个 token 字符串，没有定时器、协程作用域或 fd。
 * 播放器的 `release()` 与歌词轮询的 `cancel()` 仍在 `ui/AppViewModel.onCleared()`；
 * 本类不进那里。[signOut] 是用户主动动作。
 *
 * 线程约定：所有 suspend 方法自带 `withContext(AppDispatchers.io)`；
 * [tokenOrNull] 是纯字段读，供 `FnMusicApi` 的 tokenProvider 在主线程取用。
 * token 用 @Volatile：API 的 header 组装与这里的重登可能不在同一线程。
 */
class FnMusicSession(
    private val api: FnMusicApi,
    private val prefs: MusicPreferencesPort,
) {

    @Volatile
    private var token: String? = null

    /**
     * 验证中的表单凭据。
     *
     * `verify()` 的顺序是 login → playlists → **才** saveFnAccount，而 playlists 的
     * header 由 `FnMusicApi` 的 provider 从已落盘设置里取。不把表单里的访问码/地址
     * 临时露出来，第二步就会带着空访问码去请求，命中 HTML 闸门，界面显示
     * 「需要访问码」——明明用户已经填对了（MuMu 实测）。
     * 只活在 `verify()` 期间，成功落盘后由 prefs 接管，失败则随 finally 清掉。
     */
    @Volatile
    private var pendingForm: FnLoginForm? = null

    fun pendingBaseUrl(): String? = pendingForm?.baseUrl?.takeIf { it.isNotBlank() }
    fun pendingAccessCode(): String? = pendingForm?.accessCode

    /** 「已保存账号」就算已登录：token 可以冷启重登，不必每次弹登录框。 */
    val loggedIn: Boolean get() = prefs.hasFnLogin

    /** 设置页显示用。 */
    val accountName: String get() = prefs.fnAccount?.userName.orEmpty()

    fun tokenOrNull(): String? = token

    /**
     * 表单验证：真发 `login` **再**真发 `playlist/list`，两次都成功才落盘。
     * 只验登录不够——登录过了但访问码不对时，playlist 会回 HTML，那种账号存下来
     * 就是「显示已登录、点进去一片空白」。
     */
    suspend fun verify(form: FnLoginForm): FnLoginResult = withContext(AppDispatchers.io) {
        pendingForm = form
        try {
            val result = api.login(form)
            token = result.token
            try {
                api.playlists()
            } catch (e: Exception) {
                token = null
                throw e
            }
            prefs.saveFnAccount(
                baseUrl = form.baseUrl,
                user = form.user,
                password = form.password,
                accessCode = form.accessCode,
                deviceId = form.deviceId,
                userName = result.userName,
            )
            result
        } finally {
            pendingForm = null
        }
    }

    /** 冷启或 token 被清后重登一次。没有保存的账号就直接抛，交给 UI 提示去设置登录。 */
    suspend fun ensureToken(): String = withContext(AppDispatchers.io) {
        token?.let { return@withContext it }
        val saved = prefs.fnAccount ?: throw FnMusicUnauthorizedException("尚未登录飞牛音乐")
        val result = api.login(saved.toForm())
        token = result.token
        result.token
    }

    /**
     * 业务调用包一层：遇 token 失效只允许重登并重试**一次**。
     * 不做循环重登——NAS 改了密码时，无限重登表现为界面一直转圈而且很费电。
     */
    suspend fun <T> withAuth(block: suspend () -> T): T {
        ensureToken()
        return try {
            block()
        } catch (e: FnMusicUnauthorizedException) {
            token = null
            ensureToken()
            block()
        }
    }

    fun signOut() {
        token = null
        prefs.clearFnAccount()
    }

    private fun FnMusicAccount.toForm(): FnLoginForm = FnLoginForm(
        baseUrl = baseUrl,
        user = user,
        password = password,
        accessCode = accessCode,
        deviceId = deviceId,
    )
}
