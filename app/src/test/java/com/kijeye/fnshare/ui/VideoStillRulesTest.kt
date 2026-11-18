package com.kijeye.fnshare.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.25.0 / 0.25.1：看图页「←/→ 翻到的视频先静止、按 OK 才起播」的状态机回归。
 *
 * 这里只测纯规则，不测 Compose 组合。要钉死的是三条最容易写错的时序：
 *  - 「网格直接点视频」与「页内翻到视频」必须走两条完全不同的链路；
 *  - 起播授权属于「本次停留」（UI 侧 remember(photo.id) 的局部 state），
 *    翻走再翻回同一条也不能续播，绝不能靠异步 effect 事后归零；
 *  - 封面一直盖到首帧真到，报错时也不撤，任何时刻都有满不透明内容。
 */
class VideoStillRulesTest {

    // ── 进屏 / 换图后的授权初值 ──────────────────────────────────────────

    @Test
    fun `网格直接点照片_没有起播授权`() {
        val armed = videoEntryArmed(isVideo = false, navigated = false)
        assertFalse(armed)
        assertFalse(videoIsArmed(armed, isVideo = false))
    }

    @Test
    fun `网格直接点视频_自动授权_立即走原来的黑底转圈`() {
        val armed = videoEntryArmed(isVideo = true, navigated = false)
        assertTrue(armed)
        assertTrue(videoIsArmed(armed, isVideo = true))
    }

    @Test
    fun `页内从照片翻到视频_初值不授权_保持静止`() {
        val armed = videoEntryArmed(isVideo = true, navigated = true)
        assertFalse(videoIsArmed(armed, isVideo = true))
    }

    @Test
    fun `播放中翻到照片再翻回同一个视频_授权不复活_也要静止`() {
        // 0.25.0 在 MuMu 上实测到的越界：授权绑 id，翻回来 id 相同 → 自动续播。
        // 0.25.1 起授权是 remember(photo.id) 的局部 state：每换一次图都重建。
        val entry = videoEntryArmed(isVideo = true, navigated = false)
        assertTrue(videoIsArmed(entry, isVideo = true))
        val atPhoto = videoEntryArmed(isVideo = false, navigated = true)
        assertFalse(videoIsArmed(atPhoto, isVideo = false))
        val backToSameVideo = videoEntryArmed(isVideo = true, navigated = true)
        assertFalse(videoIsArmed(backToSameVideo, isVideo = true))
        // 只有重新按 OK 才恢复授权。
        assertTrue(
            videoIsArmed(videoArmAfterConfirm(backToSameVideo, isVideo = true), isVideo = true)
        )
    }

    // ── 按 OK 的授权状态机 ────────────────────────────────────────────────

    @Test
    fun `静止态按OK_授权给本次停留_此时才创建播放器`() {
        val before = videoEntryArmed(isVideo = true, navigated = true)
        val after = videoArmAfterConfirm(before, isVideo = true)
        assertTrue(after)
        assertTrue(videoIsArmed(after, isVideo = true))
        assertTrue(videoTimeoutEnabled(true))
    }

    @Test
    fun `已经起播再按OK_授权不变_不会重复创建播放器`() {
        assertTrue(videoArmAfterConfirm(true, isVideo = true))
    }

    @Test
    fun `静止态不跑超时计时`() {
        assertFalse(videoTimeoutEnabled(false))
    }

    @Test
    fun `按OK后翻到另一段视频_授权自动失效`() {
        val confirmed = videoArmAfterConfirm(false, isVideo = true)
        assertTrue(confirmed)
        // remember(photo.id) 重建：页内翻到的视频初值恒不授权。
        val next = videoEntryArmed(isVideo = true, navigated = true)
        assertFalse(videoIsArmed(next, isVideo = true))
    }

    @Test
    fun `按OK后翻到照片_照片不含授权`() {
        val atPhoto = videoEntryArmed(isVideo = false, navigated = true)
        assertFalse(videoIsArmed(atPhoto, isVideo = false))
    }

    // ── 封面可见性门闩 ────────────────────────────────────────────────────

    @Test
    fun `网格直接点的视频_任何阶段都不画封面`() {
        assertFalse(
            videoCoverVisible(coverOrigin = false, armed = true, firstFrameReady = false, error = false)
        )
    }

    @Test
    fun `页内翻到的视频_静止时封面盖着_按OK后首帧未到仍盖着`() {
        assertTrue(
            videoCoverVisible(coverOrigin = true, armed = false, firstFrameReady = false, error = false)
        )
        assertTrue(
            videoCoverVisible(coverOrigin = true, armed = true, firstFrameReady = false, error = false)
        )
    }

    @Test
    fun `首帧真到才整块撤封面_同一帧露出视频画面`() {
        assertFalse(
            videoCoverVisible(coverOrigin = true, armed = true, firstFrameReady = true, error = false)
        )
    }

    @Test
    fun `起播报错时封面不撤_错误提示叠在封面上不露黑`() {
        assertTrue(
            videoCoverVisible(coverOrigin = true, armed = true, firstFrameReady = true, error = true)
        )
    }

    // ── 左右键与浮层 ──────────────────────────────────────────────────────

    @Test
    fun `STILL态左右键一律翻页_不落到seek`() {
        assertTrue(
            videoDirectionSteps(still = true, loading = false, ended = false, controlsVisible = true)
        )
    }

    @Test
    fun `播放中控制栏亮着是seek_收起后仍是翻页_加载与播完都翻页`() {
        assertFalse(
            videoDirectionSteps(still = false, loading = false, ended = false, controlsVisible = true)
        )
        assertTrue(
            videoDirectionSteps(still = false, loading = false, ended = false, controlsVisible = false)
        )
        assertTrue(
            videoDirectionSteps(still = false, loading = true, ended = false, controlsVisible = true)
        )
        assertTrue(
            videoDirectionSteps(still = false, loading = false, ended = true, controlsVisible = true)
        )
    }

    @Test
    fun `静止态不亮状态牌与控制栏`() {
        assertFalse(
            videoChromeVisible(still = true, loading = false, error = false, controlsVisible = true)
        )
        assertFalse(
            videoChromeVisible(still = false, loading = true, error = false, controlsVisible = true)
        )
        assertFalse(
            videoChromeVisible(still = false, loading = false, error = true, controlsVisible = true)
        )
        assertTrue(
            videoChromeVisible(still = false, loading = false, error = false, controlsVisible = true)
        )
    }
}
