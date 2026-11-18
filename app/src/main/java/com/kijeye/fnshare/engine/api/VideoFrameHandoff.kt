package com.kijeye.fnshare.engine.api

/**
 * 0.25.2：首帧「buffer 提交给 surface」与「像素被合成上屏」不是同一件事。
 *
 * 0.25.0 把视频加载层从不透明黑底换成了不透明缩略图封面，于是暴露出两个旧时序问题：
 *  - [onRenderedFirstFrame] 一到就硬切撤封面，但电视（创维 A7E Pro 有升频 / 画质后处理）
 *    可能还要 1~数帧才把视频层真正合成上屏，中间那段就是黑帧；
 *  - 同一条回调里先 play() / unmute()，AudioTrack 起得比视频合成快 → 声音先出来。
 *
 * 现在首帧回调**只登记一次待交接**，引擎用 Choreographer 延到下一次 vsync，
 * 再由一个信号统一做「play + 恢复音量 + 上报 + 撤封面」。封面一直盖到像素上屏，
 * 声音和画面在同一刻起。纯 Kotlin 状态机，不碰 Android，顺序由 JVM 单测钉死。
 *
 * 三条硬规矩：
 *  - 停止 / 释放 / 失败 / 门控取消：待交接一律丢弃，绝不在退出后补一次开播（防尾音）；
 *  - 进后台（ON_STOP）：只撤层、不点播、不恢复音量（用户已确认按 Home 回来要按 OK 才续播），
 *    否则待交接被整块丢掉后首帧事件不会重来，封面会永远盖着撤不掉；
 *  - forceStart() 抢先开播也必须走同一道栅栏，它只负责静音推进，不负责撤层 / 恢复音量。
 */
internal enum class VideoFrameHandoffAction {
    /** 什么都不做。 */
    IGNORE,

    /** 首帧已登记，需要投递一次下一次 vsync 的回调。 */
    SCHEDULE_VSYNC,

    /** 前台统一交接：play + unmute + report + 撤封面。 */
    HANDOFF_PLAY,

    /** 后台交接：只 report + 撤封面，绝不 play / unmute。 */
    HANDOFF_PAUSED,
}

/** 首帧事件到达时该怎么做：只有「活着、没失败、没取消、还没登记」才登记。 */
internal fun videoFrameShouldDefer(
    pending: Boolean,
    active: Boolean,
    failed: Boolean,
    cancelled: Boolean,
): VideoFrameHandoffAction = when {
    !active || failed || cancelled -> VideoFrameHandoffAction.IGNORE
    pending -> VideoFrameHandoffAction.IGNORE
    else -> VideoFrameHandoffAction.SCHEDULE_VSYNC
}

/** 下一次 vsync 到达时该不该交接：闸门要**重新查一遍**，登记时的状态可能已经失效。 */
internal fun videoFramePresent(
    pending: Boolean,
    active: Boolean,
    failed: Boolean,
    cancelled: Boolean,
    hostStopped: Boolean,
): VideoFrameHandoffAction = when {
    !pending || !active || failed || cancelled -> VideoFrameHandoffAction.IGNORE
    hostStopped -> VideoFrameHandoffAction.HANDOFF_PAUSED
    else -> VideoFrameHandoffAction.HANDOFF_PLAY
}

/** 每个播放器实例一份；随 ExoVideoEngine.attach() 重置。 */
internal class VideoFrameHandoff {

    private var pending = false

    val isPending: Boolean get() = pending

    /** 首帧渲染：只登记，不交接。返回是否需要投递 vsync 回调。 */
    fun noteFirstFrame(
        active: Boolean,
        failed: Boolean,
        cancelled: Boolean,
    ): VideoFrameHandoffAction {
        val action = videoFrameShouldDefer(pending, active, failed, cancelled)
        if (action == VideoFrameHandoffAction.SCHEDULE_VSYNC) pending = true
        return action
    }

    /**
     * vsync 到达：统一交接点。无论交不交接都清掉 pending——窗口已经过去，
     * 留着只会让后续一次无关的 vsync 误撤封面。
     */
    fun onVsync(
        active: Boolean,
        failed: Boolean,
        cancelled: Boolean,
        hostStopped: Boolean,
    ): VideoFrameHandoffAction {
        val action = videoFramePresent(pending, active, failed, cancelled, hostStopped)
        pending = false
        return action
    }

    /** stop() / release()：彻底丢弃待交接，连封面都不再撤。 */
    fun discard() {
        pending = false
    }

    fun reset() {
        pending = false
    }
}
