package com.kijeye.fnshare.engine.api

import com.kijeye.fnshare.domain.model.MusicEntry
import kotlinx.coroutines.flow.StateFlow

/**
 * 背景乐引擎的契约。方法集逐字镜像 `data/music/SlideshowMusicPlayer.kt`（结构化重构第 4 批），
 * 不新增、不删减、不改默认值——UI / ViewModel 只能看见这个接口，看不见 ExoPlayer。
 *
 * 该代码是从 `data/music/SlideshowMusicPlayer.kt:33-193` 的公开方法签名提取的。
 *
 * 生命周期清理：`release()`。接线后应在 `ui/AppViewModel.kt:1198` 的 `onCleared()` 中调用
 * （即现有 `musicPlayer.release()` 那一行，方法体不变，只把接收者换成接口）。
 * `ui/AppViewModel.kt` 目前仍直接持有具体类，第 5 批由 `di/AppContainer` 注入本接口。
 *
 * 线程约束（原类注释照抄）：所有方法必须在主线程调用，ExoPlayer 绑在调用线程的 Looper 上。
 */
interface AudioEngine {

    /** 播放器真的在出声，由监听器实时维护。UI 的「播放中」徽标认这个。 */
    val playing: StateFlow<Boolean>

    /** 这一轮里有曲子根本解不开（格式不支持 / 文件坏了），播放器已跳过。 */
    val failed: StateFlow<Boolean>

    /** 开始或继续播放 [tracks]，按给定顺序循环。空列表等于停止。 */
    fun play(tracks: List<MusicEntry>)

    /** 这一场结束了：停止播放，下一次从头开始。 */
    fun stop()

    /** 暂停：停在当前位置，进度不丢，下次 [resume] 从同一处接着放。 */
    fun pause()

    /** 从 [pause] 处继续。没有载入曲子时安静空操作。 */
    fun resume()

    /**
     * 切到下一首（[delta] > 0）或上一首（[delta] < 0）。
     * 返回 true 表示真的切了；调用方靠这个返回值决定按键归谁。
     */
    fun skip(delta: Int): Boolean

    /** 当前播放位置（毫秒）。播放器还没建时 0。主线程调用。 */
    fun positionMs(): Long

    /** 当前曲目的 mediaId；没有载入曲目时 null。主线程调用。 */
    fun currentMediaIdOrNull(): String?

    /** 释放底层资源。ViewModel 销毁时调用。 */
    fun release()
}
