package com.kijeye.fnshare.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「大相册」的情形：开播时只取回头一页（比如梦里水乡 3099 项里的 60 项），
 * 后面还有没取回来的页。这些用例钉死：一轮没播完整本之前不许重洗、不许收尾。
 */
class SlideshowDeckPolicyTest {

    @Test
    fun `表发完但还有页 先续页`() {
        assertEquals(
            DeckExhaustion.LOAD_MORE,
            decideDeckExhaustion(hasMorePhotos = true, loopSlideshow = false, canRestart = true),
        )
    }

    @Test
    fun `表发完还有页 循环开着也不许提前重洗`() {
        assertEquals(
            DeckExhaustion.LOAD_MORE,
            decideDeckExhaustion(hasMorePhotos = true, loopSlideshow = true, canRestart = true),
        )
    }

    @Test
    fun `整本取完 循环开 有照片 重洗一轮`() {
        assertEquals(
            DeckExhaustion.RESTART_ROUND,
            decideDeckExhaustion(hasMorePhotos = false, loopSlideshow = true, canRestart = true),
        )
    }

    @Test
    fun `整本取完 循环关 干净收尾`() {
        assertEquals(
            DeckExhaustion.FINISH,
            decideDeckExhaustion(hasMorePhotos = false, loopSlideshow = false, canRestart = true),
        )
    }

    @Test
    fun `整本取完 循环开 但一本照片都没有 也要收尾不空转`() {
        assertEquals(
            DeckExhaustion.FINISH,
            decideDeckExhaustion(hasMorePhotos = false, loopSlideshow = true, canRestart = false),
        )
    }

    @Test
    fun `表快见底时提前续页`() {
        assertTrue(shouldPrefetchDeck(remaining = 3, hasMorePhotos = true, photosLoading = false))
        assertTrue(shouldPrefetchDeck(remaining = 1, hasMorePhotos = true, photosLoading = false))
        assertTrue(shouldPrefetchDeck(remaining = 2, hasMorePhotos = true, photosLoading = false))
    }

    @Test
    fun `表还厚 不提前续页`() {
        assertFalse(shouldPrefetchDeck(remaining = 4, hasMorePhotos = true, photosLoading = false))
        assertFalse(shouldPrefetchDeck(remaining = 28, hasMorePhotos = true, photosLoading = false))
    }

    @Test
    fun `表已发完 交给表空决策 不在这里重复续页`() {
        assertFalse(shouldPrefetchDeck(remaining = 0, hasMorePhotos = true, photosLoading = false))
    }

    @Test
    fun `整本取完 或 正在取页 都不再续`() {
        assertFalse(shouldPrefetchDeck(remaining = 2, hasMorePhotos = false, photosLoading = false))
        assertFalse(shouldPrefetchDeck(remaining = 2, hasMorePhotos = true, photosLoading = true))
    }

    @Test
    fun `canRestart 跟表的存亡一致`() {
        val d = SlideshowDeck(shuffle = { it.reversed() })
        assertFalse(d.canRestart)
        d.start(10L, listOf(1L, 2L))
        assertTrue(d.canRestart)
        d.clear()
        assertFalse(d.canRestart)
        d.start(10L, emptyList())
        assertFalse(d.canRestart)
    }

    @Test
    fun `提前续页阈值可注入 边界是闭区间`() {
        assertTrue(shouldPrefetchDeck(5, true, false, margin = 5))
        assertFalse(shouldPrefetchDeck(6, true, false, margin = 5))
        assertFalse(shouldPrefetchDeck(0, true, false, margin = 5))
    }
}
