package com.kijeye.fnshare.data.fn

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * 访问码那道门是**靠 cookie 放行的**：`/access_code_verify` 通过之后种一个 cookie，
 * 后面的 WebSocket 升级和每一个照片请求都靠它。
 *
 * OkHttp 默认是 `CookieJar.NO_COOKIES`，什么都不存——所以这个类不是可选项，
 * 没有它访问码永远过不去。
 */
class FnCookies : CookieJar {

    private val store = LinkedHashMap<String, MutableList<Cookie>>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val now = System.currentTimeMillis()
        val list = store.getOrPut(url.host) { mutableListOf() }
        // 先扫掉这个 host 下已经过期的。留着它们不会报错，但会在每次请求里被带出去，
        // 服务端看到一个死 cookie 的反应未必是「当它不存在」。
        list.removeAll { it.expiresAt <= now }
        for (cookie in cookies) {
            // 服务端下发一个已过期的同名 cookie，意思是「删掉它」——照收等于把删除当成了更新。
            // 会话 cookie 的 expiresAt 是 Long.MAX_VALUE，不会误伤。
            if (cookie.expiresAt <= now) continue
            list.removeAll { it.name == cookie.name && it.path == cookie.path }
            list += cookie
        }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> =
        store[url.host].orEmpty().filter { it.matches(url) }

    @Synchronized
    fun clear() = store.clear()
}
