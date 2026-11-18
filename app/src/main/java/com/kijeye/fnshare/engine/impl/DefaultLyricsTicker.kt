package com.kijeye.fnshare.engine.impl

import com.kijeye.fnshare.domain.model.LyricsDoc
import com.kijeye.fnshare.domain.port.LyricsSource
import com.kijeye.fnshare.engine.api.AudioEngine
import com.kijeye.fnshare.engine.api.LyricsTicker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 该代码是从 `ui/AppViewModel.kt:963-1013` 逐字提取的（结构化重构第 4 批）：
 * 100ms 轮询循环、换曲时重解析并防「慢结果盖住新曲」、二分查找当前行、`clearLyrics()`。
 *
 * 循环体与原来一一对应，仅把三个外部依赖改成构造注入（依赖倒置，不新增分支）：
 *  - `musicPlayer.currentMediaIdOrNull()/positionMs()` → [audio]
 *  - `lyricsRepo.load(id)` → [lyrics]（实现内部自带 `withContext(Dispatchers.IO)`）
 *  - `!s.slideshow || !s.lyricsEnabled` → `!shouldRun()`
 *  - `_lyricsDoc` / `_lyricIndex` → `_doc` / `_index`（同一对 MutableStateFlow）
 * `delay(100)` 与两条 StateFlow 的写入条件**一个字没动**：tick 本身永不触发重组。
 *
 * 线程约定照抄原注释：tick 跑在传入的 scope（接线时是 `viewModelScope`，默认主线程），
 * 只作播放器字段读；文件解析在 IO；状态回写全在主线程。
 *
 * 生命周期清理：`cancel()`。接线后必须在 `ui/AppViewModel.kt:1198` 的 `onCleared()` 中调用，
 * 与 `AudioEngine.release()` 并列；`setLyricsEnabled(false)`（`:951-961`）里的
 * `lyricsJob?.cancel()` + `clearLyrics()` 换成 `ticker.stop()`。
 * 本批不接线，`ui/AppViewModel.kt` 原轮询与清理逻辑原样保留，不存在两条循环并存。
 */
class DefaultLyricsTicker(
    private val scope: CoroutineScope,
    private val audio: AudioEngine,
    private val lyrics: LyricsSource,
    /** 只读布尔源，接线时传 `{ _state.value.slideshow && _state.value.lyricsEnabled }`。 */
    private val shouldRun: () -> Boolean,
) : LyricsTicker {

    private val _doc = MutableStateFlow<LyricsDoc?>(null)
    override val doc: StateFlow<LyricsDoc?> = _doc.asStateFlow()

    private val _index = MutableStateFlow(-1)
    override val index: StateFlow<Int> = _index.asStateFlow()

    private var tickJob: Job? = null
    private var loadJob: Job? = null
    private var trackId: String? = null

    /** 轮询循环（已活着就不再起第二个）。 */
    override fun start() {
        if (tickJob?.isActive == true) return
        tickJob = scope.launch {
            while (isActive) {
                if (!shouldRun()) {
                    clear()
                    return@launch
                }
                val id = audio.currentMediaIdOrNull()
                if (id != trackId) {
                    loadJob?.cancel()
                    trackId = id
                    _doc.value = null
                    _index.value = -1
                    if (id != null) {
                        // 解析在 IO 线程；回写前再校对一次当前曲目，防「慢结果盖住新曲」。
                        loadJob = launch {
                            val doc = lyrics.load(id)
                            if (trackId == id) _doc.value = doc
                        }
                    }
                }
                val doc = _doc.value
                if (doc != null) {
                    val idx = doc.indexOf(audio.positionMs())
                    if (idx != _index.value) _index.value = idx
                }
                delay(100)
            }
        }
    }

    /** 停循环并立刻清状态，随时可以再 [start]。 */
    override fun stop() {
        tickJob?.cancel()
        tickJob = null
        clear()
    }

    /** 彻底取消，连同在飞的 LRC 解析 Job；之后不再复用。 */
    override fun cancel() {
        stop()
    }

    /** 清掉歌词文档与句下标（换曲、关开关、幻灯片结束共用）。原 `clearLyrics()`。 */
    private fun clear() {
        loadJob?.cancel()
        loadJob = null
        trackId = null
        _doc.value = null
        _index.value = -1
    }
}
