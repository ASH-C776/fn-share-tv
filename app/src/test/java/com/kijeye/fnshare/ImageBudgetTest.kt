package com.kijeye.fnshare

import com.kijeye.fnshare.core.ImageBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图片缓存上限的纯函数回归（0.23.0 建立，0.23.1 把内存系数从 5% 调到 1/12）。
 *
 * 这两个数以前是写死的常量（内存 64MB / 磁盘 3GB），发布到不同内存、不同
 * 存储容量的电视上必然一头过大一头过小。这里把「下限夹死」「上限夹死」
 * 「可用空间不足时自动缩」三种情况离线钉住，换设备不用实机验证也能确认算式。
 */
class ImageBudgetTest {

    private val mb = 1024L * 1024
    private val gb = 1024L * 1024 * 1024

    // ── 内存池：总 RAM 的 5%，夹在 [96MB, 224MB] ──────────────────────────

    @Test
    fun `1_5GB 盒子按十二分之一得 128MB`() {
        // 1.5GiB / 12 = 128MiB，还在区间内，不会被抬到下限。
        assertEquals(128 * mb, ImageBudget.memoryCacheBytes((1.5 * gb).toLong()))
    }

    @Test
    fun `512MB 的老盒子仍然是 96MB 下限`() {
        // 512MiB / 12 = 42.7MiB，连一张 4K 原图都放不下，必须抬到下限。
        assertEquals(96 * mb, ImageBudget.memoryCacheBytes(512 * mb))
    }

    @Test
    fun `2GB 落在区间内按十二分之一算`() {
        // 2GiB / 12 = 170.7MiB
        assertEquals((2 * gb) / 12, ImageBudget.memoryCacheBytes(2 * gb))
    }

    @Test
    fun `创维 A7E Pro 实测 3_83GB 得到约 305MB`() {
        // /proc/meminfo 的 MemTotal 3742808 kB = 3,832,635,392 B，除以 12 得 319,386,282
        // （304.6MiB），够放 6 张 50MB 的 4K 原图。
        val total = 3_832_635_392L
        val got = ImageBudget.memoryCacheBytes(total)
        assertEquals(total / 12, got)
        // 兜底断言：这个值必须还在区间内，否则说明夹取逻辑被改坏了。
        assertTrue(got in ImageBudget.MEMORY_MIN_BYTES..ImageBudget.MEMORY_MAX_BYTES)
    }

    @Test
    fun `8GB 旗舰电视封顶 384MB`() {
        // 8GiB / 12 = 682MiB，超过上限，夹到 384MB。
        assertEquals(384 * mb, ImageBudget.memoryCacheBytes(8 * gb))
    }

    @Test
    fun `取不到总内存时退回下限`() {
        assertEquals(96 * mb, ImageBudget.memoryCacheBytes(0L))
        assertEquals(96 * mb, ImageBudget.memoryCacheBytes(-1L))
    }

    // ── 磁盘池：不超过 10GB，也不超过可用空间 1/3 ─────────────────────────

    @Test
    fun `电视剩 38GB 时取 10GB 上限`() {
        assertEquals(10 * gb, ImageBudget.diskCacheBytes(38 * gb))
    }

    @Test
    fun `空间再大也不超过 10GB`() {
        assertEquals(10 * gb, ImageBudget.diskCacheBytes(60 * gb))
    }

    @Test
    fun `可用空间小的时候按三分之一收缩`() {
        assertEquals(2 * gb, ImageBudget.diskCacheBytes(6 * gb))
    }

    @Test
    fun `刚过 10GB 三分之一的区间要跟着涨`() {
        // 36GB / 3 = 12GB > 10GB，说明 10GB 是硬顶；30GB / 3 = 10GB 正好触顶。
        assertEquals(10 * gb, ImageBudget.diskCacheBytes(30 * gb))
        assertEquals(10 * gb - 1, ImageBudget.diskCacheBytes(30 * gb - 3))
    }

    @Test
    fun `读不到可用空间时退回 1GB 兜底`() {
        assertEquals(1 * gb, ImageBudget.diskCacheBytes(0L))
        assertEquals(1 * gb, ImageBudget.diskCacheBytes(-1L))
    }
}
