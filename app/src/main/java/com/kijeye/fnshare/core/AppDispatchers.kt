package com.kijeye.fnshare.core

import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext

/**
 * 该代码是从哪个原文件提取的：
 * 【新增文件，无提取来源】。现有代码里 18 处 `withContext(Dispatchers.IO)` 集中在
 * `ui/AppViewModel.kt`，另外 `data/music/MusicScanner.kt:57`、`data/lyrics/LyricsRepo.kt:43`
 * 各自在函数体内直接写死 `Dispatchers.IO`。本对象只给后续新抽离的类提供统一入口，
 * **不替换**那些既有写法——按「禁止修改原有逻辑」的要求，旧调用点一行都不动。
 *
 * 对应的生命周期清理应该在原文件的哪个位置调用：
 * 【无需清理】。本对象是纯 Kotlin object，不持有线程、协程作用域、定时器或 Context，
 * 没有 `release()` / `cancel()`。`MainActivity.onDestroy()` 与
 * `AppViewModel.onCleared():1198` 都**不需要**为此新增任何调用。
 *
 * 设计约束：本文件位于 `core`，不 import 任何 Android / 三方库（kotlinx-coroutines 除外）。
 * 新抽离的数据层（读本地文件、解析 LRC、请求飞牛 API）一律在 [io] 上执行；
 * ViewModel 只在 [main] 上更新 StateFlow。
 */
object AppDispatchers {
    val io: CoroutineContext get() = Dispatchers.IO
    val main: CoroutineContext get() = Dispatchers.Main
    val default: CoroutineContext get() = Dispatchers.Default
}
