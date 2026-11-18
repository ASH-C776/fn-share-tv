package com.kijeye.fnshare.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 槽里「预览 / 原图」两层状态的回归（0.23.3）。
 *
 * 这两层曾经各自维护一个 alpha 动画：原图 onSuccess 一到就把预览层撤掉，
 * 而原图的淡入才刚从 0 起步，中间那 250ms 两层都不画，露出槽的黑底——
 * 快速连按时就是「暗一下」。现在两层严格互补、共用同一个 originalLoaded。
 *
 * 这里只测状态机本身（纯 Kotlin，不需要 Android/Composer）：任何一层成功
 * 都必须让 [PhotoSlot.loaded] 为真，否则整槽会被判定「没画出内容」而黑掉。
 */
class PhotoSlotTest {

    private fun slot(dual: Boolean = true) = PhotoSlot(0f).apply { dualLayer = dual }

    @Test
    fun `双层_预览先到_立刻算有内容并放行揭幕`() {
        val s = slot()
        s.onPreviewResult(true)
        assertTrue(s.ready)
        assertTrue(s.loaded)
        assertTrue(s.previewDone)
        assertFalse(s.originalDone)
    }

    @Test
    fun `双层_原图后到_仍然算有内容`() {
        val s = slot()
        s.onPreviewResult(true)
        s.onOriginalResult(true)
        assertTrue(s.ready)
        assertTrue(s.loaded)
        assertTrue(s.originalLoaded)
    }

    @Test
    fun `双层_原图加载失败_预览继续顶住画面`() {
        val s = slot()
        s.onPreviewResult(true)
        s.onOriginalResult(false)
        // 失败也算「有结果」，不能让揭幕一直等；但原图层不画，预览层照常画。
        assertTrue(s.originalDone)
        assertFalse(s.originalLoaded)
        assertTrue(s.loaded)
        assertTrue(s.ready)
    }

    @Test
    fun `双层_两层都失败_不冒充有内容_交给垫底图`() {
        val s = slot()
        s.onPreviewResult(false)
        s.onOriginalResult(false)
        assertFalse(s.loaded)
        assertTrue(s.ready)
    }

    @Test
    fun `单层_预览层的残留标记不算数`() {
        val s = slot(dual = false)
        s.onPreviewResult(true)
        // 单层时预览层根本不参与绘制，loaded 只认原图层。
        assertFalse(s.loaded)
        s.onOriginalResult(true)
        assertTrue(s.loaded)
    }

    @Test
    fun `换图_resetMarks_把所有标志归零`() {
        val s = slot()
        s.onPreviewResult(true)
        s.onOriginalResult(true)
        s.resetMarks()
        assertFalse(s.ready)
        assertFalse(s.previewDone)
        assertFalse(s.previewLoaded)
        assertFalse(s.originalDone)
        assertFalse(s.originalLoaded)
        assertFalse(s.loaded)
    }

    @Test
    fun `双层_只有原图到位_也算有内容`() {
        // 慢速切换走的正是这条路径：100ms 宽限内原图命中，预览可能还没回调。
        val s = slot()
        s.onOriginalResult(true)
        assertTrue(s.ready)
        assertTrue(s.loaded)
    }
}
