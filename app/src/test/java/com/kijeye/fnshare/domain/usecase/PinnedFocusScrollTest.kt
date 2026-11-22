package com.kijeye.fnshare.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 浮动焦点的滚动算式：焦点格顶边始终往视口四成高度处拉。
 *
 * 这些用例把「方向」和「边界」钉死——方向写反会导致往上翻时整页往下窜，
 * 是这次改动最容易犯的错，必须靠单测先兜住。
 */
class PinnedFocusScrollTest {

    /** 视口 1000px 时，钉住线在 400px。 */
    private val viewport = 1000f
    private val anchor = 400f

    @Test
    fun `焦点正好在钉住线上 不需要滚`() {
        assertEquals(0f, pinnedScrollDistance(anchor, viewport), 0.01f)
    }

    @Test
    fun `焦点在钉住线下方 返回正数 内容上移`() {
        // 焦点顶边在 700px，要把它拉回 400px，内容得往上走 300px。
        val d = pinnedScrollDistance(700f, viewport)
        assertEquals(300f, d, 0.01f)
        assertTrue("往下翻应是正距离", d > 0f)
    }

    @Test
    fun `焦点在钉住线上方 返回负数 内容下移`() {
        // 焦点顶边在 100px，要拉回 400px，内容得往下走 300px。
        val d = pinnedScrollDistance(100f, viewport)
        assertEquals(-300f, d, 0.01f)
        assertTrue("往上翻应是负距离", d < 0f)
    }

    @Test
    fun `焦点在视口上方 偏移为负 仍返回负数`() {
        val d = pinnedScrollDistance(-200f, viewport)
        assertEquals(-600f, d, 0.01f)
    }

    @Test
    fun `焦点在视口顶部 想往下拉 但列表会被夹在顶部`() {
        // 第 0 行顶边约等于 0：算式给负数（想往下拉），实际由列表 clamp 在 0，
        // 焦点因此老实待在顶部——这就是「阶段一焦点在最上面」的来源。
        val d = pinnedScrollDistance(0f, viewport)
        assertEquals(-anchor, d, 0.01f)
        assertTrue("顶部时算式为负，依赖列表夹紧", d < 0f)
    }

    @Test
    fun `视口高度变化时钉住线线性缩放`() {
        // 钉住线 = 视口 × 0.4：500 高时 200，100 高时 40。落线上距离都是 0。
        assertEquals(0f, pinnedScrollDistance(200f, 500f), 0.01f)
        assertEquals(0f, pinnedScrollDistance(40f, 100f), 0.01f)
        // 同一偏移在不同视口下的距离差，正好等于钉住线的差。
        assertEquals(
            pinnedScrollDistance(200f, 1000f) - pinnedScrollDistance(200f, 500f),
            -(400f - 200f),
            0.01f,
        )
    }

    @Test
    fun `距离只取决于偏移与视口 与格子高度无关`() {
        // 同一偏移、同一视口，结果恒定；格子多高都不影响钉住线。
        assertEquals(
            pinnedScrollDistance(520f, viewport),
            pinnedScrollDistance(520f, viewport),
            0f,
        )
    }
}
