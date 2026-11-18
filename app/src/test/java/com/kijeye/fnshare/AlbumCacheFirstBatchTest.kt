package com.kijeye.fnshare

import com.kijeye.fnshare.data.cache.CACHE_VERSION
import com.kijeye.fnshare.data.cache.parseAlbumEntry
import com.kijeye.fnshare.data.fn.SharedPhoto
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 打开相册「即时切屏 + 首批先到」的离线回归（0.24.5）。
 *
 * `parseAlbumEntry` 是纯函数，所以不用上电视掐秒表也能钉住两件事：
 * ① 首批回调必须**恰好一次**、拿到的是按列表顺序的前 N 条；
 * ② 整本结果仍然完整，不能因为提前回调就少解析后面的照片。
 * 这两条任何一条坏了，用户看到的不是「点完等一下」就是「相册只剩 24 张」。
 */
class AlbumCacheFirstBatchTest {

    // 版本号跟着 AlbumCache.CACHE_VERSION 走：这里测的是「首批回调」，不该因为缓存格式
    // 升级（0.27.0 → v2）就连带失败，也不该另抄一个数字。
    private fun entryJson(count: Int, version: Int = CACHE_VERSION): String {
        val items = JSONArray()
        repeat(count) { i ->
            val urls = JSONArray()
            urls.put("/t/$i.jpg")
            urls.put("/m/$i.jpg")
            urls.put("/o/$i.jpg")
            val item = JSONObject()
            item.put("pid", i.toLong())
            item.put("urls", urls)
            items.put(item)
        }
        val obj = JSONObject()
        obj.put("v", version)
        obj.put("id", 7L)
        obj.put("name", "卡比")
        obj.put("count", count)
        obj.put("savedAt", 123L)
        obj.put("items", items)
        return obj.toString()
    }

    private fun ids(photos: List<SharedPhoto>): List<Long> = photos.map { it.id }

    @Test
    fun `首批只回调一次 且是列表顺序的前N条`() {
        val batches = mutableListOf<List<Long>>()
        val entry = parseAlbumEntry(
            text = entryJson(50),
            albumId = 7L,
            baseUrl = "http://nas:5666",
            firstBatchSize = 24,
        ) { batch -> batches += ids(batch) }

        assertNotNull(entry)
        assertEquals(1, batches.size)
        assertEquals((0L..23L).toList(), batches[0])
        // 整本仍然完整，不能因为提前交了一批就少解析。
        assertEquals(50, entry!!.photos.size)
        assertEquals(49L, entry.photos.last().id)
    }

    @Test
    fun `首批是副本 后续解析不会改动已交出去的列表`() {
        var snapshot: List<SharedPhoto> = emptyList()
        val entry = parseAlbumEntry(
            text = entryJson(50),
            albumId = 7L,
            baseUrl = "http://nas:5666",
            firstBatchSize = 24,
        ) { snapshot = it }

        assertEquals(24, snapshot.size)
        assertEquals(0L, snapshot.first().id)
        assertEquals(23L, snapshot.last().id)
        assertEquals(50, entry!!.photos.size)
    }

    @Test
    fun `条目不足一批时不回调 完整结果照常返回`() {
        var called = false
        val entry = parseAlbumEntry(
            text = entryJson(5),
            albumId = 7L,
            baseUrl = "http://nas:5666",
            firstBatchSize = 24,
        ) { called = true }

        assertEquals(false, called)
        assertNotNull(entry)
        assertEquals(5, entry!!.photos.size)
    }

    @Test
    fun `默认读法与旧行为一致 不回调`() {
        val entry = parseAlbumEntry(entryJson(50), 7L, "http://nas:5666")
        assertNotNull(entry)
        assertEquals(50, entry!!.photos.size)
    }

    @Test
    fun `firstBatchSize为零或回调为空都不触发`() {
        var called = false
        parseAlbumEntry(entryJson(50), 7L, "http://nas:5666", 0) { called = true }
        assertEquals(false, called)

        // 只给批次不给回调，也不能抛异常。
        val entry = parseAlbumEntry(entryJson(50), 7L, "http://nas:5666", 24, null)
        assertNotNull(entry)
        assertEquals(50, entry!!.photos.size)
    }

    @Test
    fun `版本不认识或JSON坏掉一律返回null`() {
        assertNull(parseAlbumEntry(entryJson(10, version = 999), 7L, "http://nas:5666", 5) { })
        assertNull(parseAlbumEntry("{ 这不是 json", 7L, "http://nas:5666", 5) { })
    }

    @Test
    fun `相对路径按baseUrl还原 首批与全量口径一致`() {
        var firstUrls: List<String?> = emptyList()
        val entry = parseAlbumEntry(
            text = entryJson(30),
            albumId = 7L,
            baseUrl = "http://nas:5666",
            firstBatchSize = 6,
        ) { firstUrls = it.map { p -> p.thumbUrl } }

        assertEquals(6, firstUrls.size)
        assertTrue(firstUrls.all { it?.startsWith("http://nas:5666/t/") == true })
        assertEquals(entry!!.photos.take(6).map { it.thumbUrl }, firstUrls)
    }
}
