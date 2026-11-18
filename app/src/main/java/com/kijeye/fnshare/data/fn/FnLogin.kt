package com.kijeye.fnshare.data.fn

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import kotlin.random.Random

/** 登录没成，但密码没问题——账号开了二次验证。电视上不适合每次输 6 位码。 */
class FnTwoFactorRequiredException(detail: String = "") :
    FnException("这个账号开了二次验证，电视端请在 NAS 上为它关掉。返回字段: $detail")

/** 访问码不对。 */
class FnAccessCodeRejectedException : FnException("访问码错误")

/**
 * `AccessToken` 失效（HTTP 401，或业务码 5000/5001/131072 这类「会话过期」）。
 *
 * 这不是「出错」——仓库层 [com.kijeye.fnshare.data.FnRepo] 捕获它后，用已存的凭据静默重登一次，
 * 再重放原请求。电视相框可能连续开机数天，token 过期后不该让人退出重登。
 */
class FnSessionExpiredException(detail: String = "") :
    FnException("登录状态已过期，正在重新登录。返回字段: $detail")

/**
 * 这台 NAS 开了访问码闸门，而请求里没有访问码。
 *
 * 它不是「出错」——登录页把它翻译成一个弹窗，请人输一次码，其余信息原样保留。
 */
class FnAccessCodeRequiredException :
    FnException("这台 NAS 开了访问码，需要先输入一次")

open class FnException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** 一次成功登录的结果。 */
data class FnSession(val address: FnAddress, val token: String, val userName: String)

/**
 * 登录：先过访问码那道门（如果 NAS 开了），再走 WebSocket 的加密握手。
 *
 * WebSocket 只用来换一个 `AccessToken`；换到之后连接就关掉，之后全走 HTTP。
 * 二次验证需要在这条连接上继续提交动态码，本版不做——电视端更适合直接给账号关掉它。
 */
class FnLogin(private val client: OkHttpClient) {

    /**
     * 访问码校验。返回 true 表示通过（含「这台 NAS 根本没设访问码」的情况）。
     *
     * 门的原理：网关用 `X-Trim-Safe-Code-Challenge` 标出挑战页，WebSocket 升级会直接 401。
     * 通过之后靠 cookie 放行，所以必须和后面的请求共用同一个 [FnCookies]。
     */
    fun passAccessCode(address: FnAddress, code: String): Boolean {
        if (code.isEmpty()) return true
        val request = Request.Builder()
            .url("${address.baseUrl}/access_code_verify")
            .header("x-access-code", FnCrypto.base64(code.toByteArray(Charsets.UTF_8)))
            .header("x-access-source", "web")
            .get()
            .build()
        return client.newCall(request).execute().use { it.isSuccessful }
    }

    /**
     * 访问码这道门有没有开。网关在**任何**路径上都用这个响应头自报家门，
     * 所以探一下首页就够了，不用真的去试登录。
     *
     * 没探测这一步的问题：码留空时 WebSocket 直接 401，表现成「登录超时/被拒绝」，
     * 人根本不知道该去输访问码。
     */
    private fun hasAccessCodeGate(address: FnAddress): Boolean = runCatching {
        client.newCall(Request.Builder().url("${address.baseUrl}/").get().build())
            .execute().use { it.header(CHALLENGE_HEADER) == "1" }
    }.getOrDefault(false)

    fun login(
        address: FnAddress,
        userName: String,
        password: String,
        accessCode: String,
        deviceId: String,
    ): FnSession {
        FnReqId.reset()
        // 访问码只在「门真的开了」的时候才需要：没开闸门的 NAS 上访问码字段根本不出现，
        // 开了闸门而码是空的，直接请人弹窗去输，而不是让 WebSocket 撞死在 401 上。
        if (hasAccessCodeGate(address)) {
            if (accessCode.isEmpty()) throw FnAccessCodeRequiredException()
            if (!passAccessCode(address, accessCode)) throw FnAccessCodeRejectedException()
        }

        val inbox = LinkedBlockingQueue<String>()
        var failure: Throwable? = null
        val listener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                inbox.put(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                failure = t
                inbox.put("""{"result":"fail","errno":-1}""")
            }
        }

        val socket = client.newWebSocket(Request.Builder().url(address.webSocketUrl).build(), listener)
        try {
            socket.send(JSONObject().put("req", REQ_PUBLIC_KEY).put("reqid", FnReqId.next()).toString())
            val hello = JSONObject(await(inbox, failure, 10_000))
            if (hello.optString("result") != "succ") {
                throw FnException(hello.optString("msg").ifEmpty { "取公钥失败" })
            }

            // si 是 18 位整数，但必须原样当字符串回传：转成数字会丢低位，服务器报 errno 8192。
            val si = hello.optString("si")
            val publicKey = FnCrypto.parsePublicKey(hello.optString("pub"))
            val payload = JSONObject()
                .put("req", REQ_LOGIN)
                .put("reqid", FnReqId.next())
                .put("si", si)
                .put("user", userName)
                .put("password", password)
                .put("stay", true)
                .put("deviceType", DEVICE_TYPE)
                .put("deviceName", DEVICE_NAME)
                .put("did", deviceId)

            val aesKey = FnCrypto.newAesKey()
            val iv = FnCrypto.newIv()
            val envelope = JSONObject()
                .put("req", "encrypted")
                .put("iv", FnCrypto.base64(iv))
                .put("rsa", FnCrypto.base64(FnCrypto.rsaEncrypt(publicKey, aesKey)))
                .put("aes", FnCrypto.base64(FnCrypto.aesEncrypt(payload.toString(), aesKey, iv)))
            socket.send(envelope.toString())

            val reply = JSONObject(await(inbox, failure, 15_000))
            FnReqId.setBackId(reply.optString("backId").takeIf { id -> id.length == 16 })

            if (reply.optString("result") != "succ") {
                throw FnException("登录被拒绝：errno=${reply.optInt("errno")} ${reply.optString("msg")}")
            }

            val token = reply.optString("token")
            if (token.isEmpty()) {
                // result 是 succ 却没有 token：密码对了，但服务器在等动态码。
                throw FnTwoFactorRequiredException(
                    "bind=${reply.optBoolean("isBindTwofaSecret")} " +
                        "enforced=${reply.optBoolean("isTwofaEnforced")} " +
                        "trusted=${reply.optBoolean("isTrustedDevice")}",
                )
            }
            return FnSession(address, token, userName)
        } finally {
            socket.close(1000, null)
        }
    }

    private fun await(
        inbox: LinkedBlockingQueue<String>,
        failure: Throwable?,
        timeoutMs: Long,
    ): String {
        val text = inbox.poll(timeoutMs, TimeUnit.MILLISECONDS)
            ?: throw FnException(failure?.message ?: "登录超时，检查地址和端口", failure)
        return text
    }

    companion object {
        private const val REQ_PUBLIC_KEY = "util.crypto.getRSAPub"
        private const val REQ_LOGIN = "user.login"
        private const val DEVICE_TYPE = "AndroidTV"

        /** 网关用来宣布「这台 NAS 有访问码闸门」的响应头。 */
        const val CHALLENGE_HEADER = "X-Trim-Safe-Code-Challenge"

        /**
         * 会出现在 NAS 的登录记录里。别叫「飞牛相册」——那是官方客户端，
         * 混在同一份记录里会分不清谁是谁。
         */
        const val DEVICE_NAME = "FN 共享相册"

        /** 24 位十六进制的设备号。 */
        fun newDeviceId(): String =
            (1..24).map { "0123456789abcdef"[Random.nextInt(16)] }.joinToString("")
    }
}
