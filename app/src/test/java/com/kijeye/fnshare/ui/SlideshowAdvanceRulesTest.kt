package com.kijeye.fnshare.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.26.2：「幻灯片开着时，人手翻到一条视频」这一格归人。
 *
 * 拿「梦里水乡」这本相册举例：幻灯片在放着照片，人按 → 翻到第 6 格（视频），
 * 这一格要一直停着，静止封面等着、按 OK 能完整播完；人自己翻回照片后，
 * 幻灯片才接着按节奏往下走。
 *
 * 这里只测纯规则，不测 Compose 组合。播放 / 暂停 / 播完不会重新点着计时，
 * 靠 UI 侧的 effect key 不含 phase，另有一条用例把「同一格状态恒定」钉住。
 */
class SlideshowAdvanceRulesTest {

    private fun mode(
        slideshow: Boolean = true,
        paused: Boolean = false,
        isVideo: Boolean = false,
        manualArrival: Boolean = false,
    ) = slideshowAdvanceMode(slideshow, paused, isVideo, manualArrival)

    @Test
    fun `幻灯片开着 照片正常到点前进`() {
        assertEquals(SlideshowAdvanceMode.DELAY_THEN_ADVANCE, mode())
        assertEquals(
            SlideshowAdvanceMode.DELAY_THEN_ADVANCE,
            mode(manualArrival = true),
        )
    }

    @Test
    fun `人手翻到的视频 整格停住`() {
        assertEquals(
            SlideshowAdvanceMode.HOLD,
            mode(isVideo = true, manualArrival = true),
        )
    }

    @Test
    fun `幻灯片自己落在视频上 兜底仍立刻跳过`() {
        assertEquals(
            SlideshowAdvanceMode.SKIP_VIDEO,
            mode(isVideo = true, manualArrival = false),
        )
    }

    @Test
    fun `幻灯片没开 一律不自动前进`() {
        assertEquals(SlideshowAdvanceMode.HOLD, mode(slideshow = false))
        assertEquals(
            SlideshowAdvanceMode.HOLD,
            mode(slideshow = false, isVideo = true, manualArrival = false),
        )
    }

    @Test
    fun `暂停优先 视频和照片都停住`() {
        assertEquals(SlideshowAdvanceMode.HOLD, mode(paused = true))
        assertEquals(
            SlideshowAdvanceMode.HOLD,
            mode(paused = true, isVideo = true, manualArrival = false),
        )
        assertEquals(
            SlideshowAdvanceMode.HOLD,
            mode(paused = true, isVideo = true, manualArrival = true),
        )
    }

    @Test
    fun `同一条视频从静止到播完 规则恒定不变`() {
        // UI 侧的 effect 只看 slideshow / paused / index / photo.id / manualArrival，
        // 不把播放状态放进 key：按 OK 起播、播放、暂停、播完都不会重新点着计时。
        repeat(5) {
            assertEquals(
                SlideshowAdvanceMode.HOLD,
                mode(isVideo = true, manualArrival = true),
            )
        }
    }

    @Test
    fun `人手翻回照片 不用额外复位就恢复自动前进`() {
        assertEquals(
            SlideshowAdvanceMode.HOLD,
            mode(isVideo = true, manualArrival = true),
        )
        assertEquals(
            SlideshowAdvanceMode.DELAY_THEN_ADVANCE,
            mode(isVideo = false, manualArrival = true),
        )
    }

    @Test
    fun `人手翻到的视频播完 不交给幻灯片续下一张`() {
        assertFalse(videoEndedAdvancesSlideshow(manualArrival = true))
    }

    @Test
    fun `不是人手翻来的视频播完 保持原行为`() {
        assertTrue(videoEndedAdvancesSlideshow(manualArrival = false))
    }
}
