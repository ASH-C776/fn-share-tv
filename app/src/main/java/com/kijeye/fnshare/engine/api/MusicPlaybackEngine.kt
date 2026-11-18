package com.kijeye.fnshare.engine.api

import com.kijeye.fnshare.domain.model.MusicSourceKind

/**
 * 该代码是从哪个原文件提取的：
 * 【新增契约，无函数体被搬动】。双源背景乐在 [AudioEngine] 之上只多一个「换源」动作。
 *
 * 为什么不是让 ViewModel 直接换掉 `AudioEngine` 实例：`musicPlaying` / `musicFailed` /
 * `lyricsDoc` 都是 MainActivity 单独 collect 的 StateFlow，换实例会让订阅指向旧播放器，
 * 歌词节拍器里也留着旧引用。留一个稳定的代理对象，两个源在内部互斥起停，
 * ViewModel 与 UI 一行都不用改订阅方式。
 *
 * 放在 `engine/api` 而不是 `domain/port`：domain 不依赖 engine，方向仍是
 * `ui → presentation → domain/port + engine/api ← engine/impl ← di`。
 *
 * 对应的生命周期清理应该在原文件的哪个位置调用：
 * `ui/AppViewModel.kt` 的 `onCleared()`，即现有 `musicPlayer.release()` 那一行——
 * 实现会在内部把两个播放器与状态桥接协程一起收掉。切源**不**算清理，
 * 走 [switch]，不新建也不 release 实例。
 *
 * 线程约定：所有方法主线程调用（ExoPlayer 绑调用线程 Looper）。
 */
interface MusicPlaybackEngine : AudioEngine {

    /** 当前出声的是哪个源。 */
    val kind: MusicSourceKind

    /** 互斥换源：停掉当前源再切，任何时刻只有一个播放器出声。 */
    fun switch(kind: MusicSourceKind)
}
