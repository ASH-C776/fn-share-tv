package com.kijeye.fnshare.domain.usecase

import com.kijeye.fnshare.domain.model.SharedPhoto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「小相册」8 项：3、6 是视频，1/2/4/5/7/8 是照片。
 * 洗牌函数注入成 reversed，断言才是确定的；另有一例用真随机验不重不漏。
 */
class SlideshowDeckTest {

    private fun photo(id: Long, video: Boolean = false) = SharedPhoto(
        id = id,
        thumbUrl = null,
        fullUrl = null,
        isVideo = video,
        videoUrl = if (video) "v$id" else null,
    )

    private val album = listOf(
        photo(1), photo(2), photo(3, video = true), photo(4),
        photo(5), photo(6, video = true), photo(7), photo(8),
    )
    private val photoIds = listOf(1L, 2L, 4L, 5L, 7L, 8L)

    /** 确定性洗牌：倒序，方便断言「确实洗过」和「按表发」。 */
    private fun deck() = SlideshowDeck(shuffle = { it.reversed() })

    private fun drain(d: SlideshowDeck): List<Long> =
        generateSequence { d.next() }.toList()

    @Test
    fun `视频不进表 且 id 去重`() {
        assertEquals(photoIds, slideshowDeckIds(album))
        assertEquals(photoIds, slideshowDeckIds(album + photo(1)))
    }

    @Test
    fun `开播只排一次序 六张各一次`() {
        val d = deck()
        d.start(albumId = 10L, photoIds = photoIds)
        assertEquals(6, d.remaining)
        assertEquals(photoIds.reversed(), drain(d))
        assertEquals(0, d.remaining)
    }

    @Test
    fun `一轮之内不重复不漏 发完返回 null`() {
        val d = deck()
        d.start(10L, photoIds)
        val seen = drain(d)
        assertEquals(photoIds.sorted(), seen.sorted())
        assertEquals(seen.size, seen.distinct().size)
        assertNull(d.next())
    }

    @Test
    fun `peek 不推进 且与下一次 next 一致`() {
        val d = deck()
        d.start(10L, photoIds)
        val first = d.peek()
        assertEquals(6, d.remaining)
        assertEquals(first, d.peek())
        assertEquals(first, d.next())
        assertEquals(5, d.remaining)
    }

    @Test
    fun `续页新增的两张接在队尾 不重复`() {
        val d = deck()
        d.start(10L, listOf(1L, 2L, 4L))
        repeat(2) { d.next() }
        val added = d.sync(10L, listOf(1L, 2L, 4L, 5L, 7L))
        assertEquals(2, added)
        // 已发 4、2，剩下 1，队尾追加洗过的 7、5
        assertEquals(listOf(1L, 7L, 5L), drain(d))
    }

    @Test
    fun `重复 sync 同一批不再追加`() {
        val d = deck()
        d.start(10L, photoIds)
        assertEquals(0, d.sync(10L, photoIds))
        assertEquals(6, d.remaining)
        assertEquals(0, d.sync(10L, photoIds))
        assertEquals(6, d.remaining)
    }

    @Test
    fun `换相册整体重置 旧表不残留`() {
        val d = deck()
        d.start(10L, photoIds)
        repeat(3) { d.next() }
        assertEquals(2, d.sync(20L, listOf(91L, 92L)))
        assertEquals(2, d.remaining)
        assertEquals(listOf(92L, 91L), drain(d))
    }

    @Test
    fun `restartRound 重洗后仍是全部照片 且 cursor 归零`() {
        val d = deck()
        d.start(10L, photoIds)
        drain(d)
        assertNull(d.next())
        assertTrue(d.restartRound())
        assertEquals(6, d.remaining)
        assertEquals(photoIds.sorted(), drain(d).sorted())
    }

    @Test
    fun `空表 restartRound 返回 false`() {
        val d = deck()
        assertFalse(d.restartRound())
        d.start(10L, emptyList())
        assertFalse(d.restartRound())
    }

    @Test
    fun `clear 之后什么都发不出来`() {
        val d = deck()
        d.start(10L, photoIds)
        d.clear()
        assertEquals(0, d.remaining)
        assertNull(d.peek())
        assertNull(d.next())
    }

    @Test
    fun `真随机洗牌 一轮仍不重不漏 视频 id 不出现`() {
        val d = SlideshowDeck()
        d.start(10L, slideshowDeckIds(album))
        val seen = drain(d)
        assertEquals(photoIds.sorted(), seen.sorted())
        assertEquals(6, seen.distinct().size)
        assertFalse(seen.contains(3L))
        assertFalse(seen.contains(6L))
    }

    @Test
    fun `一轮中途续页 最终整本不重不漏`() {
        val d = deck()
        d.start(10L, listOf(1L, 2L, 4L))
        assertEquals(4L, d.next())
        d.sync(10L, listOf(1L, 2L, 4L, 5L, 7L, 8L))
        val seen = mutableListOf(4L)
        seen += drain(d)
        assertEquals(listOf(1L, 2L, 4L, 5L, 7L, 8L), seen.sorted())
        assertEquals(seen.size, seen.distinct().size)
    }
}
