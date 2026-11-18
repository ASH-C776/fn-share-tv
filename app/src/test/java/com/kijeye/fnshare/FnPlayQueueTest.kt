package com.kijeye.fnshare

import com.kijeye.fnshare.domain.model.MusicEntry
import com.kijeye.fnshare.domain.usecase.FnGroupTracks
import com.kijeye.fnshare.domain.usecase.buildFnPlayQueue
import com.kijeye.fnshare.domain.usecase.fnQueueRetryDelayMs
import com.kijeye.fnshare.domain.usecase.shouldRetryFnQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 飞牛歌单展开队列的纯函数回归。
 *
 * 0.21.2 的「勾了歌单、幻灯片一声不响」根因是服务端曲目接口失败被
 * `runCatching{}.getOrNull().orEmpty()` 抹成空列表，UI 与日志都看不到失败。
 * 这里把「全成功 / 部分失败 / 全失败 / 空歌单 / 旧版兜底」全部离线钉死，
 * 以后再改这条链路，不用等 NAS 复现就能发现回归。
 */
class FnPlayQueueTest {

    private fun track(id: String, playable: Boolean = true) =
        MusicEntry(id = "fn:$id", title = id, playable = playable)

    private val a = track("a")
    private val b = track("b")
    private val c = track("c")

    private fun ok(id: String, vararg tracks: MusicEntry) = FnGroupTracks.Ok("fnpl:$id", tracks.toList())
    private fun failed(id: String) =
        FnGroupTracks.Failed("fnpl:$id", RuntimeException("100001 unknown error"))

    @Test
    fun allGroupsOkKeepsPickOrderDedupesAndDropsUnplayable() {
        val queue = buildFnPlayQueue(
            listOf(
                ok("1", a, b, track("bad", playable = false)),
                ok("2", b, c),
            ),
        )
        assertEquals(listOf(a, b, c), queue.tracks)
        assertFalse(queue.hasFailure)
        assertFalse(queue.allFailed)
    }

    @Test
    fun partialFailureStillPlaysWhatWorked() {
        val queue = buildFnPlayQueue(listOf(failed("1"), ok("2", a, b)))
        assertEquals(listOf(a, b), queue.tracks)
        assertTrue(queue.hasFailure)
        // 还有歌可放，不该报「全失败」去打断用户。
        assertFalse(queue.allFailed)
    }

    @Test
    fun allFailedIsExplicitAndMustTriggerVisibleHint() {
        val queue = buildFnPlayQueue(listOf(failed("1"), failed("2")))
        assertTrue(queue.tracks.isEmpty())
        assertTrue(queue.hasFailure)
        assertTrue(queue.allFailed)
    }

    @Test
    fun emptyPlaylistIsSuccessNotFailure() {
        val queue = buildFnPlayQueue(listOf(ok("1")))
        assertTrue(queue.tracks.isEmpty())
        assertFalse(queue.hasFailure)
        assertFalse(queue.allFailed)
    }

    @Test
    fun looseTracksOnlyFillInWhenMainQueueIsEmpty() {
        val legacy = track("legacy")
        // 主队列有歌：旧版残留曲目不许混进来。
        assertEquals(
            listOf(a),
            buildFnPlayQueue(listOf(ok("1", a)), listOf(legacy)).tracks,
        )
        // 主队列空：旧版 fn: 曲目顶上，老用户不会静音。
        assertEquals(
            listOf(legacy),
            buildFnPlayQueue(listOf(failed("1")), listOf(legacy)).tracks,
        )
    }

    @Test
    fun legacyLooseTracksMeanNotAllFailed() {
        val queue = buildFnPlayQueue(listOf(failed("1")), listOf(a, b))
        assertEquals(listOf(a, b), queue.tracks)
        assertTrue(queue.hasFailure)
        assertFalse(queue.allFailed)
    }

    @Test
    fun noSelectedGroupsIsNeutral() {
        val queue = buildFnPlayQueue(emptyList())
        assertTrue(queue.tracks.isEmpty())
        assertFalse(queue.hasFailure)
        assertFalse(queue.allFailed)
    }

    // ---- 0.21.4：NAS 服务端抖动时的有限重试，别在后台无限轮询 ----

    @Test
    fun retryOnlyWhenEveryGroupFailedAndUserStillWaiting() {
        val allFailed = buildFnPlayQueue(listOf(failed("1"), failed("2")))
        assertTrue(shouldRetryFnQueue(allFailed, attempt = 1, stillWaiting = true))
        // 用户关了幻灯片 / 切回本地 / 改了勾选：立刻停手，不再发请求。
        assertFalse(shouldRetryFnQueue(allFailed, attempt = 1, stillWaiting = false))
    }

    @Test
    fun partialSuccessAndEmptyPlaylistNeverRetry() {
        val partial = buildFnPlayQueue(listOf(ok("1", a), failed("2")))
        assertFalse(shouldRetryFnQueue(partial, attempt = 1, stillWaiting = true))
        val emptyPlaylist = buildFnPlayQueue(listOf(ok("1")))
        assertFalse(shouldRetryFnQueue(emptyPlaylist, attempt = 1, stillWaiting = true))
        val noSelection = buildFnPlayQueue(emptyList())
        assertFalse(shouldRetryFnQueue(noSelection, attempt = 1, stillWaiting = true))
    }

    @Test
    fun retryStopsAfterTwoBackoffs() {
        val allFailed = buildFnPlayQueue(listOf(failed("1")))
        assertEquals(3_000L, fnQueueRetryDelayMs(1))
        assertEquals(6_000L, fnQueueRetryDelayMs(2))
        assertNull(fnQueueRetryDelayMs(3))
        assertTrue(shouldRetryFnQueue(allFailed, attempt = 2, stillWaiting = true))
        assertFalse(shouldRetryFnQueue(allFailed, attempt = 3, stillWaiting = true))
        assertNull(fnQueueRetryDelayMs(0))
        assertNull(fnQueueRetryDelayMs(-1))
    }
}
