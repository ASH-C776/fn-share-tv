package com.kijeye.fnshare.domain.port

import com.kijeye.fnshare.domain.model.LyricsDoc

/**
 * 该代码是从哪个原文件提取的：
 * 【新增契约，无函数体被搬动】。签名逐字镜像 `data/lyrics/LyricsRepo.kt:43` 的
 * `suspend fun load(entryId: String): LyricsDoc?`。原 `LyricsRepo` 内的 LRU 缓存、
 * File 直读 / MediaStore `content://` 兜底 / MediaScanner 触发，第 3 批由
 * `LocalLyricsSource` 原样包住，一行不改。
 *
 * 注意：目录树里曾列 `clearCache()`，**现有 `LyricsRepo` 并没有这个方法**；
 * 为遵守「禁止顺手加功能、不改写业务逻辑」，本批不凭空声明它。日后确需清空 LRU 时
 * 再单独加，且只能是实现类自己的方法，不塞进接口。
 *
 * 对应的生命周期清理应该在原文件的哪个位置调用：
 * 【本接口无需清理】。`load()` 是一次性 suspend，不持有定时器。
 * 现有 LRU 是内存 `LinkedHashMap`（上限 8 首），随 `LyricsRepo` 实例一起被 GC；
 * `LyricsRepo` 由 `AppViewModel` 持有，其生命周期终点是 `AppViewModel.onCleared():1198`。
 * 歌词**行号轮询**不在这里，仍在 `ui/AppViewModel.kt:973-1012`，第 4 批抽成
 * `DefaultLyricsTicker` 后其 `cancel()` 挂到 `onCleared():1198`。
 *
 * 线程约定：实现方必须在 `Dispatchers.IO` 上读文件 / 解析 LRC（现有 `LyricsRepo.load`
 * 已自带 `withContext(Dispatchers.IO)`），主线程只消费返回的 [LyricsDoc]。
 */
interface LyricsSource {

    /**
     * 找并解析这首歌的歌词；任何失败都安静返回 null（上层据此整体隐藏歌词浮层）。
     * 必须在 `Dispatchers.IO` 上执行。
     */
    suspend fun load(trackId: String): LyricsDoc?
}
