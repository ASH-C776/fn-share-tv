package com.kijeye.fnshare.ui

import androidx.compose.runtime.snapshots.SnapshotStateMap
import java.util.Locale

/**
 * 各图片 URL 对应的真实字节数（Coil 磁盘缓存里那份源的字节大小）。
 *
 * 键是 [com.kijeye.fnshare.ui.ViewerScreen.photoRequest] 里传给 Coil 的 URL 字符串。
 * 值在图片加载成功时由该请求的监听写进来——无论是网络新拉还是命中磁盘缓存，
 * 成功回调都会跑，所以「原图/缩略图」都会拿到各自真实的字节数。
 *
 * 用 [SnapshotStateMap] 而非普通 Map：监听在 IO 线程写、信息栏在主线程读，
 * 改动要能触发信息栏重组，普通 Map 做不到。
 */
object ImageSizes {
    /**
     * 兼容委托：真正的表已搬到 `core/ImageSizes.kt`（结构化重构第 4 批，逐字搬移）。
     * 这里只转发，不新建第二张表——否则引擎写入和 UI 读取会落到两个实例上。
     * 生命周期清理：无需清理，见 `core/ImageSizes.kt` 头注。
     */
    val map: SnapshotStateMap<String, Long>
        get() = com.kijeye.fnshare.core.ImageSizes.map
}

/** 把字节数格式化成电视上易读的形式：B / KB / MB。原图十几 MB、1080p 预览约 200KB。 */
fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return ""
    val kb = bytes / 1024.0
    return when {
        kb < 1 -> "$bytes B"
        kb < 1024 -> "%.0f KB".format(Locale.US, kb)
        else -> "%.1f MB".format(Locale.US, kb / 1024.0)
    }
}

/**
 * 缓存占用的格式：B / KB / MB / GB。
 *
 * 和 [formatBytes] 分开是因为两处语义不同：
 *  - 照片大小为 0 是「不知道」，所以 [formatBytes] 返回空串；
 *  - 缓存占用为 0 是「还没缓存过」，是正常状态，要显示「0 B」。
 * 缓存上限是 GB 级的，MB 那套不够用。
 */
fun formatCacheBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val kb = bytes / 1024.0
    return when {
        kb < 1 -> "$bytes B"
        kb < 1024 -> "%.0f KB".format(Locale.US, kb)
        kb < 1024 * 1024 -> "%.1f MB".format(Locale.US, kb / 1024.0)
        else -> "%.2f GB".format(Locale.US, kb / (1024.0 * 1024.0))
    }
}
