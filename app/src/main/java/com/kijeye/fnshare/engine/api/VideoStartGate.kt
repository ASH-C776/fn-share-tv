package com.kijeye.fnshare.engine.api

/**
 * 视频开局门控（0.24.10，方案 A）：待播 prepare → READY 后主动 seek(0) 把 00:00 帧推上
 * surface → **首帧真的画出来**才 play()。纯 Kotlin 状态机，不碰 Android，顺序由单测钉死。
 *
 * ## 为什么不再 playWhenReady = true
 *
 * 0.24.9 让播放器一 attach 就播，音频渲染器往往比视频首帧先就绪，于是「转圈还没撤、
 * 声音已经出来」；加载层只是盖在 PlayerView 上的一层 Compose，挡得住画面挡不住声音。
 * 撤层时 currentPosition 已经不是 0，看到的也不是 00:00 首帧。
 *
 * 现在加载层盖着期间 playWhenReady=false：时间轴不走、不出声。首帧回调到，
 * UI 撤层与引擎 play() 在同一条回调链上发生，声音和画面从同一帧一起起。
 *
 * ## 为什么 READY 之后要 seek(0)
 *
 * 待播态（playWhenReady=false）下首帧渲染时有时无——0.24.0 当初要靠图片封面兜住
 * 的正是这个坑。首次 READY 时主动 seek 到 0，逼播放器把起点帧解码画出来；
 * 万一还是不来，3 秒超时由 UI 侧强制开播（播放一推进，首帧必然渲染）。
 *
 * ⚠️ 播放中缓冲结束也会报 STATE_READY，所以「开局 seek」只允许发生一次，
 * 且开播之后（playWhenReady=true）一律不再 seek，否则看着看着会被拉回 00:00。
 */
internal enum class VideoStartAction {
    /** 什么都不做。 */
    NONE,

    /** 主动 seek(0)，把 00:00 第一帧推上 surface。 */
    SEEK_TO_START,

    /** 可以开播了：撤加载层 + play()。 */
    START_PLAYBACK,
}

internal class VideoStartGate {

    private var seekDone = false
    private var started = false
    private var cancelled = false
    private var actuallyPlayed = false

    /** 是否已经开播（含超时 / 手动开播）。 */
    val isStarted: Boolean get() = started

    /** 是否已经退出/释放。取消之后任何回调都不许再把播放器点着。 */
    val isCancelled: Boolean get() = cancelled

    /**
     * 播放器的真实 `isPlaying` 有没有上来过。
     *
     * 0.26.4：[videoIsPlaying] 的乐观上报只覆盖「`play()` 发出去、真实状态还没回来」
     * 那一瞬。真实播放一起来，这个标志就一直为真，之后完全听 `actualIsPlaying`——
     * 否则按 OK 暂停时仍会被乐观报成「在播」，暂停牌不显示、闲置保护也永不触发。
     */
    val hasActuallyPlayed: Boolean get() = actuallyPlayed

    /** 由播放器的 `onIsPlayingChanged(true)` 调用；幂等。 */
    fun onActuallyPlaying() {
        actuallyPlayed = true
    }

    /**
     * 0.24.11：退出确认、切视频或 release 时调用。
     *
     * 旧屏淡出那 160ms 里播放器还没被 dispose，`onRenderedFirstFrame` 仍可能到达；
     * 待播态（playWhenReady=false）下这次回调会判成「首帧到了 → play()」，
     * 等于人已经按了退出、声音反而这时候才起来。取消态把这条路彻底封死。
     */
    fun cancel() {
        cancelled = true
    }

    /**
     * 播放器进入 STATE_READY。
     *
     * [playWhenReady] 为 true 说明已经开播（超时兜底或人按 OK 走在了 READY 前面），
     * 此时不能再 seek，否则会把刚开始播的片子拽回起点。
     */
    fun onPlaybackReady(playWhenReady: Boolean): VideoStartAction {
        if (cancelled || started || seekDone || playWhenReady) return VideoStartAction.NONE
        seekDone = true
        return VideoStartAction.SEEK_TO_START
    }

    /** 首帧已画到 surface：从这一帧开播。seek / 渲染器重启后重复上报是幂等的。 */
    fun onFirstFrame(): VideoStartAction = startNow()

    /**
     * 不等首帧直接开播：超时兜底、或人按 OK 主动开播。
     * 之后首帧再来也只撤层、不重复 play；READY 再来也不再 seek。
     */
    fun startNow(): VideoStartAction {
        if (cancelled || started) return VideoStartAction.NONE
        started = true
        return VideoStartAction.START_PLAYBACK
    }
}

/**
 * 0.24.11：`p.play()` 是把消息 post 给播放线程的，紧接着同步 `report()` 那一刻
 * `p.isPlaying` 往往还是 false。UI 会先判成 `PAUSED`（控制栏强制常亮、状态牌写「暂停」），
 * 等 `onIsPlayingChanged` 再来才翻成 `PLAYING` —— 进屏那一瞬状态跳一下，就是「不利索」。
 *
 * 0.24.12：乐观条件改成只看门控自身，不依赖 `p.playWhenReady` 的同步可见性
 * （超时 / 手动开播分支的 `report()` 仍可能读到旧值）。同时排除取消态与缓冲态：
 * 缓冲卡死时 `isPlaying=false` 却被乐观报成「在播」，闲置保护就把「有人在忙」当真，
 * 画面卡住也永不退出。这里宁可按没在播报，让 UI 落到 LOADING。
 *
 * 0.26.4：再加 [hasActuallyPlayed]。乐观值只该活到「真实 isPlaying 第一次回 true」
 * 为止；在那之后播放器说停就是停。少了这一条，按 OK 暂停后 actualIsPlaying=false
 * 却仍被报成「在播」，暂停牌永远不亮、闲置保护也一直当有人在看。
 */
internal fun videoIsPlaying(
    actualIsPlaying: Boolean,
    gateStarted: Boolean,
    cancelled: Boolean,
    buffering: Boolean,
    hasActuallyPlayed: Boolean,
): Boolean = actualIsPlaying ||
    (gateStarted && !cancelled && !buffering && !hasActuallyPlayed)
