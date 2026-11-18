package com.kijeye.fnshare

import com.kijeye.fnshare.engine.impl.IMAGE_CACHE_DIR
import com.kijeye.fnshare.engine.impl.resolveImageCacheDir
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 图片缓存从 cacheDir 搬到 filesDir 的回归（0.24.4）。
 *
 * 重点不是「换个路径」，而是**攒了几 GB 的旧缓存不能作废**：升级后第一次启动必须
 * 整目录搬过去、journal 内容原样保留，否则用户会看到全库缩略图重新下载一遍。
 * 这四种情况离线钉住，不用在电视上等系统清缓存才能验证。
 */
class ImageCacheDirTest {

    private val root: File = Files.createTempDirectory("fn-image-cache").toFile()
    private lateinit var files: File
    private lateinit var cache: File

    @Before
    fun setUp() {
        files = File(root, "files").apply { mkdirs() }
        cache = File(root, "cache").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `旧缓存整目录搬到 filesDir 且 journal 不丢`() {
        val legacy = File(cache, IMAGE_CACHE_DIR).apply { mkdirs() }
        File(legacy, "journal").writeText("old-journal")

        val dir = resolveImageCacheDir(files, cache)

        assertEquals(File(files, IMAGE_CACHE_DIR), dir)
        assertTrue(dir.isDirectory)
        assertEquals("old-journal", File(dir, "journal").readText())
        assertFalse(legacy.exists())
    }

    @Test
    fun `新目录已存在就直接复用 不碰旧目录`() {
        val target = File(files, IMAGE_CACHE_DIR).apply { mkdirs() }
        File(target, "journal").writeText("new")
        val legacy = File(cache, IMAGE_CACHE_DIR).apply { mkdirs() }
        File(legacy, "journal").writeText("old")

        assertEquals(target, resolveImageCacheDir(files, cache))
        assertEquals("new", File(target, "journal").readText())
        assertEquals("old", File(legacy, "journal").readText())
    }

    @Test
    fun `没有旧缓存时直接返回 filesDir 下的新路径`() {
        val dir = resolveImageCacheDir(files, cache)

        assertEquals(File(files, IMAGE_CACHE_DIR), dir)
        assertFalse(File(cache, IMAGE_CACHE_DIR).exists())
    }

    @Test
    fun `旧路径是普通文件时不搬也不抛`() {
        val legacy = File(cache, IMAGE_CACHE_DIR).apply { writeText("not a dir") }

        val dir = resolveImageCacheDir(files, cache)

        assertEquals(File(files, IMAGE_CACHE_DIR), dir)
        assertTrue(legacy.isFile)
        assertFalse(dir.exists())
    }
}
