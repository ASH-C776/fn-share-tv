package com.kijeye.fnshare.data.fn

import kotlin.random.Random

/**
 * `authx` 请求头。
 *
 * ```
 * paramHash = md5(参数按键名排序后，以 k=v 用 & 连起来)
 * sign      = md5(SALT_path_nonce_timestamp_paramHash_SECRET)
 * authx     = nonce=<6 位>&timestamp=<毫秒>&sign=<sign>
 * ```
 *
 * 两个容易踩的点：`path` 必须带 `/p` 前缀；`timestamp` 是毫秒，不是秒。参数参与哈希时
 * 用的是**解码后的值**，所以调用方传进来的是原始字符串，不要预先 URL 编码。
 */
internal object FnSign {

    fun authx(
        path: String,
        params: Map<String, String>,
        timestamp: Long = System.currentTimeMillis(),
        nonce: String = newNonce(),
    ): String {
        val paramHash = md5Hex(
            params.toSortedMap().entries.joinToString("&") { "${it.key}=${it.value}" },
        )
        val sign = md5Hex(
            listOf(FnConst.SALT, path, nonce, timestamp.toString(), paramHash, FnConst.SIGN_SECRET)
                .joinToString("_"),
        )
        return "nonce=$nonce&timestamp=$timestamp&sign=$sign"
    }

    private fun newNonce(): String = (100_000 + Random.nextInt(900_000)).toString()
}
