package com.kijeye.fnshare.ui

import com.kijeye.fnshare.engine.api.VideoActivity
import com.kijeye.fnshare.engine.api.VideoStartAction
import com.kijeye.fnshare.engine.api.VideoStartGate
import com.kijeye.fnshare.engine.api.videoIsPlaying
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.24.12 m-4：**组合时序**回归测试。
 *
 * 以前「门控」「加载门闩」「存活状态」各自单测、彼此不搭，结果 190 例全绿而电视上
 * 声画照样不同步。这里把三者按真实进屏顺序串起来，钉死动作序列与竞态：
 * 正常开局、超时强制推进、退出后迟到首帧、坏片源报错、回看同一 URL、缓冲期状态上报。
 *
 * 说明：没有注入假 ExoPlayer（那要把播放器构造也抽象出接口），这里验证的是
 * 编排层状态机；真机的 `attach → stop → release` 调用顺序由 [VideoActivity] 那组测试覆盖。
 */
class VideoSessionTimelineTest {

    @Test
    fun `正常路径_READY到seek到首帧到play到撤层`() {
        val gate = VideoStartGate()
        val loading = VideoLoadingState()
        val activity = VideoActivity()
        val trace = mutableListOf<String>()

        activity.onAttach()
        val token = loading.beginSession()
        if (loading.loadingVisible(token)) trace += "LOADING"
        if (gate.onPlaybackReady(false) == VideoStartAction.SEEK_TO_START) trace += "SEEK_0"
        if (gate.onFirstFrame() == VideoStartAction.START_PLAYBACK) {
            trace += "PLAY"
            loading.onFirstFrame(token)
        }
        if (!loading.loadingVisible(token)) trace += "HIDE"

        assertEquals(listOf("LOADING", "SEEK_0", "PLAY", "HIDE"), trace)
        assertTrue(activity.allowsStateFlow())
    }

    @Test
    fun `超时路径_先静音推进且不撤层_首帧到了才恢复音量并撤层`() {
        val gate = VideoStartGate()
        val loading = VideoLoadingState()
        val trace = mutableListOf<String>()

        val token = loading.beginSession()
        trace += "LOADING"
        if (loading.onForceStart(token)) {
            trace += "MUTE_FORCE_PLAY"
            gate.startNow()
        }
        // 关键：强制推进之后转圈仍在，画面没出来绝不出声、绝不撤层。
        assertTrue(loading.loadingVisible(token))
        // 首帧终于到了：门控不让重复 play，引擎恢复音量，UI 撤层。
        val action = gate.onFirstFrame()
        assertEquals(VideoStartAction.NONE, action)
        trace += "RESTORE_VOLUME"
        loading.onFirstFrame(token)
        if (!loading.loadingVisible(token)) trace += "HIDE"

        assertEquals(listOf("LOADING", "MUTE_FORCE_PLAY", "RESTORE_VOLUME", "HIDE"), trace)
    }

    @Test
    fun `退出竞态_stop之后迟到的首帧既不play也不撤层`() {
        val gate = VideoStartGate()
        val loading = VideoLoadingState()
        val activity = VideoActivity()
        val trace = mutableListOf<String>()

        activity.onAttach()
        val token = loading.beginSession()
        gate.onPlaybackReady(false)
        // 人按两下 BACK：UI 先 stop()
        activity.onStop()
        gate.cancel()
        // 淡出期间旧播放器才报首帧
        if (activity.allowsStateFlow() && gate.onFirstFrame() == VideoStartAction.START_PLAYBACK) {
            trace += "PLAY"
            loading.onFirstFrame(token)
        }
        if (!loading.loadingVisible(token)) trace += "HIDE"

        assertTrue(trace.isEmpty())
        assertTrue(loading.loadingVisible(token))
    }

    @Test
    fun `坏片源报错_撤层且之后不许再开播`() {
        val gate = VideoStartGate()
        val loading = VideoLoadingState()
        val trace = mutableListOf<String>()

        val token = loading.beginSession()
        // onPlayerError：failed + 取消门控 + 撤层交错误提示
        gate.cancel()
        loading.onGiveUp(token)
        if (!loading.loadingVisible(token)) trace += "SHOW_ERROR"
        // 超时兜底 / 人按 OK 都不许把出错的播放器点着
        assertEquals(VideoStartAction.NONE, gate.startNow())
        assertEquals(VideoStartAction.NONE, gate.onFirstFrame())

        assertEquals(listOf("SHOW_ERROR"), trace)
    }

    @Test
    fun `回看同一个URL_新实例重新画转圈_旧首帧不误撤`() {
        val gateA = VideoStartGate()
        val loading = VideoLoadingState()

        val first = loading.beginSession()
        gateA.onPlaybackReady(false)
        gateA.onFirstFrame()
        loading.onFirstFrame(first)

        // 中间翻到照片、再翻回同一个视频：播放器实例是全新的
        loading.beginSession()
        val back = loading.beginSession()
        val gateB = VideoStartGate()
        assertTrue(loading.loadingVisible(back))
        // 旧实例迟到的首帧
        loading.onFirstFrame(first)
        assertTrue(loading.loadingVisible(back))
        // 新实例走完整开局
        assertEquals(VideoStartAction.SEEK_TO_START, gateB.onPlaybackReady(false))
        assertEquals(VideoStartAction.START_PLAYBACK, gateB.onFirstFrame())
        loading.onFirstFrame(back)
        assertFalse(loading.loadingVisible(back))
    }

    @Test
    fun `缓冲期不谎报在播_闲置保护才能正常触发`() {
        assertFalse(
            videoIsPlaying(
                actualIsPlaying = false,
                gateStarted = true,
                cancelled = false,
                buffering = true,
                hasActuallyPlayed = false,
            )
        )
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
}
