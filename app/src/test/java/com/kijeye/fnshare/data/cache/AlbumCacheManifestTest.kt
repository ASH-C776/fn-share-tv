package com.kijeye.fnshare.data.cache

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** manifest 的可选 sig 字段：老格式兼容、增量合并不冲掉别的相册、空 sig 继承旧值。 */
class AlbumCacheManifestTest {

    private val host = "nas.local:5666"

    private fun manifest(version: Int = CACHE_VERSION, block: JSONObject.() -> Unit = {}): String =
        JSONObject().apply {
            put("v", version)
            put("host", host)
            put("albums", JSONObject())
            block()
        }.toString()

    private fun addAlbum(text: String, id: Long, count: Int, sig: String? = null): String {
        val obj = JSONObject(text)
        val albums = obj.getJSONObject("albums")
        val entry = JSONObject().apply {
            put("count", count)
            put("savedAt", 1000L)
            if (sig != null) put("sig", sig)
        }
        albums.put(id.toString(), entry)
        return obj.toString()
    }

    @Test
    fun `老格式没有sig 读成空串`() {
        val text = addAlbum(manifest(), 7L, 10)
        val info = parseManifest(text)[7L]!!
        assertEquals(10, info.photoCount)
        assertEquals("", info.sig)
        assertEquals(1000L, info.savedAt)
    }

    @Test
    fun `新格式sig读回来一致`() {
        val text = addAlbum(manifest(), 7L, 10, "abc123")
        assertEquals("abc123", parseManifest(text)[7L]!!.sig)
    }

    @Test
    fun `非法JSON返回空map`() {
        assertTrue(parseManifest("{坏JSON").isEmpty())
    }

    @Test
    fun `版本不匹配返回空map`() {
        assertTrue(parseManifest(manifest(version = CACHE_VERSION + 1)).isEmpty())
    }

    @Test
    fun `增量合并保留其他相册条目`() {
        val old = addAlbum(addAlbum(manifest(), 7L, 10), 8L, 20)
        val merged = mergeManifestEntry(old, host, albumId = 7L, photoCount = 11, savedAt = 2000L, sig = "newSig")
        val parsed = parseManifest(merged)
        assertEquals(2, parsed.size)
        assertEquals(11, parsed[7L]!!.photoCount)
        assertEquals("newSig", parsed[7L]!!.sig)
        assertEquals(20, parsed[8L]!!.photoCount) // 另一本没被动过
    }

    @Test
    fun `空sig合并时继承旧指纹`() {
        val old = addAlbum(manifest(), 7L, 10, "keepMe")
        val merged = mergeManifestEntry(old, host, albumId = 7L, photoCount = 10, savedAt = 3000L, sig = "")
        assertEquals("keepMe", parseManifest(merged)[7L]!!.sig)
    }

    @Test
    fun `写入sig再读回来一致`() {
        val merged = mergeManifestEntry(manifest(), host, albumId = 7L, photoCount = 10, savedAt = 2000L, sig = "fingerprint")
        val info = parseManifest(merged)[7L]!!
        assertEquals("fingerprint", info.sig)
        assertEquals(10, info.photoCount)
        assertEquals(2000L, info.savedAt)
    }
}
