package com.kijeye.fnshare.data

import com.kijeye.fnshare.data.fn.FnAddress
import com.kijeye.fnshare.data.fn.FnApi
import com.kijeye.fnshare.data.fn.FnCookies
import com.kijeye.fnshare.data.fn.FnLogin
import com.kijeye.fnshare.data.fn.FnSession
import com.kijeye.fnshare.data.fn.FnSessionExpiredException
import com.kijeye.fnshare.data.fn.FnTrust
import com.kijeye.fnshare.data.fn.PhotoPage
import com.kijeye.fnshare.data.fn.SharedAlbum
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap

/**
 * 会话与所有网络请求的唯一入口。
 *
 * 图片加载和接口调用共用同一个 [client]：缩略图那类 URL 不要签名，但**要** `AccessToken`，
 * 所以凭证是挂在客户端上的拦截器，而不是每个调用各加一次——少写一遍就少漏一处。
 */
class FnRepo(private val settings: FnSettings) : FnDataSource {

    /**
     * 证书指纹按主机名存。进程内用 ConcurrentHashMap 作快速查表（TLS 握手可能在多个线程并发进行），
     * 启动时从 [FnSettings.loadPins] 灌入，写入时同步落盘——这样重启后仍能认出「上次那张证书」，
     * 否则 TOFU 的连续性只在单个进程生命周期内有效，攻击者挑重启后的首次连接即可 MITM。
     */
    private val pins = ConcurrentHashMap<String, String>().apply { putAll(settings.loadPins()) }

    @Volatile
    private var token: String = ""

    override val client: OkHttpClient = FnTrust(
        loadPin = { pins[it] },
        savePin = { host, value ->
            if (value == null) {
                pins.remove(host)
                settings.savePin(host, null)
            } else {
                pins[host] = value
                settings.savePin(host, value)
            }
        },
    ).newClient(FnCookies())
        .newBuilder()
        .addInterceptor { chain ->
            val request = chain.request()
            val current = token
            chain.proceed(
                if (current.isEmpty()) request else {
                    request.newBuilder().header("AccessToken", current).build()
                },
            )
        }
        .build()

    private val auth = FnLogin(client)
    private val api = FnApi(client)

    private var session: FnSession? = null

    /**
     * 重登锁。翻页、刷新可能并发触发过期，加锁保证同一时刻只有一个线程去重新握手，
     * 其余线程看到 token 已刷新就直接复用、重试原请求。
     */
    private val authLock = Any()

    val address: FnAddress? get() = session?.address

    fun deviceId(): String {
        val saved = settings.deviceId
        if (saved.isNotEmpty()) return saved
        val fresh = FnLogin.newDeviceId()
        settings.deviceId = fresh
        return fresh
    }

    /** 用存下来的账号登录。失败时把真实原因抛出去，不要在这里吞掉——见 [FnSettings.hasSavedLogin]。 */
    override fun resume(): FnSession = signIn(
        settings.host,
        settings.userName,
        settings.password,
        settings.accessCode,
    )

    override fun signIn(rawHost: String, userName: String, password: String, accessCode: String): FnSession {
        val address = FnAddress.parse(rawHost) ?: throw IllegalArgumentException("地址填错了")
        val fresh = auth.login(
            address = address,
            userName = userName,
            password = password,
            accessCode = accessCode,
            deviceId = deviceId(),
        )
        token = fresh.token
        session = fresh
        return fresh
    }

    private fun requireSession(): FnSession = session ?: throw IllegalStateException("还没登录")

    /**
     * 带「过期自动重登」的数据请求。
     *
     * 飞牛的 `AccessToken` 有有效期，而本应用是挂墙相框，可能连续开机数天。token 失效时
     * [FnApi] 会抛 [FnSessionExpiredException]，这里捕获后用已存的凭据静默重登一次，再重放原请求。
     * 重登失败（密码改了、NAS 关机）就如实把异常抛给上层，由 UI 提示，不会无限循环。
     */
    private inline fun <T> withSessionRetry(crossinline block: (FnSession) -> T): T {
        return try {
            block(requireSession())
        } catch (e: FnSessionExpiredException) {
            synchronized(authLock) {
                // token 已空说明还没人重登过，这次来重登；否则直接用已有的新 token 重放。
                if (token.isEmpty()) {
                    signIn(settings.host, settings.userName, settings.password, settings.accessCode)
                }
                block(requireSession())
            }
        }
    }

    override fun albums(): List<SharedAlbum> = withSessionRetry { api.sharedAlbums(it) }

    override fun photos(albumId: Long, offset: Int, limit: Int): PhotoPage =
        withSessionRetry { api.photos(it, albumId, offset, limit) }

    override fun signOut() {
        token = ""
        session = null
        settings.forget()
    }
}
