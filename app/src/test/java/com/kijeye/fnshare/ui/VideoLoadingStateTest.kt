package com.kijeye.fnshare.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 视频加载层门闩（0.24.12 起按「播放器实例 token」记，不再按 URL 记）。
 *
 * 要锁住的事：
 *  - 新实例开局画转圈，只有首帧 / 放弃等待能撤；
 *  - 撤了就不再盖回来（暂停 / 播完 / seek 后首帧回调可能再报一次）；
 *  - **A → 照片 → A**：URL 没变但播放器实例换了新的，必须重新画（M-4）；
 *  - 旧实例迟到的首帧不能误撤新实例的加载层；
 *  - **超时只静音强制推进，不撤层**（M-3），首帧真到了才撤。
 *
 * 纯 Kotlin 状态，不需要 Android / Composer / ExoPlayer。
 */
class VideoLoadingStateTest {

    @Test
    fun `新播放器实例开局画加载层`() {
        val loading = VideoLoadingState()
        val token = loading.beginSession()
        assertTrue(loading.loadingVisible(token))
    }

    @Test
    fun `播放器报告首帧后撤掉加载层`() {
        val loading = VideoLoadingState()
        val token = loading.beginSession()
        loading.onFirstFrame(token)
        assertFalse(loading.loadingVisible(token))
    }

    @Test
    fun `首帧重复上报是幂等的_暂停播完不再叫回加载层`() {
        val loading = VideoLoadingState()
        val token = loading.beginSession()
        loading.onFirstFrame(token)
        repeat(3) { loading.onFirstFrame(token) }
        assertFalse(loading.loadingVisible(token))
    }

    @Test
    fun `同一实例反复重组读取_加载层不会重新出现`() {
        val loading = VideoLoadingState()
        val token = loading.beginSession()
        loading.onFirstFrame(token)
        repeat(20) { assertFalse(loading.loadingVisible(token)) }
    }

    @Test
    fun `切到下一个视频_新实例重新画加载层`() {
        val loading = VideoLoadingState()
        val a = loading.beginSession()
        loading.onFirstFrame(a)
        val b = loading.beginSession()
        assertTrue(loading.loadingVisible(b))
        loading.onFirstFrame(b)
        assertFalse(loading.loadingVisible(b))
    }

    @Test
    fun `A到照片再回A_URL没变但播放器实例变了_仍重新画`() {
        val loading = VideoLoadingState()
        val firstA = loading.beginSession()
        loading.onFirstFrame(firstA)
        assertFalse(loading.loadingVisible(firstA))

        val photo = loading.beginSession()
        assertTrue(loading.loadingVisible(photo))

        // 同一个 URL，但 key(videoUrl) 重挂了一个待播态播放器：必须重新转圈。
        val backToA = loading.beginSession()
        assertTrue(loading.loadingVisible(backToA))
        loading.onFirstFrame(backToA)
        assertFalse(loading.loadingVisible(backToA))
    }

    @Test
    fun `旧实例迟到的首帧不会误撤新实例的加载层`() {
        val loading = VideoLoadingState()
        val old = loading.beginSession()
        val current = loading.beginSession()
        loading.onFirstFrame(old)
        assertTrue(loading.loadingVisible(current))
    }

    @Test
    fun `session token 单调递增_每次重挂都换一个新的`() {
        val loading = VideoLoadingState()
        val a = loading.beginSession()
        val b = loading.beginSession()
        val c = loading.beginSession()
        assertTrue(a < b && b < c)
    }

    // ── 0.24.12 M-3：超时只强制推进，不撤层 ──────────────────────────────

    @Test
    fun `超时只静音强制推进_加载层仍然盖着`() {
        val loading = VideoLoadingState()
        val token = loading.beginSession()
        assertTrue(loading.onForceStart(token))
        assertTrue(loading.loadingVisible(token))
    }

    @Test
    fun `强制推进只做一次_重复超时不再forceStart`() {
        val loading = VideoLoadingState()
        val token = loading.beginSession()
        assertTrue(loading.onForceStart(token))
        assertFalse(loading.onForceStart(token))
        assertFalse(loading.onForceStart(token))
    }

    @Test
    fun `强制推进后首帧真到了才撤层`() {
        val loading = VideoLoadingState()
        val token = loading.beginSession()
        loading.onForceStart(token)
        assertTrue(loading.loadingVisible(token))
        loading.onFirstFrame(token)
        assertFalse(loading.loadingVisible(token))
    }

    @Test
    fun `首帧先到时超时不再强制推进`() {
        val loading = VideoLoadingState()
        val token = loading.beginSession()
        loading.onFirstFrame(token)
        assertFalse(loading.onForceStart(token))
    }

    @Test
    fun `旧实例的超时不会触发新实例的强制推进`() {
        val loading = VideoLoadingState()
        val old = loading.beginSession()
        val current = loading.beginSession()
        assertFalse(loading.onForceStart(old))
        assertTrue(loading.onForceStart(current))
    }

    // ── 报错 / 二次超时：放弃等待 ────────────────────────────────────────

    @Test
    fun `报错或二次超时放弃等待并撤层`() {
        val loading = VideoLoadingState()
        val token = loading.beginSession()
        loading.onForceStart(token)
        assertTrue(loading.loadingVisible(token))
        loading.onGiveUp(token)
        assertFalse(loading.loadingVisible(token))
    }

    @Test
    fun `放弃等待之后首帧再来也不回盖`() {
        val loading = VideoLoadingState()
        val token = loading.beginSession()
        loading.onGiveUp(token)
        loading.onFirstFrame(token)
        assertFalse(loading.loadingVisible(token))
    }

    @Test
    fun `旧实例的放弃不影响新实例`() {
        val loading = VideoLoadingState()
        val old = loading.beginSession()
        val current = loading.beginSession()
        loading.onGiveUp(old)
        assertTrue(loading.loadingVisible(current))
    }
}
