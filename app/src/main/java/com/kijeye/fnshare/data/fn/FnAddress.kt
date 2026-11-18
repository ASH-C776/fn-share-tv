package com.kijeye.fnshare.data.fn

/**
 * 地址解析：用户输入 `192.168.1.66`、`192.168.1.66:5667`、`https://nas.local:5667`
 * 都得能认。
 */
data class FnAddress(val host: String, val port: Int, val secure: Boolean) {

    val baseUrl: String get() = "${if (secure) "https" else "http"}://$host:$port"

    val webSocketUrl: String
        get() = "${if (secure) "wss" else "ws"}://$host:$port/websocket?type=main"

    /** 写回设置里的规范形式，不带协议前缀。 */
    val display: String get() = "$host:$port"

    companion object {
        fun parse(raw: String): FnAddress? {
            var text = raw.trim()
            if (text.isEmpty()) return null

            var secure = true
            when {
                text.startsWith("https://", ignoreCase = true) -> {
                    secure = true
                    text = text.substring(8)
                }
                text.startsWith("http://", ignoreCase = true) -> {
                    secure = false
                    text = text.substring(7)
                }
            }
            text = text.trimEnd('/')
            if (text.isEmpty()) return null

            // IPv6 字面量是 [::1]:5667 这种形状，方括号里的冒号不是端口分隔符。
            val host: String
            val portText: String?
            if (text.startsWith("[")) {
                val close = text.indexOf(']')
                if (close < 0) return null
                host = text.substring(1, close)
                portText = text.substring(close + 1).removePrefix(":")
            } else {
                val colon = text.lastIndexOf(':')
                if (colon >= 0 && text.indexOf(':') == colon) {
                    host = text.substring(0, colon)
                    portText = text.substring(colon + 1)
                } else {
                    host = text
                    portText = null
                }
            }
            if (host.isEmpty()) return null

            val port = portText?.toIntOrNull()
                ?: if (secure) FnConst.HTTPS_PORT else FnConst.HTTP_PORT
            if (port !in 1..65535) return null

            return FnAddress(host, port, secure)
        }
    }
}
