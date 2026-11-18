package com.kijeye.fnshare.data.fn

import java.security.KeyFactory
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString

/**
 * 登录用的那层加密：正文走 AES-256-CBC，AES 的密钥本身再用服务器的 RSA 公钥加密。
 *
 * 公钥是 X.509（`SubjectPublicKeyInfo`）形式的 PEM，去掉头尾之后可以直接当
 * `X509EncodedKeySpec` 用，不用补 DER 头。
 *
 * base64 用 okio 而不是 `android.util.Base64`：后者在 JVM 单测里是个会抛异常的桩，
 * 而协议层的单测必须能在没有设备的情况下跑。
 */
internal object FnCrypto {

    private val random = SecureRandom()
    private val alphabet = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"

    /** 32 个字符的 ASCII 当 AES 密钥——服务器要的就是这个，不是 32 字节随机数。 */
    fun newAesKey(): ByteArray =
        (1..32).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("").toByteArray()

    fun newIv(): ByteArray = ByteArray(16).also { random.nextBytes(it) }

    fun aesEncrypt(plain: String, key: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
    }

    fun rsaEncrypt(publicKey: PublicKey, aesKey: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)
        return cipher.doFinal(aesKey)
    }

    fun parsePublicKey(pem: String): PublicKey {
        val body = pem
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace("\\s".toRegex(), "")
        val der = body.decodeBase64() ?: error("公钥不是合法的 base64")
        return KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(der.toByteArray()))
    }

    fun base64(bytes: ByteArray): String = bytes.toByteString().base64()
}
