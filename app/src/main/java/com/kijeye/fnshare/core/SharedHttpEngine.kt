package com.kijeye.fnshare.core

import okhttp3.OkHttpClient

/**
 * 该代码是从 `data/FnDataSource.kt:44-48` 的注释与 `ui/AppViewModel.kt:68-96` 两处用法
 * 提取的（结构化重构第 5 批）：图片加载（Coil）、视频取流（ExoPlayer）、接口请求
 * 必须复用**同一个** OkHttpClient——缩略图与视频流不要签名但要 `AccessToken`，
 * 凭证挂在客户端拦截器上，换成别的实例就会全漏。
 *
 * 原实现里这个共享点是 `AppViewModel.repo.client`（VM 直接伸手进 data 层的具体类）。
 * 本接口把它提到 core，由 `di/AppContainer` 单点持有；VM 只从容器拿
 * `ImageEngine` 和 `DataSource.Factory`，不再认识 OkHttp。
 *
 * 对应的生命周期清理应该在原文件的哪个位置调用：
 * 【无需清理】。OkHttpClient 的线程池与连接池随进程消亡，没有需要显式 close 的 fd；
 * `AppViewModel.onCleared():1198` 与 `MainActivity.onDestroy()` 都不为此新增调用。
 * 真正需要 `release()` / `close()` 的是音频播放器与 ImageLoader，不是本对象。
 */
interface SharedHttpEngine {

    /** 全进程唯一的 OkHttpClient，含证书 pin 与 AccessToken 拦截器。 */
    val client: OkHttpClient
}
