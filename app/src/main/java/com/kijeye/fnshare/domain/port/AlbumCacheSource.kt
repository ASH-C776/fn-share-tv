package com.kijeye.fnshare.domain.port

import com.kijeye.fnshare.domain.model.AlbumManifestInfo
import com.kijeye.fnshare.domain.model.AlbumSnapshot
import com.kijeye.fnshare.domain.model.CacheStats
import com.kijeye.fnshare.domain.model.SharedAlbum
import com.kijeye.fnshare.domain.model.SharedPhoto

/**
 * 该代码是从哪个原文件提取的：
 * 【新增契约，无函数体被搬动】。九个方法逐字镜像
 * `data/cache/AlbumCache.kt:66,97,131,163,192,211,233,238,254` 的
 * `read / readAlbums / write / writeAlbums / readManifest / writeManifest /
 * clearAll / stats / trim` 的参数与语义。
 * 区别只有一处：读方法的返回类型从嵌套类 `AlbumCache.Entry` / `Stats` 换成
 * `domain/model` 里的逐字段镜像 [AlbumSnapshot] / [CacheStats]（Kotlin 不支持类内
 * typealias，直接搬嵌套类会逼着改原类签名）。第 3 批适配器做纯字段对拷。
 *
 * 缓存目录、JSON 编码、失效判据（`photoCount` 快照比对）、`trim()` 的淘汰顺序
 * **全部留在 `AlbumCache` 里，本接口一个字节都不碰**。
 *
 * 对应的生命周期清理应该在原文件的哪个位置调用：
 * 【本接口无需清理】。没有定时器、协程作用域或打开的 fd（`read/write` 都是一次性开闭文件）。
 * `clearAll()` 是业务动作（退出登录时清缓存），**不是**生命周期清理方法，
 * 调用位置仍是 `ui/AppViewModel.kt` 退出登录处，不要挪到 `onDestroy()`。
 *
 * 线程约定：现有 `AlbumCache` 的方法都是同步的，由 `AppViewModel` 在
 * `withContext(Dispatchers.IO)` 里调用。本接口除 [clearAll] 外统一声明为 `suspend`，
 * 第 3 批的 `DiskAlbumCacheSource` 在 `Dispatchers.IO` 上转发，保证「禁止主线程耗时操作」；
 * 原 `AlbumCache` 的同步签名与函数体保持原样不动。接线时那些外层 `withContext` 去掉
 * （适配器内部已自带 IO），调度结果等价、调用顺序不变。
 */
interface AlbumCacheSource {

    /**
     * 读一本相册的清单。
     *
     * [firstBatchSize] / [onFirstBatch]（0.24.5）：解析到前 N 条先回调一次，让「打开相册」
     * 能在整本读完之前就把首屏画出来；默认 0 = 旧行为，整本读完才返回。
     * 回调在 IO 线程上发生，调用方要自己保证 state 更新是线程安全的。
     */
    suspend fun read(
        host: String,
        albumId: Long,
        baseUrl: String,
        firstBatchSize: Int = 0,
        onFirstBatch: ((List<SharedPhoto>) -> Unit)? = null,
    ): AlbumSnapshot?

    suspend fun readAlbums(host: String, baseUrl: String): List<SharedAlbum>?

    /**
     * [sig] 是前 24 张照片 id 的指纹，刷新重建时传进来；
     * 「翻完页写回当前列表」那条路不传，由 AlbumCache 保留已有指纹。
     */
    suspend fun write(
        host: String,
        album: SharedAlbum,
        photos: List<SharedPhoto>,
        baseUrl: String,
        sig: String = "",
    )

    suspend fun writeAlbums(host: String, albums: List<SharedAlbum>, baseUrl: String)

    /** 相册 id → 建缓存时相册列表报的 photoCount，用于判断缓存是否过期。 */
    suspend fun readManifest(host: String): Map<Long, Int>

    /** 完整快照（张数 + 前 N 张指纹），给手动刷新的精筛判据用。 */
    suspend fun readManifestInfo(host: String): Map<Long, AlbumManifestInfo>

    suspend fun writeManifest(host: String, albumId: Long, photoCount: Int, savedAt: Long)

    /**
     * 退出登录时清空（里面有拍摄地点和文件名）。不是生命周期清理。
     *
     * 第 5 批修正：这一项**保持同步**，不随其余方法改成 suspend。原
     * `ui/AppViewModel.kt:1252` 的 `signOut()` 是非 suspend 函数，直接同步调
     * `albumCache.clearAll()`；改成 suspend 就得丢进 `viewModelScope.launch`，
     * 清缓存会和紧随其后的 `refreshCacheStats()` 竞态，设置页可能显示清之前的旧数字。
     * 保持同步 = 保持「先清完、再统计」的既有顺序。
     */
    fun clearAll()

    suspend fun stats(): CacheStats

    suspend fun trim(maxBytes: Long)
}
