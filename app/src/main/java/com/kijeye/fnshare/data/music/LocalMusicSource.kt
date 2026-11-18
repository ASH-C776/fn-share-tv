package com.kijeye.fnshare.data.music

import com.kijeye.fnshare.core.AppDispatchers
import com.kijeye.fnshare.domain.model.MusicEntry
import com.kijeye.fnshare.domain.port.MusicSource
import kotlinx.coroutines.withContext

/**
 * 该代码是从哪个原文件提取的：
 * 【新增适配器，原文件零改动】。三个成员逐个转发到 `data/music/MusicScanner.kt:43,50,57`
 * 的 `requiredPermission` / `hasPermission()` / `suspend scan()`。
 * MediaStore 遍历、路径黑名单、`musicIdFromPath()` 的 id 编码、去重、排序
 * **全部留在 `MusicScanner` 内，本文件没有复制或改写任何一行**。
 *
 * 对应的生命周期清理应该在原文件的哪个位置调用：
 * 【无需清理】。`MusicScanner` 无状态、无定时器、无协程作用域、无打开的文件句柄；
 * 本适配器只多一个 delegate 引用。`MainActivity.onDestroy()` /
 * `AppViewModel.onCleared():1198` 不需要新增调用。
 * 注意：`SlideshowMusicPlayer`（ExoPlayer）**不属于本类**，它的 `release()` 仍在
 * `AppViewModel.onCleared():1199` 调用，第 4 批抽成 `ExoAudioEngine` 后位置不变。
 *
 * 线程约定：[scan] 显式在 [AppDispatchers.io] 上执行（`MusicScanner.scan()` 内部
 * 本来就自带一次 `withContext(Dispatchers.IO)`，外层再包一层是同一 dispatcher 的
 * 幂等调度，不改变顺序与结果），结果由 ViewModel 在主线程写进 StateFlow。
 * [hasPermission] 是同步的纯权限查询，按现有签名保持原样，不做 IO。
 */
class LocalMusicSource(private val delegate: MusicScanner) : MusicSource {

    override val requiredPermission: String get() = delegate.requiredPermission

    override fun hasPermission(): Boolean = delegate.hasPermission()

    override suspend fun scan(): List<MusicEntry> = withContext(AppDispatchers.io) {
        delegate.scan()
    }
}
