package com.kijeye.fnshare.data.local

import com.kijeye.fnshare.core.AppDispatchers
import com.kijeye.fnshare.data.cache.AlbumCache
import com.kijeye.fnshare.domain.model.AlbumSnapshot
import com.kijeye.fnshare.domain.model.CacheStats
import com.kijeye.fnshare.domain.model.SharedAlbum
import com.kijeye.fnshare.domain.model.SharedPhoto
import com.kijeye.fnshare.domain.port.AlbumCacheSource
import kotlinx.coroutines.withContext

/**
 * 该代码是从哪个原文件提取的：
 * 【新增适配器，原文件零改动】。九个方法逐个转发到
 * `data/cache/AlbumCache.kt:66,97,131,163,192,211,233,238,254` 的
 * `read / readAlbums / write / writeAlbums / readManifest / writeManifest /
 * clearAll / stats / trim`。
 * 缓存目录（`filesDir/album_cache/`）、JSON 编码、失效判据（`photoCount` 快照比对）、
 * `trim()` 的淘汰顺序 **全部留在 `AlbumCache` 里，本文件一个字节都不碰**。
 *
 * 唯一的类型转换：`AlbumCache.Entry` → [AlbumSnapshot]、`AlbumCache.Stats` → [CacheStats]。
 * 两者是逐字段一一对应的纯 DTO（5 字段 / 2 字段），这里只做构造，无分支、无判断、
 * 不改写缓存命中或失效逻辑。Kotlin 不允许在类内部声明 typealias，所以没法让原类
 * 直接返回 domain 类型；用适配器对拷是改动面最小的做法。
 *
 * 对应的生命周期清理应该在原文件的哪个位置调用：
 * 【无需清理】。`read/write` 都是一次性开闭文件，不持有长生命周期 fd、定时器或
 * 协程作用域。`clearAll()` 是业务动作（退出登录时清掉缓存里的拍摄地点和文件名），
 * **不是**生命周期清理方法，调用位置仍是 `ui/AppViewModel.kt` 退出登录处，
 * 不要挪到 `MainActivity.onDestroy()` / `AppViewModel.onCleared():1198`。
 *
 * 线程约定：原 `AlbumCache` 九个方法都是同步的，由 `AppViewModel` 在
 * `withContext(Dispatchers.IO)` 里调用；本适配器把这些 IO 显式收进
 * [AppDispatchers.io]，第 5 批接线后 ViewModel 侧只保留主线程 StateFlow 更新。
 */
class DiskAlbumCacheSource(private val delegate: AlbumCache) : AlbumCacheSource {

    override suspend fun read(
        host: String,
        albumId: Long,
        baseUrl: String,
        firstBatchSize: Int,
        onFirstBatch: ((List<SharedPhoto>) -> Unit)?,
    ): AlbumSnapshot? = withContext(AppDispatchers.io) {
        // 0.24.5：首批回调原样透传，不在这里做缓冲或批处理——晚一帧就白改了。
        delegate.read(host, albumId, baseUrl, firstBatchSize, onFirstBatch)?.let { e ->
            AlbumSnapshot(
                albumId = e.albumId,
                albumName = e.albumName,
                photoCount = e.photoCount,
                savedAt = e.savedAt,
                photos = e.photos,
            )
        }
    }

    override suspend fun readAlbums(host: String, baseUrl: String): List<SharedAlbum>? =
        withContext(AppDispatchers.io) { delegate.readAlbums(host, baseUrl) }

    override suspend fun write(
        host: String,
        album: SharedAlbum,
        photos: List<SharedPhoto>,
        baseUrl: String,
    ) = withContext(AppDispatchers.io) { delegate.write(host, album, photos, baseUrl) }

    override suspend fun writeAlbums(host: String, albums: List<SharedAlbum>, baseUrl: String) =
        withContext(AppDispatchers.io) { delegate.writeAlbums(host, albums, baseUrl) }

    override suspend fun readManifest(host: String): Map<Long, Int> =
        withContext(AppDispatchers.io) { delegate.readManifest(host) }

    override suspend fun writeManifest(host: String, albumId: Long, photoCount: Int, savedAt: Long) =
        withContext(AppDispatchers.io) { delegate.writeManifest(host, albumId, photoCount, savedAt) }

    /**
     * 同步转发，见 `domain/port/AlbumCacheSource.clearAll` 的说明：
     * `ui/AppViewModel.kt:1252` 的 `signOut()` 不是 suspend，清完缓存紧接着要统计，
     * 改成异步会引入竞态。原 `AlbumCache.clearAll()` 本来就是同步调用，行为零变化。
     */
    override fun clearAll() = delegate.clearAll()

    override suspend fun stats(): CacheStats = withContext(AppDispatchers.io) {
        delegate.stats().let { s -> CacheStats(bytes = s.bytes, albums = s.albums) }
    }

    override suspend fun trim(maxBytes: Long) =
        withContext(AppDispatchers.io) { delegate.trim(maxBytes) }
}
