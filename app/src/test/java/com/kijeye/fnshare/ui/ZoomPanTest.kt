package com.kijeye.fnshare.ui

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 放大后方向键平移的回归（0.23.4）。
 *
 * 旧实现按「拖照片」做：按 ← 照片往左跑，在遥控器上看着像反的。现在方向键移动的是
 * 取景框，照片要往相反方向挪；步长也从固定 48dp 改成「单侧行程 / 10」，
 * 这样 1.7x / 2.5x、1080p / 4K 都是按同样次数到边。
 *
 * 这里只测纯数学，不碰 Compose 运行时、焦点和翻页。
 */
class ZoomPanTest {

    private val tvWidth = 1920f
    private val tvHeight = 1080f

    @Test
    fun `默认档没有可平移行程`() {
        assertEquals(0f, zoomPanLimit(1f, tvWidth), 0f)
        assertEquals(0f, zoomPanLimit(1f, tvHeight), 0f)
    }

    @Test
    fun `放大档的单侧行程等于多出来的半幅`() {
        assertEquals(672f, zoomPanLimit(1.7f, tvWidth), 0.001f)
        assertEquals(378f, zoomPanLimit(1.7f, tvHeight), 0.001f)
        assertEquals(1440f, zoomPanLimit(2.5f, tvWidth), 0.001f)
    }

    @Test
    fun `按左镜头往左_照片往右移`() {
        val d = zoomPanDelta(-1, 0, 67.2f, 37.8f)
        assertTrue(d.x > 0f)
        assertEquals(0f, d.y, 0f)
    }

    @Test
    fun `按右镜头往右_照片往左移`() {
        val d = zoomPanDelta(1, 0, 67.2f, 37.8f)
        assertTrue(d.x < 0f)
        assertEquals(0f, d.y, 0f)
    }

    @Test
    fun `按上镜头往上_照片往下移_按下则相反`() {
        assertTrue(zoomPanDelta(0, -1, 67.2f, 37.8f).y > 0f)
        assertTrue(zoomPanDelta(0, 1, 67.2f, 37.8f).y < 0f)
        assertEquals(0f, zoomPanDelta(0, -1, 67.2f, 37.8f).x, 0f)
    }

    @Test
    fun `任意放大档从正中到边都固定十次按键`() {
        for (scale in listOf(1.7f, 2.5f)) {
            for (screen in listOf(tvWidth, tvHeight, 3840f, 2160f)) {
                val limit = zoomPanLimit(scale, screen)
                val step = zoomPanStep(limit)
                var pan = Offset.Zero
                repeat(ZOOM_PAN_STEPS) {
                    pan = applyZoomPan(pan, Offset(step, 0f), limit, limit)
                }
                assertEquals("scale=$scale screen=$screen", limit, pan.x, 0.001f)
            }
        }
    }

    @Test
    fun `一直按同方向会被边界夹住不露黑底`() {
        val limit = zoomPanLimit(1.7f, tvWidth)
        val step = zoomPanStep(limit)
        var pan = Offset.Zero
        repeat(ZOOM_PAN_STEPS * 3) {
            pan = applyZoomPan(pan, Offset(step, 0f), limit, zoomPanLimit(1.7f, tvHeight))
        }
        assertEquals(limit, pan.x, 0.001f)
    }

    @Test
    fun `反向按能退回正中`() {
        val limit = zoomPanLimit(2.5f, tvWidth)
        val step = zoomPanStep(limit)
        var pan = Offset.Zero
        repeat(4) { pan = applyZoomPan(pan, Offset(step, 0f), limit, limit) }
        repeat(4) { pan = applyZoomPan(pan, Offset(-step, 0f), limit, limit) }
        assertEquals(0f, pan.x, 0.001f)
    }
}
