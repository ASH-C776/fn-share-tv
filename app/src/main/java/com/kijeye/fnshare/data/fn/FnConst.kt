package com.kijeye.fnshare.data.fn

/**
 * 写死在 fnOS 网页前端里的签名材料。
 *
 * 这两个值不是按安装生成的，所有 fnOS 都是一样的；它们也不是登录返回的那个
 * `secret`（那个只用于 WebSocket 消息签名）。
 */
internal object FnConst {
    const val SALT = "NDzZTVxnRKP8Z0jXg1VAMonaG8akvh"
    const val SIGN_SECRET = "EAECCF25-80A6-4666-A7C2-A76904A74AB6"

    /** fnOS 的默认端口：5666 明文、5667 TLS。用户改过的话地址里会带端口。 */
    const val HTTP_PORT = 5666
    const val HTTPS_PORT = 5667
}
