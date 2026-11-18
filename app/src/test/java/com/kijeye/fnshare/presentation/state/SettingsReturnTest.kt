package com.kijeye.fnshare.presentation.state

import com.kijeye.fnshare.domain.model.SharedAlbum
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 设置页的返回目标（0.28.0）。
 *
 * 从相册网格按菜单键进设置，返回时必须回到那本相册——不然人改完一项设置就被丢回
 * 相册墙，还得重新找刚才在看的相册。其他来源（看图页、登录页、设置页自身）一律回相册墙。
 */
class SettingsReturnTest {

    private val album = SharedAlbum(
        id = 5,
        name = "卡比",
        ownerName = "tv",
        photoCount = 3160,
        posterUrl = null,
    )

    @Test
    fun `从相册墙进设置 返回相册墙`() {
        assertEquals(Screen.Wall, settingsReturnTarget(Screen.Wall))
    }

    @Test
    fun `从网格进设置 返回同一本相册`() {
        assertEquals(Screen.Grid(album), settingsReturnTarget(Screen.Grid(album)))
    }

    @Test
    fun `其他来源一律回相册墙`() {
        assertEquals(Screen.Wall, settingsReturnTarget(Screen.Viewer(album, 3)))
        assertEquals(Screen.Wall, settingsReturnTarget(Screen.Login))
        assertEquals(Screen.Wall, settingsReturnTarget(null))
    }

    @Test
    fun `更多页不作为设置入口来源 避免返回到已关闭的页面`() {
        // 「更多」页有自己的返回路径（固定回设置页），不经过 settingsReturn。
        // 万一以后有人在更多页直接开设置，settingsReturn 会被记成 Screen.More，
        // 这条断言保证那种情况下仍回相册墙，而不是返回到一个早该关掉的信息页。
        assertEquals(Screen.Wall, settingsReturnTarget(Screen.More))
    }
}
