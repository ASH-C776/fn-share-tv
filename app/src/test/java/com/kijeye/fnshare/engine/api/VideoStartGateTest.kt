package com.kijeye.fnshare.engine.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.24.10 方案 A：把「待播 → 首帧 → 开播」的顺序钉死在纯状态机上。
 *
 * 这轮要防的三个回归：
 *  - 加载层还盖着就 play()（声音先出来，画面还不是 00:00）；
 *  - 播放中缓冲结束又报一次 READY，被当成开局 seek 拉回 00:00；
 *  - 超时 / 手动开播之后首帧才来，重复 play 或重复撤层。
 *
 * 0.26.4 补：乐观上报只活到「真实 isPlaying 第一次回 true」为止。
 */
class VideoStartGateTest {

    @Test
    fun `首次READY且待播_主动seek到起点推首帧`() {
        val gate = VideoStartGate()
        assertEquals(VideoStartAction.SEEK_TO_START, gate.onPlaybackReady(playWhenReady = false))
        assertFalse(gate.isStarted)
    }

    @Test
    fun `开局seek只允许一次_重复READY不再seek`() {
        val gate = VideoStartGate()
        assertEquals(VideoStartAction.SEEK_TO_START, gate.onPlaybackReady(false))
        repeat(3) {
            assertEquals(VideoStartAction.NONE, gate.onPlaybackReady(false))
        }
    }

    @Test
    fun `已经开播才READY_不seek_不会把片子拽回起点`() {
        val gate = VideoStartGate()
        assertEquals(VideoStartAction.START_PLAYBACK, gate.startNow())
        assertEquals(VideoStartAction.NONE, gate.onPlaybackReady(true))
        assertEquals(VideoStartAction.NONE, gate.onPlaybackReady(false))
    }

    @Test
    fun `正常开局顺序_READY到seek到首帧到play`() {
        val gate = VideoStartGate()
        assertEquals(VideoStartAction.SEEK_TO_START, gate.onPlaybackReady(false))
        assertEquals(VideoStartAction.START_PLAYBACK, gate.onFirstFrame())
        assertTrue(gate.isStarted)
        assertEquals(VideoStartAction.NONE, gate.onPlaybackReady(true))
    }

    @Test
    fun `首帧重复上报幂等_不会重复play`() {
        val gate = VideoStartGate()
        assertEquals(VideoStartAction.START_PLAYBACK, gate.onFirstFrame())
        repeat(3) { assertEquals(VideoStartAction.NONE, gate.onFirstFrame()) }
    }

    @Test
    fun `正常路径开播后超时不再生效`() {
        val gate = VideoStartGate()
        gate.onPlaybackReady(false)
        gate.onFirstFrame()
        assertEquals(VideoStartAction.NONE, gate.startNow())
    }

    @Test
    fun `超时先到强制开播_之后首帧只撤层不重复play`() {
        val gate = VideoStartGate()
        assertEquals(VideoStartAction.START_PLAYBACK, gate.startNow())
        assertEquals(VideoStartAction.NONE, gate.onFirstFrame())
        assertTrue(gate.isStarted)
    }

    @Test
    fun `超时先到且READY还没来_后续READY不做开局seek`() {
        val gate = VideoStartGate()
        gate.startNow()
        // 超时开播时播放器可能还在 BUFFERING：playWhenReady 已是 true，READY 到了不能 seek。
        assertEquals(VideoStartAction.NONE, gate.onPlaybackReady(true))
        assertEquals(VideoStartAction.NONE, gate.onPlaybackReady(false))
    }

    @Test
    fun `开局未READY就等来首帧_直接开播且之后不再seek`() {
        val gate = VideoStartGate()
        assertEquals(VideoStartAction.START_PLAYBACK, gate.onFirstFrame())
        assertEquals(VideoStartAction.NONE, gate.onPlaybackReady(false))
    }

    @Test
    fun `每个视频独立门控_新实例重新走开局`() {
        val first = VideoStartGate()
        first.onPlaybackReady(false)
        first.onFirstFrame()

        val second = VideoStartGate()
        assertFalse(second.isStarted)
        assertEquals(VideoStartAction.SEEK_TO_START, second.onPlaybackReady(false))
        assertEquals(VideoStartAction.START_PLAYBACK, second.onFirstFrame())
    }

