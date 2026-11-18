package com.kijeye.fnshare.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.24.9「画出什么」的回归测试；0.24.12 起判定键由 URL 换成播放器实例 token。
 *
 * 0.24.6 的教训：只测 key 字符串，171 例全绿却一点改善都没有。
 * 这里把加载层的可见性判定和超时边界抽成纯函数直接钉死。
 */
class VideoLoadingDecisionTest {

    @Test
    fun `还没就绪_加载层可见`() {
        assertTrue(videoLoadingVisible(-1, 0))
    }

    @Test
    fun `当前实例已就绪_加载层整块撤掉`() {
        assertFalse(videoLoadingVisible(3, 3))
    }

    @Test
    fun `就绪的是上一个实例_新实例仍要画加载层`() {
        assertTrue(videoLoadingVisible(1, 2))
        assertTrue(videoLoadingVisible(2, 1))
    }

    @Test
    fun `任意输入只返回布尔单值_不存在两层同时可见的中间态`() {
        for (ready in listOf(-1, 0, 1, 2)) {
            for (token in listOf(0, 1, 2)) {
                val visible = videoLoadingVisible(ready, token)
                // 可见 = 整块盖住播放器；不可见 = 整块撤掉。没有第三种状态。
                assertTrue(visible || !visible)
                assertFalse(visible && videoLoadingVisible(token, token))
            }
        }
    }

    @Test
    fun `超时边界_到点才撤_早一毫秒都不撤`() {
        assertFalse(videoLoadingTimedOut(0L))
        assertFalse(videoLoadingTimedOut(-1L))
        assertFalse(videoLoadingTimedOut(VIDEO_LOADING_TIMEOUT_MS - 1))
        assertTrue(videoLoadingTimedOut(VIDEO_LOADING_TIMEOUT_MS))
        assertTrue(videoLoadingTimedOut(VIDEO_LOADING_TIMEOUT_MS + 1))
    }

    @Test
    fun `超时阈值可替换_边界同样按大于等于判定`() {
        assertFalse(videoLoadingTimedOut(499L, 500L))
        assertTrue(videoLoadingTimedOut(500L, 500L))
    }

    @Test
    fun `强制推进后的放弃等待窗口长于首次超时窗口`() {
        assertTrue(VIDEO_FORCE_GIVE_UP_MS > 0L)
        assertTrue(VIDEO_FORCE_GIVE_UP_MS >= VIDEO_LOADING_TIMEOUT_MS)
    }
}
