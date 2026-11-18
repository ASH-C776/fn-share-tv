package com.kijeye.fnshare.engine.api

import com.kijeye.fnshare.domain.model.LyricsDoc
import kotlinx.coroutines.flow.StateFlow

/**
 * 歌词节拍器的契约（结构化重构第 4 批）。
 *
 * 该代码是从 `ui/AppViewModel.kt:963-1013` 提取的：100ms 轮询循环、换曲时重解析、
 * 二分查找当前行、以及 `clearLyrics()`。循环体逐字照搬，只把三个外部依赖改成注入
 * （`musicPlayer` → `AudioEngine`、`lyricsRepo` → `domain/port/LyricsSource`、
 * `_state.value.slideshow && lyricsEnabled` → `shouldRun()`）——这是依赖倒置，
 * 不新增分支、不改 100ms 节奏、不改 StateFlow 的写入条件。
 *
 * 线程约定（照抄原注释）：tick 在 viewModelScope 的默认主线程上，只作字段读；
 * LRC 解析由 `LyricsSource.load()` 内部 `withContext(Dispatchers.IO)` 完成；
 * 状态回写全部在主线程。
 *
 * 生命周期清理：`cancel()`。接线后必须在 `ui/AppViewModel.kt:1198` 的 `onCleared()` 中调用，
 * 与 `AudioEngine.release()` 并列；另外原 `setLyricsEnabled(false)`（`:951-961`）里的
 * `lyricsJob?.cancel()` 改为 `ticker.stop()`，`clearLyrics()` 改为 `ticker.cancel()` 后
 * 再 `start()`（或由 `stop()` 内部清状态），语义保持「立刻清、最多一帧都不多挂」。
 * 本批不接线，VM 原轮询逻辑原样保留。
 */
interface LyricsTicker {

    /** 当前曲目的歌词文档；换曲瞬间为 null。 */
    val doc: StateFlow<LyricsDoc?>

    /** 当前句下标；没有歌词时 -1。 */
    val index: StateFlow<Int>

    /** 启动循环（已活着就不再起第二个）。 */
    fun start()

    /** 停循环并清空状态，随时可以再 [start]。 */
    fun stop()

    /** 彻底取消，连同在飞的 LRC 解析 Job；之后不再复用。 */
    fun cancel()
}
