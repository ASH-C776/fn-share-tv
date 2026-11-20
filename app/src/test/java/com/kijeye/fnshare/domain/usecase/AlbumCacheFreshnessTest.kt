package com.kijeye.fnshare.domain.usecase

import com.kijeye.fnshare.domain.model.SharedAlbum
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 钉死缓存失效判据：冷启只看张数、手动刷新才比指纹，老缓存能自愈迁移。 */
class AlbumCacheFreshnessTest {

    private fun album(count: Int) = SharedAlbum(
        id = 1L,
        name = "测试相册",
        ownerName = "电视",
        photoCount = count,
        posterUrl = null,
    )

    private fun ids(vararg values: Long) = values.toList()

    // --- 指纹 ---

    @Test
    fun `相同id列表得到相同指纹`() {
        assertEquals(photoIdSig(ids(1, 2, 3)), photoIdSig(ids(1, 2, 3)))
    }

    @Test
    fun `id顺序不同指纹不同`() {
        assertTrue(photoIdSig(ids(1, 2, 3)) != photoIdSig(ids(3, 2, 1)))
    }

    @Test
    fun `空列表也有稳定指纹`() {
        val a = photoIdSig(emptyList())
        assertEquals(a, photoIdSig(emptyList()))
        assertEquals(12, a.length)
    }

    @Test
    fun `只取前24张 第25张不同不影响指纹`() {
        val base = (1L..24L).toList()
        val changed = base + 999L
        assertEquals(photoIdSig(base), photoIdSig(changed))
    }

    @Test
    fun `前24张里有一张不同 指纹就不同`() {
        val base = (1L..24L).toList()
        val changed = base.toMutableList().also { it[10] = 999L }
        assertTrue(photoIdSig(base) != photoIdSig(changed))
    }

    @Test
    fun `传整页id 也只按前24张算`() {
        val sig24 = photoIdSig((1L..24L).toList())
        val sig300 = photoIdSig((1L..300L).toList())
        assertEquals(sig24, sig300)
    }

    // --- needsRebuild ---

    @Test
    fun `没有快照 视为新相册需要重建`() {
        assertTrue(needsRebuild(album(10), null, null, CheckMode.MANUAL))
    }

    @Test
    fun `冷启 张数相同 即使探测显示变化也不重建`() {
        val snapshot = CacheSnapshot(photoCount = 10, sig = photoIdSig(ids(1, 2, 3)))
        val probe = ids(9, 8, 7)
        assertFalse(needsRebuild(album(10), snapshot, probe, CheckMode.COLD))
    }

    @Test
    fun `冷启 张数不同 需要重建`() {
        val snapshot = CacheSnapshot(photoCount = 10, sig = "")
        assertTrue(needsRebuild(album(11), snapshot, null, CheckMode.COLD))
    }

    @Test
    fun `手动刷新 张数不同 不需要探测就重建`() {
        val snapshot = CacheSnapshot(photoCount = 10, sig = "whatever")
        assertTrue(needsRebuild(album(11), snapshot, null, CheckMode.MANUAL))
    }

    @Test
    fun `手动刷新 张数相同且指纹相同 不重建`() {
        val sig = photoIdSig(ids(1, 2, 3))
        val snapshot = CacheSnapshot(photoCount = 10, sig = sig)
        assertFalse(needsRebuild(album(10), snapshot, ids(1, 2, 3), CheckMode.MANUAL))
    }

    @Test
    fun `手动刷新 张数相同但前24张变了 需要重建`() {
        val snapshot = CacheSnapshot(photoCount = 10, sig = photoIdSig((1L..24L).toList()))
        val probe = (1L..23L).toList() + 999L
        assertTrue(needsRebuild(album(10), snapshot, probe, CheckMode.MANUAL))
    }

    @Test
    fun `手动刷新 老缓存空指纹 探测有值则重建迁移`() {
        val snapshot = CacheSnapshot(photoCount = 10, sig = "")
        assertTrue(needsRebuild(album(10), snapshot, ids(1, 2, 3), CheckMode.MANUAL))
    }

    @Test
    fun `手动刷新 探测失败不冒然重建`() {
        val snapshot = CacheSnapshot(photoCount = 10, sig = "old")
        assertFalse(needsRebuild(album(10), snapshot, null, CheckMode.MANUAL))
    }
}
