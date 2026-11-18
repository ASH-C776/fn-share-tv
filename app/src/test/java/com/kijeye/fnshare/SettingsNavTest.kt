package com.kijeye.fnshare

import com.kijeye.fnshare.ui.NavDirection
import com.kijeye.fnshare.ui.ROW_COLS
import com.kijeye.fnshare.ui.nextFocusCell
import com.kijeye.fnshare.ui.wrapIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 设置页二维焦点导航的纯函数回归：双列行左右切格、上下跨行保持列、
 * 目标行只有一格时钳到第 0 列、到顶到底循环。
 *
 * 全部纯 JVM，不需要设备也不需要 Compose runtime —— 双列布局最容易出的
 * 「按左跑到别的行」「从整行往上落错列」在这里就能挡住。
 * 0.21.2 起「闲置保护 | 飞牛音乐」并成一行双列，全页 6 行一屏放得下。
 */
class SettingsNavTest {

    @Test
    fun leftMovesBetweenTwoColumnsOnly() {
        assertEquals(0 to 0, nextFocusCell(0, 1, NavDirection.LEFT, ROW_COLS))
        assertNull(nextFocusCell(0, 0, NavDirection.LEFT, ROW_COLS))
    }

    @Test
    fun rightStopsAtLastColumn() {
        assertEquals(0 to 1, nextFocusCell(0, 0, NavDirection.RIGHT, ROW_COLS))
        assertNull(nextFocusCell(0, 1, NavDirection.RIGHT, ROW_COLS))
    }

    @Test
    fun downKeepsColumnBetweenDoubleRows() {
        assertEquals(1 to 0, nextFocusCell(0, 0, NavDirection.DOWN, ROW_COLS))
        assertEquals(1 to 1, nextFocusCell(0, 1, NavDirection.DOWN, ROW_COLS))
        assertEquals(3 to 1, nextFocusCell(2, 1, NavDirection.DOWN, ROW_COLS))
        // 更多行也是双列：从缓存动作行右格往下要落在更多行右格（飞牛音乐）。
        assertEquals(4 to 1, nextFocusCell(3, 1, NavDirection.DOWN, ROW_COLS))
    }

    @Test
    fun downFromMoreRowKeepsColumnIntoAccountRow() {
        // 底部账号行也是双列：更多行 (4,1) 往下落到退出登录 (5,1)，保持列。
        assertEquals(5 to 1, nextFocusCell(4, 1, NavDirection.DOWN, ROW_COLS))
        assertEquals(5 to 0, nextFocusCell(4, 0, NavDirection.DOWN, ROW_COLS))
        assertEquals(4 to 1, nextFocusCell(5, 1, NavDirection.UP, ROW_COLS))
    }

    @Test
    fun upKeepsColumnBetweenDoubleRows() {
        assertEquals(0 to 1, nextFocusCell(1, 1, NavDirection.UP, ROW_COLS))
        assertEquals(2 to 0, nextFocusCell(3, 0, NavDirection.UP, ROW_COLS))
        assertEquals(3 to 1, nextFocusCell(4, 1, NavDirection.UP, ROW_COLS))
    }

    @Test
    fun topWrapsToBottomAndBottomWrapsToTop() {
        assertEquals(5 to 0, nextFocusCell(0, 0, NavDirection.UP, ROW_COLS))
        assertEquals(5 to 1, nextFocusCell(0, 1, NavDirection.UP, ROW_COLS))
        assertEquals(0 to 0, nextFocusCell(5, 0, NavDirection.DOWN, ROW_COLS))
        assertEquals(0 to 1, nextFocusCell(5, 1, NavDirection.DOWN, ROW_COLS))
    }

    @Test
    fun accountRowSwitchesBetweenMoreAndSignOut() {
        // 底部账号行也是双列：左右键在「更多」和「退出登录」之间切格。
        assertEquals(5 to 1, nextFocusCell(5, 0, NavDirection.RIGHT, ROW_COLS))
        assertEquals(5 to 0, nextFocusCell(5, 1, NavDirection.LEFT, ROW_COLS))
        assertNull(nextFocusCell(5, 0, NavDirection.LEFT, ROW_COLS))
        assertNull(nextFocusCell(5, 1, NavDirection.RIGHT, ROW_COLS))
    }

    @Test
    fun moreRowSwitchesBetweenIdleAndFnMusic() {
        assertEquals(4 to 1, nextFocusCell(4, 0, NavDirection.RIGHT, ROW_COLS))
        assertEquals(4 to 0, nextFocusCell(4, 1, NavDirection.LEFT, ROW_COLS))
        assertNull(nextFocusCell(4, 1, NavDirection.RIGHT, ROW_COLS))
        assertNull(nextFocusCell(4, 0, NavDirection.LEFT, ROW_COLS))
    }

    @Test
    fun gridShapeMatchesPageLayout() {
        // 显示 2 行 + 缓存 2 行 + 更多 1 行 + 底部账号 1 行，全页 6 行都是双列。
        assertEquals(listOf(2, 2, 2, 2, 2, 2), ROW_COLS.toList())
    }

    @Test
    fun wrapIndexWrapsForwardFromLastToFirst() {
        // 闲置保护按到「30 分钟」、焦点颜色按到最后一色，再按 OK 都要回到第一档，
        // 以前 coerceIn 会卡在最后一档，遥控器看着像失灵。
        assertEquals(0, wrapIndex(3, 1, 4))
        assertEquals(1, wrapIndex(0, 1, 4))
        assertEquals(0, wrapIndex(4, 1, 5))
    }

    @Test
    fun wrapIndexWrapsBackwardWithNegativeDelta() {
        assertEquals(3, wrapIndex(0, -1, 4))
        assertEquals(2, wrapIndex(3, -1, 4))
    }
}
