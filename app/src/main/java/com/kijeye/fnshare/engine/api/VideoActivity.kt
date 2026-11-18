package com.kijeye.fnshare.engine.api

/**
 * 0.24.12 M-1 / m-4：播放器「还活着吗」的纯 Kotlin 状态。
 *
 * 0.24.11 只在 `stop()` 里把 `latestOnState` 置空，却挡不住 UI 那圈轮询直接调
 * `engine.snapshot()`；旧屏淡出那 160ms 里状态照样回流，被判成「暂停」闪一块牌子。
 * 现在引擎把状态闸门收到这里：`stop()` / `release()` 之后 `allowsStateFlow()` 恒 false，
 * report 和 fastPoll 都过它，回调再晚到也不会回流。
 *
 * 单独成一个不碰 Android 的类，是为了让 `attach → stop → release` 的调用序列和
 * 「停止后无状态回流」能在 JVM 单测里钉死，不必每次都靠电视肉眼验收。
 */
internal class VideoActivity {

    private var active = false

    val isActive: Boolean get() = active

    fun onAttach() {
        active = true
    }

    fun onStop() {
        active = false
    }

    fun onRelease() {
        active = false
    }

    /** 停止之后一律不许再产生状态回流（监听器回调 / UI 轮询共用这一道闸门）。 */
    fun allowsStateFlow(): Boolean = active
}
