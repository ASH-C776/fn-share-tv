package com.kijeye.fnshare.data.music.fn

/**
 * 该代码是从哪个原文件提取的：
 * 【新增实现，无函数体被搬动】。飞牛音乐是 NAS 上**另一个端口的另一个服务**，
 * 相册的 baseUrl 不能直接拿来用；这里只负责把相册地址里的主机名推成音乐服务的默认地址。
 *
 * 对应的生命周期清理应该在原文件的哪个位置调用：
 * 【无需清理】。纯字符串函数，不持有任何对象。
 *
 * 线程约定：纯函数，任意线程可调用；结果由 `di/AppContainer` 包进 provider，
 * 设置页改地址后下一次请求立刻取到新值。
 *
 * ⚠️ 默认地址只是**登录表单的初始值**，用户可以整个改掉（域名反代、非 5666 端口、
 * https 都行）。不要把这里的结果当成不可覆盖的事实写死。
 */
fun defaultFnMusicBaseUrl(albumHost: String): String {
    val raw = albumHost.trim()
    if (raw.isEmpty()) return ""
    // 去掉 scheme / 路径，只留 host[:port]，再丢掉相册自己的端口。
    // IPv6 字面量不在此支持：家里这台 NAS 走 IPv4 或域名，需要时由用户在表单里手填。
    val authority = raw
        .substringAfter("://", raw)
        .substringBefore('/')
        .substringBefore('?')
        .trim()
    val hostname = authority.substringBeforeLast(':', authority).trim()
    if (hostname.isEmpty()) return ""
    return "http://$hostname:5666${FnMusicApi.DEFAULT_PATH_SUFFIX}"
}
