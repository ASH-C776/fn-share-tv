package com.kijeye.fnshare.data.fn

import java.util.concurrent.atomic.AtomicInteger

/**
 * 请求 id 是 28 位十六进制：`tttttttt` + `bbbbbbbbbbbbbbbb` + `eeee`。
 *
 * 中间的 16 位一开始是全零，登录成功后换成服务器给的 `backId`。它属于**一次**登录，
 * 所以每次重新握手前必须清掉——否则第二次登录会把上一次的会话 id 报上去。
 */
internal object FnReqId {

    private const val ZERO = "0000000000000000"
    private val counter = AtomicInteger(1)

    @Volatile
    private var backId: String = ZERO

    fun setBackId(value: String?) {
        if (value != null && value.length == 16) backId = value
    }

    fun reset() {
        backId = ZERO
    }

    fun next(): String {
        val seconds = "%08x".format(System.currentTimeMillis() / 1000)
        val seq = "%04x".format(counter.getAndIncrement() and 0xFFFF)
        return seconds + backId + seq
    }
}
