package com.kijeye.fnshare.engine.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.24.12 M-1 / m-4：`attach → stop → release` 的调用序列与「停止后无状态回流」。
 *
 * 0.24.11 只把 `latestOnState` 置空，UI 的轮询仍会直接 `snapshot()` 回流状态；
 * 这里把闸门抽成可测的纯 Kotlin 状态，让这条竞态不用靠电视肉眼验收。
 */
class VideoActivityTest {

    @Test
    fun `初始不活跃_不许有任何状态回流`() {
        val activity = VideoActivity()
        assertFalse(activity.isActive)
        assertFalse(activity.allowsStateFlow())
    }

    @Test
    fun `attach之后才活跃且允许回流`() {
        val activity = VideoActivity()
        activity.onAttach()
        assertTrue(activity.isActive)
        assertTrue(activity.allowsStateFlow())
    }

    @Test
    fun `stop立刻断流_重复stop幂等`() {
        val activity = VideoActivity()
        activity.onAttach()
        activity.onStop()
        assertFalse(activity.allowsStateFlow())
        activity.onStop()
        assertFalse(activity.allowsStateFlow())
    }

    @Test
    fun `release同样断流`() {
        val activity = VideoActivity()
        activity.onAttach()
        activity.onRelease()
        assertFalse(activity.isActive)
        assertFalse(activity.allowsStateFlow())
    }

    @Test
    fun `完整生命周期序列_停止之后监听器再来也不许回流`() {
        val activity = VideoActivity()
        val trace = mutableListOf<Pair<String, Boolean>>()

        activity.onAttach()
        trace += "attach" to activity.allowsStateFlow()
        trace += "listener" to activity.allowsStateFlow()
        activity.onStop()
        trace += "stop" to activity.allowsStateFlow()
        // 旧屏淡出那 160ms 里迟到的 onRenderedFirstFrame / onIsPlayingChanged
        trace += "lateFirstFrame" to activity.allowsStateFlow()
        trace += "latePoll" to activity.allowsStateFlow()
        activity.onRelease()
        trace += "release" to activity.allowsStateFlow()

        assertEquals(
            listOf(
                "attach" to true,
                "listener" to true,
                "stop" to false,
                "lateFirstFrame" to false,
                "latePoll" to false,
                "release" to false,
            ),
            trace,
        )
    }

    @Test
    fun `新实例重新attach后恢复回流_每段视频互不影响`() {
        val first = VideoActivity()
        first.onAttach()
        first.onStop()

        val second = VideoActivity()
        second.onAttach()
        assertTrue(second.allowsStateFlow())
        assertFalse(first.allowsStateFlow())
    }
}
