package com.kijeye.fnshare.data.lyrics

import com.kijeye.fnshare.core.AppDispatchers
import com.kijeye.fnshare.domain.model.LyricsDoc
import com.kijeye.fnshare.domain.port.LyricsSource
import kotlinx.coroutines.withContext

/**
 * 该代码是从哪个原文件提取的：
 * 【新增适配器，原文件零改动】。唯一方法转发到 `data/lyrics/LyricsRepo.kt:43` 的
 * `suspend fun load(entryId: String): LyricsDoc?`。
 * LRU 缓存（上限 8 首）、File 直读、`MediaStore.Files` 的 `content://` 兜底、
 * `MediaScannerConnection.scanFile` 触发、`LrcParser.parse()` 解析
 * **全部留在 `LyricsRepo` 内，本文件没有复制或改写任何一行**。
 *
 * 对应的生命周期清理应该在原文件的哪个位置调用：
 * 【本适配器无需清理】。`LyricsRepo` 的 LRU 是内存 `LinkedHashMap`，随实例被 GC；
 * 它由 `AppViewModel` 持有，生命周期终点是 `AppViewModel.onCleared():1198`。
 * 真正需要显式 `cancel()` 的是**歌词行号轮询循环**（`AppViewModel.kt:973-1012` 的
 * 100ms `while(isActive)`），第 4 批抽成 `DefaultLyricsTicker` 后挂到
 * `onCleared():1198`，与现有 `musicPlayer.release()` 并列——**不要挂到这里**。
 *
 * 线程约定：[load] 显式在 [AppDispatchers.io] 上执行（`LyricsRepo.load()` 内部
 * 本来就自带 `withContext(Dispatchers.IO)`，外层是同一 dispatcher 的幂等调度）。
 * 主线程只消费返回的 [LyricsDoc]，不碰文件 IO。
 */
class LocalLyricsSource(private val delegate: LyricsRepo) : LyricsSource {

    override suspend fun load(trackId: String): LyricsDoc? = withContext(AppDispatchers.io) {
        delegate.load(trackId)
    }
}
