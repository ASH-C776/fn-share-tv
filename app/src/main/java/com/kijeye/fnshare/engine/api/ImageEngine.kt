package com.kijeye.fnshare.engine.api

import coil3.request.ImageRequest

/**
 * 图片加载引擎的契约（结构化重构第 4 批）。
 *
 * 该代码是从以下两处提取的：
 *  - `ui/AppViewModel.kt:68-84`（ImageLoader 构造：OkHttp fetcher / 64MB 内存 / 3GB 磁盘）
 *  - `MainActivity.kt:96-98`（`SingletonImageLoader.setSafe`，必须在 setContent 之前）
 *  - `ui/ViewerScreen.kt:481-498`（预取 `imageLoader.enqueue`）
 *
 * 接口不暴露 `ImageLoader` 实例：UI 只能拿到请求、发起预取、读磁盘占用。
 *
 * ⚠️ 全局只能有一个实例。Coil 的磁盘缓存目录是 `filesDir/coil3_disk_cache`（0.24.4 起），
 * 同目录起两个 ImageLoader 会损坏 journal。接线时由 `di/AppContainer` 单点构造，
 * `MainActivity` 那行 `SingletonImageLoader.setSafe { model.imageLoader }` 改成
 * `container.imageEngine.install()`——仍然是 setContent 之前，时序不变。
 *
 * 生命周期清理：`close()` 保留在契约里，但第 5/6 批接线后**不调用**：
 * 容器是进程级单例，`onCleared()` 里 close 会让 VM 重建后全局仍指向已 shutdown 的
 * loader（满屏灰块）。`AppViewModel.onCleared()` 只 release 音频引擎与歌词节拍器。
 */
interface ImageEngine {

    /** 注册全局 ImageLoader。必须在任何 Compose 内容组合之前调用。 */
    fun install()

    /** 按当前屏幕像素产出请求工厂；viewport 变化时重新取一个，不改引擎内部状态。 */
    fun requestFactory(
        screenWpx: Int,
        screenHpx: Int,
        previewOriginal: () -> Boolean,
    ): ImageRequestFactory

    /** 把一份请求排进预取队列（不等待结果）。原 `imageLoader.enqueue(it)`。 */
    fun enqueue(request: ImageRequest)

    /** 磁盘缓存当前占用的字节数，供设置页显示「离满还差多少」。 */
    fun diskBytes(): Long

    /**
     * 磁盘缓存上限（第 5 批补：原 `ui/AppViewModel.kt:725` 的 `disk?.maxSize ?: 0L`）。
     * 设置页拿它当分母。Coil 3.5.0 的 `maxSize` 是同步属性，不是 suspend。
     */
    fun diskMaxBytes(): Long

    /** 清空磁盘缓存（第 5 批补：原 `ui/AppViewModel.kt:741` 的 `diskCache?.clear()`）。 */
    fun clearDisk()

    /**
     * 释放 ImageLoader（关闭磁盘缓存 journal 与线程池）。
     *
     * ⚠️ 第 5 批接线后**不调用**：基线的 `AppViewModel.onCleared()` 只 release 音频播放器，
     * 从未 shutdown ImageLoader；而容器是进程级单例，提前 close 会让 VM 重建后满屏灰块。
     * 保留在契约里是为了让「引擎拥有资源、有权释放」这件事显式可见，由未来的
     * Application 级生命周期统一管理。
     */
    fun close()
}
