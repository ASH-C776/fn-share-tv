package com.kijeye.fnshare.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 信息栏那行技术文案。都是纯格式化，出错只会让电视上多一行怪文字（比如 `0:00 · 0 × 0`），
 * 所以这里把「不知道就别写」钉死。
 */
class MediaInfoTextTest {

    @Test
    fun `时长按分秒两位 满一小时加小时`() {
        assertEquals("00:09", formatMediaDuration(9))
        assertEquals("01:23", formatMediaDuration(83))
        assertEquals("09:13", formatMediaDuration(553))
        assertEquals("10:00", formatMediaDuration(600))
        assertEquals("1:00:00", formatMediaDuration(3600))
        assertEquals("1:01:40", formatMediaDuration(3700))
    }

    @Test
    fun `时长为 0 或负数当不知道`() {
        assertNull(formatMediaDuration(0))
        assertNull(formatMediaDuration(-5))
    }

    @Test
    fun `像素任一为 0 当不知道`() {
        assertEquals("1920 × 1080", formatPixels(1920, 1080))
        assertEquals("1080 × 1920", formatPixels(1080, 1920))
        assertNull(formatPixels(0, 1080))
        assertNull(formatPixels(1920, 0))
        assertNull(formatPixels(0, 0))
    }

    @Test
    fun `视频给时长加像素 照片只给像素`() {
        assertEquals("01:23 · 1080 × 1920", mediaFactsText(1080, 1920, 83))
        assertEquals("4096 × 3072", mediaFactsText(4096, 3072, 0))
    }

    @Test
    fun `什么都不知道就整行不出现`() {
        assertNull(mediaFactsText(0, 0, 0))
    }

    @Test
    fun `只有时长也能显示`() {
        assertEquals("01:23", mediaFactsText(0, 0, 83))
    }
}
