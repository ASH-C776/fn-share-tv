package com.kijeye.fnshare.engine.impl

import com.kijeye.fnshare.domain.model.MusicEntry
import com.kijeye.fnshare.domain.model.MusicSourceKind
import com.kijeye.fnshare.engine.api.AudioEngine
import com.kijeye.fnshare.engine.api.MusicPlaybackEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 该代码是从哪个原文件提取的：
 * 【新增实现，无函数体被搬动】。两个 [AudioEngine] 原样保留（本地 `ExoAudioEngine`、
 * 飞牛 `FnMusicPlayer`），本类只做「选一个转发 + 把两个 playing/failed 按当前源合成一路」。
 * 两个播放器的构造、ExoPlayer 参数、失败跳曲逻辑、skip 语义**一行都没改**。
 *
 * 为什么两个实例都在构造时建好：两者的 `player` 都是懒创建（首次 play 才 new ExoPlayer），
 * 空实例不占解码资源；提前建好才能从一开始就监听两个 flow，不必在切源时动态换订阅。
 *
 * 对应的生命周期清理应该在原文件的哪个位置调用：
 * `ui/AppViewModel.kt:onCleared()` → 本类 [release] → 桥接 scope cancel + 两个播放器
 * release。这是唯一清理点；[switch] 只 stop，不 release（release 后 ExoPlayer 不可复用）。
 *
 * 线程约定：所有方法主线程调用；桥接协程跑在 `Dispatchers.Main.immediate`。
 */
class DualSourceAudioEngine(
    private val local: AudioEngine,
    private val remote: AudioEngine,
    initialKind: MusicSourceKind,
) : MusicPlaybackEngine {

    private val _kind = MutableStateFlow(initialKind)
    override val kind: MusicSourceKind get() = _kind.value

    private val _playing = MutableStateFlow(false)
    override val playing: StateFlow<Boolean> = _playing.asStateFlow()

    private val _failed = MutableStateFlow(false)
    override val failed: StateFlow<Boolean> = _failed.asStateFlow()

    /** 只用于转发两个子播放器的状态；随 [release] 一起取消，不额外占用 ViewModel 的 scope。 */
    private val bridge = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        bridge.launch {
            combine(local.playing, remote.playing, _kind) { l, r, k -> pick(l, r, k) }
                .collect { _playing.value = it }
        }
        bridge.launch {
            combine(local.failed, remote.failed, _kind) { l, r, k -> pick(l, r, k) }
                .collect { _failed.value = it }
        }
    }

    private val active: AudioEngine get() = if (_kind.value == MusicSourceKind.LOCAL) local else remote

    private fun <T> pick(localValue: T, remoteValue: T, kind: MusicSourceKind): T =
        if (kind == MusicSourceKind.LOCAL) localValue else remoteValue

    override fun switch(kind: MusicSourceKind) {
        if (kind == _kind.value) return
        // 互斥停播：另一个源本来就是停的（或从没 play 过），这里只停当前这个就够了。
        active.stop()
        _kind.value = kind
        // 立刻清徽标，不等新源的第一帧 isPlaying 回调。
        _playing.value = false
        _failed.value = false
    }

    override fun play(tracks: List<MusicEntry>) = active.play(tracks)
    override fun stop() = active.stop()
    override fun pause() = active.pause()
    override fun resume() = active.resume()
    override fun skip(delta: Int): Boolean = active.skip(delta)
    override fun positionMs(): Long = active.positionMs()
    override fun currentMediaIdOrNull(): String? = active.currentMediaIdOrNull()

    override fun release() {
        bridge.cancel()
        local.release()
        remote.release()
        _playing.value = false
        _failed.value = false
    }
}
