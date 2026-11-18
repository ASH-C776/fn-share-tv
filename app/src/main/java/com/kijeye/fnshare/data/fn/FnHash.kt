package com.kijeye.fnshare.data.fn

import java.security.MessageDigest

/** 签名用的是小写十六进制 MD5。 */
internal fun md5Hex(text: String): String =
    MessageDigest.getInstance("MD5")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