    // ── 0.24.11：退出/释放之后不许再被回调点着 ──────────────────────────

    @Test
    fun `退出后旧屏淡出期间来首帧_不再开播`() {
        val gate = VideoStartGate()
        gate.onPlaybackReady(false)
        gate.cancel()
        assertTrue(gate.isCancelled)
        assertEquals(VideoStartAction.NONE, gate.onFirstFrame())
        assertFalse(gate.isStarted)
    }

    @Test
    fun `退出后超时兜底与READY都不再生效`() {
        val gate = VideoStartGate()
        gate.cancel()
        assertEquals(VideoStartAction.NONE, gate.startNow())
        assertEquals(VideoStartAction.NONE, gate.onPlaybackReady(false))
        assertEquals(VideoStartAction.NONE, gate.onPlaybackReady(true))
    }

    @Test
    fun `取消是幂等的_已开播视频退出也不重复play`() {
        val gate = VideoStartGate()
        gate.onFirstFrame()
        gate.cancel()
        gate.cancel()
        assertEquals(VideoStartAction.NONE, gate.onFirstFrame())
        assertEquals(VideoStartAction.NONE, gate.startNow())
    }

    // ── 0.24.11：play() 后同步上报别把 UI 判成「暂停」 ──────────────────

    @Test
    fun `play刚发出还没回isPlaying_按在播上报`() {
        assertTrue(
            videoIsPlaying(
                actualIsPlaying = false,
                gateStarted = true,
                cancelled = false,
                buffering = false,
                hasActuallyPlayed = false,
            )
        )
    }

    @Test
    fun `真实在播按在播上报_门控没开也听真实值`() {
        assertTrue(
            videoIsPlaying(
                actualIsPlaying = true,
                gateStarted = false,
                cancelled = false,
                buffering = false,
                hasActuallyPlayed = false,
            )
        )
        assertFalse(
            videoIsPlaying(
                actualIsPlaying = false,
                gateStarted = false,
                cancelled = false,
                buffering = false,
                hasActuallyPlayed = false,
            )
        )
    }

    @Test
    fun `停止后不再谎报在播`() {
        assertFalse(
            videoIsPlaying(
                actualIsPlaying = false,
                gateStarted = true,
                cancelled = true,
                buffering = false,
                hasActuallyPlayed = false,
            )
        )
    }

    @Test
    fun `缓冲中不谎报在播_闲置保护才能正常触发`() {
        assertFalse(
            videoIsPlaying(
                actualIsPlaying = false,
                gateStarted = true,
                cancelled = false,
                buffering = true,
                hasActuallyPlayed = false,
            )
        )
    }

    // ── 0.26.4：真实播放起来之后，乐观上报必须让位 ──────────────────────

    @Test
    fun `真实播过之后按OK暂停_必须报没在播`() {
        assertFalse(
            videoIsPlaying(
                actualIsPlaying = false,
                gateStarted = true,
                cancelled = false,
                buffering = false,
                hasActuallyPlayed = true,
            )
        )
    }

    @Test
    fun `真实播过之后继续播_仍按在播上报`() {
        assertTrue(
            videoIsPlaying(
                actualIsPlaying = true,
                gateStarted = true,
                cancelled = false,
                buffering = false,
                hasActuallyPlayed = true,
            )
        )
    }

    @Test
    fun `真实播过之后再缓冲_也不谎报在播`() {
        assertFalse(
            videoIsPlaying(
                actualIsPlaying = false,
                gateStarted = true,
                cancelled = false,
                buffering = true,
                hasActuallyPlayed = true,
            )
        )
    }

    @Test
    fun `onIsPlayingChanged_true只记一次且幂等_新门控重新归零`() {
        val gate = VideoStartGate()
        assertFalse(gate.hasActuallyPlayed)
        gate.onActuallyPlaying()
        gate.onActuallyPlaying()
        assertTrue(gate.hasActuallyPlayed)
        // 换一条视频会 new 一个门控，不能把上一条的「已经播过」带过来。
        assertFalse(VideoStartGate().hasActuallyPlayed)
    }

    @Test
    fun `没真实播过时_缓冲期仍按没在播上报_开局窗口不误判`() {
        assertFalse(
            videoIsPlaying(
                actualIsPlaying = false,
                gateStarted = true,
                cancelled = false,
                buffering = true,
                hasActuallyPlayed = false,
            )
        )
    }
}
