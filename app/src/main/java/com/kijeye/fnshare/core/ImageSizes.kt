package com.kijeye.fnshare.core

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap

/**
 * 该代码是从 `ui/ImageSizes.kt` 的 `object ImageSizes` 逐字提取的（结构化重构第 4 批）。
 *
 * 搬走的原因：Coil 的 `onSuccess` 回调里要写这张表，而回调实现下沉到 `engine/impl`
 * 之后，若还引用 `ui` 包就是「内层反向引用 UI 层」，违反单向依赖。`ui/ImageSizes.kt`
 * 保留一个同名 object 作委托，所有现有调用点（`ui/ViewerScreen.kt`）一行不改。
 *
 * 生命周期清理：**无需清理**。这是一张进程级只读快照表，没有定时器、协程、fd 或监听器；
 * 它随进程一起消亡，不需要在原文件的任何位置调用 release()。
 * 真正需要清理的是 Coil 的 ImageLoader（见 `engine/impl/CoilImageEngine.close()`，
 * 对应 `ui/AppViewModel.kt` 的 `onCleared()`）。
 *
 * 注：`SnapshotStateMap` 来自 `androidx.compose.runtime`，是唯一保留的非纯 Kotlin 依赖——
 * 因为写方在 Coil 的 IO 线程、读方在主线程，必须靠快照机制触发重组，换成普通 Map 会
 * 让信息栏的字节数停在旧值上（这是既有行为，不在此次重构里改）。
 */
object ImageSizes {
    val map: SnapshotStateMap<String, Long> = mutableStateMapOf()
}
