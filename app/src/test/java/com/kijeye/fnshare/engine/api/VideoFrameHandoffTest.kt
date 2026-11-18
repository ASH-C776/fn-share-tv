package com.kijeye.fnshare.engine.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.25.2：把「首帧只登记 → 下一次 vsync 才统一交接」钉死在 JVM 上。
 *
 * 电视上的黑帧 / 声先画后属于合成时序，MuMu 抓不到（x86 软合成、无 video layer、
 * 无后处理），所以状态机必须先在单测里立住，再靠真机肉眼验收瞬态。
 */
class VideoFrameHandoffTest {

    @Test
    fun `首帧只登记_不在回调里立刻交接`() {
        val fence = VideoFrameHandoff()
        assertEquals(
            VideoFrameHandoffAction.SCHEDULE_VSYNC,
            fence.noteFirstFrame(active = true, failed = false, cancelled = false),
        )
        assertTrue(fence.isPending)
    }

    @Test
    fun `下一次vsync才统一交接_交接后pending清空`() {
        val fence = VideoFrameHandoff()
        fence.noteFirstFrame(active = true, failed = false, cancelled = false)
        assertEquals(
            VideoFrameHandoffAction.HANDOFF_PLAY,
            fence.onVsync(active = true, failed = false, cancelled = false, hostStopped = false),
        )
        assertFalse(fence.isPending)
    }

    @Test
    fun `同一个待交接窗口内首帧重复上报_只投递一次vsync`() {
        val fence = VideoFrameHandoff()
        assertEquals(
            VideoFrameHandoffAction.SCHEDULE_VSYNC,
            fence.noteFirstFrame(true, false, false),
        )
        repeat(3) {
            assertEquals(
                VideoFrameHandoffAction.IGNORE,
                fence.noteFirstFrame(true, false, false),
            )
        }
        assertEquals(
            VideoFrameHandoffAction.HANDOFF_PLAY,
            fence.onVsync(true, false, false, false),
        )
    }

    @Test
    fun `交接完成之后多余的vsync不再动作`() {
        val fence = VideoFrameHandoff()
        fence.noteFirstFrame(true, false, false)
        fence.onVsync(true, false, false, false)
        repeat(3) {
            assertEquals(
                VideoFrameHandoffAction.IGNORE,
                fence.onVsync(true, false, false, false),
            )
        }
    }

    @Test
    fun `stop或release丢弃待交接_退出后不补开播也不撤层`() {
        val fence = VideoFrameHandoff()
        fence.noteFirstFrame(true, false, false)
        fence.discard()
        assertFalse(fence.isPending)
        assertEquals(
            VideoFrameHandoffAction.IGNORE,
            fence.onVsync(true, false, false, false),
        )
    }

    @Test
    fun `取流或解码失败_首帧不登记_vsync也不交接`() {
        val fence = VideoFrameHandoff()
        assertEquals(
            VideoFrameHandoffAction.IGNORE,
            fence.noteFirstFrame(active = true, failed = true, cancelled = false),
        )
        assertEquals(
            VideoFrameHandoffAction.IGNORE,
            fence.onVsync(active = true, failed = true, cancelled = false, hostStopped = false),
        )
    }

    @Test
    fun `门控已取消_登记与交接都作废`() {
        val fence = VideoFrameHandoff()
        assertEquals(
            VideoFrameHandoffAction.IGNORE,
            fence.noteFirstFrame(active = true, failed = false, cancelled = true),
        )
        // 登记后才取消：vsync 时重新查闸门，不许交接。
        fence.noteFirstFrame(true, false, false)
        assertEquals(
            VideoFrameHandoffAction.IGNORE,
            fence.onVsync(active = true, failed = false, cancelled = true, hostStopped = false),
        )
        assertFalse(fence.isPending)
    }

    @Test
    fun `引擎已失活_登记与交接都作废`() {
        val fence = VideoFrameHandoff()
        assertEquals(
            VideoFrameHandoffAction.IGNORE,
            fence.noteFirstFrame(active = false, failed = false, cancelled = false),
        )
        fence.noteFirstFrame(true, false, false)
        assertEquals(
            VideoFrameHandoffAction.IGNORE,
            fence.onVsync(active = false, failed = false, cancelled = false, hostStopped = false),
        )
    }

    @Test
    fun `进后台只撤层不点播_回来按OK才续播`() {
        val fence = VideoFrameHandoff()
        fence.noteFirstFrame(true, false, false)
        assertEquals(
            VideoFrameHandoffAction.HANDOFF_PAUSED,
            fence.onVsync(active = true, failed = false, cancelled = false, hostStopped = true),
        )
        assertFalse(fence.isPending)
    }

    @Test
    fun `forceStart抢先开播_首帧仍必须走同一道栅栏`() {
        val gate = VideoStartGate()
        val fence = VideoFrameHandoff()
        // 3 秒超时：静音推进，加载层不撤。
        assertEquals(VideoStartAction.START_PLAYBACK, gate.startNow())
        // 首帧因此必然到来，但交接不能在这一刻发生。
        assertEquals(
            VideoFrameHandoffAction.SCHEDULE_VSYNC,
            fence.noteFirstFrame(true, false, gate.isCancelled),
        )
        assertEquals(
            VideoFrameHandoffAction.HANDOFF_PLAY,
            fence.onVsync(
                active = true,
                failed = false,
                cancelled = gate.isCancelled,
                hostStopped = false,
            ),
        )
        // 栅栏放行之后才轮到门控：已经开播过，不再重复 play()，但仍恢复音量 + 撤层。
        assertEquals(VideoStartAction.NONE, gate.onFirstFrame())
    }

    @Test
    fun `新播放器实例重置后_首帧栅栏从头开始`() {
        val fence = VideoFrameHandoff()
        fence.noteFirstFrame(true, false, false)
        fence.reset()
        assertFalse(fence.isPending)
        assertEquals(
            VideoFrameHandoffAction.SCHEDULE_VSYNC,
            fence.noteFirstFrame(true, false, false),
        )
    }
}
