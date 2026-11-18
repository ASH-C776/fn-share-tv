package com.kijeye.fnshare.domain.model

/**
 * 该代码是从哪个原文件提取的：
 * 字段与注释逐字镜像 `data/cache/AlbumCache.kt:41-56` 的嵌套类 `AlbumCache.Entry`
 * 与 `AlbumCache.Stats`；**本批不改动 `AlbumCache.kt` 一行**，因为 Kotlin 不允许在类内部
 * 声明 `typealias`，硬把嵌套类改成 typealias 会逼着改原类的返回类型签名。
 * 第 3 批的 `DiskAlbumCacheSource` 适配器负责把 `Entry` / `Stats` 逐字段对拷成本类型，
 * 那是纯构造、无分支、无判断，不改变任何缓存命中/失效逻辑。
 *
 * 对应的生命周期清理应该在原文件的哪个位置调用：
 * 【无需清理】。两个不可变数据类，不持有文件句柄、线程或 Context。
 * 真正的磁盘目录 `filesDir/album_cache/` 由 `AlbumCache` 自己管，
 * 其 `clearAll()` 的调用位置仍是 `ui/AppViewModel.kt` 退出登录处，本批不触碰。
 */

/**
 * 一本相册的缓存内容。
 *
 * [photoCount] 是建这份缓存时相册列表里报的值。失效判据只看它，**不**看缓存里实际
 * 存了多少条：`/album/photos` 返回的列表口径和 `photoCount` 可能不同（照片接口把视频
 * 也混在列表里），两个数直接比会误判。
 */
data class AlbumSnapshot(
    val albumId: Long,
    val albumName: String,
    val photoCount: Int,
    val savedAt: Long,
    val photos: List<SharedPhoto>,
)

/** 缓存占用情况，给设置页显示。 */
data class CacheStats(val bytes: Long, val albums: Int)
