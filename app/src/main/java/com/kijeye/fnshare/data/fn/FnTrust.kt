package com.kijeye.fnshare.data.fn

import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 飞牛 NAS 用的是自签证书（`O=fnOS, CN=fnOS`，三个月一换），系统信任链永远过不去。
 *
 * 这里做的是「首次见面就记住」：信任任何证书，但把第一次见到的指纹按主机记下来，
 * 之后再见到不同的就拒绝一次并清掉记录——既保证加密，也能发现中间人换了证书。
 * 证书每三个月续期一次，所以「变了」是常态，不能直接锁死，否则用户只有遥控器，没法修。
 */
class FnTrust(
    private val loadPin: (host: String) -> String?,
    private val savePin: (host: String, fingerprint: String?) -> Unit,
) {

    val trustManager: X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    val hostnameVerifier = HostnameVerifier { host, session ->
        val cert = runCatching { session.peerCertificates.first() as X509Certificate }.getOrNull()
            ?: return@HostnameVerifier false
        val fingerprint = sha256(cert.encoded)
        val known = loadPin(host)
        when {
            known == null -> {
                savePin(host, fingerprint)
                true
            }
            known == fingerprint -> true
            else -> {
                // 清掉旧记录，下一次刻意的登录会重新记住新证书。
                savePin(host, null)
                false
            }
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun newClient(cookies: FnCookies): OkHttpClient {
        val context = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustManager), null)
        }
        return OkHttpClient.Builder()
            .cookieJar(cookies)
            .sslSocketFactory(context.socketFactory, trustManager)
            .hostnameVerifier(hostnameVerifier)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
