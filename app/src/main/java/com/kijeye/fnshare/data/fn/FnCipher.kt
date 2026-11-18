package com.kijeye.fnshare.data.fn

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 把敏感字段（密码、访问码）在落盘前用 AndroidKeyStore 里的 AES-256-GCM 密钥加密。
 *
 * 明文只在内存里出现一次，SharedPreferences 里存的是密文——电视丢了或被 root 提走，
 * 别人也读不到 NAS 账号密码。密钥由系统硬件-backed 的 Keystore 保管，应用进程里
 * 拿不到明文密钥本身，只能拿去加解密。
 *
 * minSdk 23 以上 AndroidKeyStore 支持 AES/GCM，无需任何额外依赖。
 */
object FnCipher {

    private const val ANDROID_KEY_STORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "fn_share_secret"
    private const val ALGORITHM = KeyProperties.KEY_ALGORITHM_AES
    private const val BLOCK_MODE = KeyProperties.BLOCK_MODE_GCM
    private const val PADDING = KeyProperties.ENCRYPTION_PADDING_NONE
    private const val TRANSFORMATION = "$ALGORITHM/$BLOCK_MODE/$PADDING"
    private const val KEY_SIZE = 256
    private const val IV_LENGTH = 12 // GCM 默认 96-bit IV
    private const val GCM_TAG_BITS = 128

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
    }

    private fun secretKey(): SecretKey {
        val existing = keyStore.getEntry(KEY_ALIAS, null)
        if (existing is KeyStore.SecretKeyEntry) return existing.secretKey
        val generator = KeyGenerator.getInstance(ALGORITHM, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(KEY_SIZE)
                .setBlockModes(BLOCK_MODE)
                .setEncryptionPaddings(PADDING)
                .build(),
        )
        return generator.generateKey()
    }

    /** 明文 → Base64(IV ‖ 密文)。空串直接返回空串，避免落一堆无意义密文。 */
    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val out = ByteArray(iv.size + encrypted.size)
        System.arraycopy(iv, 0, out, 0, iv.size)
        System.arraycopy(encrypted, 0, out, iv.size, encrypted.size)
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    /**
     * Base64(IV ‖ 密文) → 明文。空串返回空。解密失败（密钥随系统重置丢了、或拿到的根本不是
     * 我们家的密文）返回空串，让上层当作「没登录过」重新要密码，而不是崩。
     */
    fun decrypt(cipherText: String): String {
        if (cipherText.isEmpty()) return ""
        return runCatching {
            val data = Base64.decode(cipherText, Base64.NO_WRAP)
            val iv = data.copyOfRange(0, IV_LENGTH)
            val encrypted = data.copyOfRange(IV_LENGTH, data.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        }.getOrDefault("")
    }
}
