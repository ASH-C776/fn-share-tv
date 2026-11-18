package com.kijeye.fnshare.presentation.state

import com.kijeye.fnshare.domain.model.SharedAlbum
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoPageGuardTest {

    private fun album(id: Long) = SharedAlbum(
        id = id,
        name = "相册$id",
        ownerName = "家里人",
        photoCount = 42,
        posterUrl = null,
    )

    @Test
    fun `网格页还是发出请求的那一本 放行`() {
        assertTrue(photoPageBelongsTo(Screen.Grid(album(25)), 25L))
    }

    @Test
    fun `看图页还是发出请求的那一本 放行`() {
        // 0.26.1 起看图页也会续页，别把这一屏误挡掉。
        assertTrue(photoPageBelongsTo(Screen.Viewer(album(25), index = 6), 25L))
    }

    @Test
    fun `网格页已经换成另一本 挡掉`() {
        assertFalse(photoPageBelongsTo(Screen.Grid(album(5)), 25L))
    }

    @Test
    fun `看图页已经换成另一本 挡掉`() {
        assertFalse(photoPageBelongsTo(Screen.Viewer(album(5), index = 2), 25L))
    }

    @Test
    fun `退回相册墙 挡掉`() {
        assertFalse(photoPageBelongsTo(Screen.Wall, 25L))
    }

    @Test
    fun `设置页与登录页 挡掉`() {
        assertFalse(photoPageBelongsTo(Screen.Settings, 25L))
        assertFalse(photoPageBelongsTo(Screen.Login, 25L))
    }

    @Test
    fun `同一本相册从网格进看图 续页仍放行`() {
        // 请求在网格发出、回来时人已点进看图页：是同一本，数据该收。
        assertTrue(photoPageBelongsTo(Screen.Viewer(album(25), index = 0), 25L))
    }
}
