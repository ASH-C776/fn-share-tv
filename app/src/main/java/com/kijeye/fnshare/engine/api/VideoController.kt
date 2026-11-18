package com.kijeye.fnshare.engine.api

/**
 * 供遥控器按键调用的视频控制句柄。由 `engine/impl/ExoVideoEngine.attach()` 产出，
 * UI 不持有 ExoPlayer 本身。`ui/ViewerScreen.kt` 的按键调用点只认这五个方法 + 停止。
 */
interface VideoController {
    /** 从当前位置开始播；已播完则从头。 */
    fun play()
    fun pause()
    fun playPause()
    fun seekBy(seconds: Int)
    fun setSpeed(speed: Float)

    /**
     * 0.24.11：退出 / 切视频时**立刻**断掉声音和画面。
     *
     * 不等组合销毁（`AnimatedContent` 旧屏还要淡出 160ms，`onDispose` 才 release），
     * 也不让 AudioTrack 里已经写进去的 PCM 继续播完——电视上那段尾音能有 1~2 秒。
     * 实现里同时取消开局门控，防止淡出期间来一次首帧回调又把播放器点着。
     */
    fun stop()

    /**
     * 0.24.12：加载超过 3 秒仍未出首帧时，**静音强制推进播放**以逼出首帧。
     *
     * 与 [play] 的区别：play() 正常出声；forceStart() 全程静音，且**不撤加载层**，
     * 要等首帧真的画上来才恢复音量 + 撤层。旧版超时直接 `onTimeout()` 撤层 + `play()`，
     * 画面还在缓冲就先出声，把 0.24.9「画面没出来就有声音」原样复发了一遍。
     */
    fun forceStart()
}
