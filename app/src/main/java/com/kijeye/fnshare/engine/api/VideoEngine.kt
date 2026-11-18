package com.kijeye.fnshare.engine.api

import androidx.media3.datasource.DataSource
import androidx.media3.ui.PlayerView

/**
 * 视频引擎契约。UI 只把渲染壳 [PlayerView] 交给引擎，拿不回 ExoPlayer，也不跨层操作播放器。
 *
 * 具体实现由 `di/AppContainer.videoEngine()` 产出（0.24.12 起），UI 只认这个接口，
 * 于是播放器时序可以用假实现在 JVM 上验证，不必每次都靠电视肉眼验收。
 *
 * 生命周期：attach →（onHostStop）→ release。退出 / 切片时 UI 先调
 * `VideoController.stop()` 立即断声画，release() 是组合销毁时的最后兜底，两者幂等。
 */
interface VideoEngine {

    /** 供外部按键遥控的句柄，[attach] 之后才非空；stop / release 后清空。 */
    val controller: VideoController?

    /**
     * 0.24.12：**停止之后不许再有任何状态回流**。
     *
     * 旧屏淡出那 160ms 里播放器还没 dispose，监听器回调仍会到；只把 `latestOnState`
     * 置空挡不住 UI 自己那圈轮询（`engine.snapshot()` 直接调）。引擎侧把 report 和
     * UI 侧的轮询闸门一起关掉，才不会在退出瞬间闪出「暂停」牌。
     */
    val isActive: Boolean

    /**
     * 建播放器、挂监听、setMediaSource、prepare，并产出 controller。
     *
     * [onFirstFrame] 来自 `Player.Listener.onRenderedFirstFrame`；引擎在这条回调里
     * 先开播（或恢复音量），再转发给 UI 撤加载层，声画从同一帧一起起。
     * [onError] 是 0.24.12 新增：取流 / 解码失败时转发 `PlaybackException.errorCodeName`，
     * UI 据此撤转圈并显示提示，错误态不再盲目 `play()`。
     */
    fun attach(
        view: PlayerView,
        url: String,
        dataSourceFactory: DataSource.Factory,
        onState: (VideoUiState) -> Unit,
        onFirstFrame: () -> Unit,
        onError: (String) -> Unit,
    )

    /** 取一帧完整状态。 */
    fun snapshot(): VideoUiState

    /** 轮询该走 250ms 还是 1s：原 `player.isPlaying || playbackState == STATE_BUFFERING`。 */
    fun needsFastPoll(): Boolean

    /**
     * 0.24.12：Activity 进后台（ON_STOP）时暂停并静音，避免按 Home / 切 HDMI 后视频继续出声。
     * 回前台不自动续播——停在暂停态，人按 OK 再继续。
     */
    fun onHostStop()

    /** 释放播放器、摘监听器、清 controller。 */
    fun release()
}
