package com.kijeye.fnshare.engine.impl

import android.content.Context
import com.kijeye.fnshare.data.music.SlideshowMusicPlayer
import com.kijeye.fnshare.domain.model.MusicEntry
import com.kijeye.fnshare.engine.api.AudioEngine
import kotlinx.coroutines.flow.StateFlow

/**
 * 该代码是从 `data/music/SlideshowMusicPlayer.kt` 提取的（结构化重构第 4 批）。
 * 提取方式是**包一层转发**：原类 243 行一个字没改，方法体仍由它执行，
 * 本类只把公开 API 对到 `engine/api/AudioEngine` 上。没有新增判断、没有改播放器配置。
 *
 * 生命周期清理：`release()` → 转发 `SlideshowMusicPlayer.release()`。
 * 接线后应在 `ui/AppViewModel.kt:1198` 的 `onCleared()` 中调用，即替换现有
 * `musicPlayer.release()` 那一行（原行不改，只换接收者类型）。
 *
 * 线程约束照抄原类：所有方法必须主线程调用（ExoPlayer 绑调用线程的 Looper）。
 */
class ExoAudioEngine(
    private val delegate: SlideshowMusicPlayer,
) : AudioEngine {

    /** 便于 `di/AppContainer` 单点构造：整个进程一个播放器实例，不重复申请音频焦点。 */
    constructor(context: Context) : this(SlideshowMusicPlayer(context))

    override val playing: StateFlow<Boolean> get() = delegate.playing
    override val failed: StateFlow<Boolean> get() = delegate.failed

    override fun play(tracks: List<MusicEntry>) = delegate.play(tracks)
    override fun stop() = delegate.stop()
    override fun pause() = delegate.pause()
    override fun resume() = delegate.resume()
    override fun skip(delta: Int): Boolean = delegate.skip(delta)
    override fun positionMs(): Long = delegate.positionMs()
    override fun currentMediaIdOrNull(): String? = delegate.currentMediaIdOrNull()
    override fun release() = delegate.release()
}
