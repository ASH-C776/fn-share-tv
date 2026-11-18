package com.kijeye.fnshare.data.cache

import com.kijeye.fnshare.data.fn.SharedPhoto
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 清单缓存的条目格式（0.27.0 起带宽高 / 时长 / 字节数）。
 *
 * 为什么要钉住：这几个字段错了不会崩，只表现为信息栏悄悄少一行或者显示 0，
 * 上设备也未必看得出来。version 那条同样重要——格式改了不升版本，旧缓存里的
 * 新字段会一直是 0，而且没有任何报错。
 */
class AlbumCacheEntryTest {

    private val base = "https://nas.local:5666"
    private val albumId = 7L

    private fun albumJson(items: JSONArray, version: Int = CACHE_VERSION): String = JSONObject().apply {
        put("v", version)
        put("id", albumId)
        put("name", "梦里水乡")
        put("count", items.length())
        put("items", items)
    }.toString()

    private fun roundTrip(photo: SharedPhoto): SharedPhoto {
        val json = albumJson(JSONArray().put(photoToJson(photo, base)))
        val entry = parseAlbumEntry(json, albumId, base)
        assertNotNull("缓存应当能解析", entry)
        return entry!!.photos.single()
    }

    @Test
    fun `宽高时长字节数 写进去读回来一致`() {
        val photo = SharedPhoto(
            id = 42,
            thumbUrl = "$base/p/a.jpg",
            fullUrl = "$base/p/a.jpg",
            originalUrl = null,
            isVideo = true,
            videoUrl = "$base/p/a.mp4",
            width = 1080,
            height = 1920,
            durationSeconds = 83,
            byteSize = 49_222_894L,
        )

        val back = roundTrip(photo)

        assertEquals(1080, back.width)
        assertEquals(1920, back.height)
        assertEquals(83, back.durationSeconds)
        assertEquals(49_222_894L, back.byteSize)
        assertTrue(back.isVideo)
        assertEquals("$base/p/a.mp4", back.videoUrl)
    }

    @Test
    fun `未知的技术字段不写进 JSON`() {
        val photo = SharedPhoto(
            id = 1,
            thumbUrl = null,
            fullUrl = null,
            isVideo = false,
            videoUrl = null,
        )

        val item = photoToJson(photo, base)

        assertFalse("宽为 0 不该写入", item.has("w"))
        assertFalse("高为 0 不该写入", item.has("h"))
        assertFalse("时长为 0 不该写入", item.has("dur"))
        assertFalse("字节数为 0 不该写入", item.has("bytes"))
    }

    @Test
    fun `缺少技术字段的老条目读回来是 0 而不是崩`() {
        val item = JSONObject().apply {
            put("pid", 9)
            put("video", false)
            put("urls", JSONArray().put("/p/a.jpg"))
        }
        val entry = parseAlbumEntry(albumJson(JSONArray().put(item)), albumId, base)

        val photo = entry!!.photos.single()
        assertEquals(0, photo.width)
        assertEquals(0, photo.height)
        assertEquals(0, photo.durationSeconds)
        assertEquals(0L, photo.byteSize)
    }

    @Test
    fun `老版本缓存整份作废 让相册重新索引`() {
        val item = JSONObject().apply {
            put("pid", 9)
            put("video", false)
        }
        val json = albumJson(JSONArray().put(item), version = CACHE_VERSION - 1)

        assertNull("版本对不上就必须当没有缓存", parseAlbumEntry(json, albumId, base))
    }
}
