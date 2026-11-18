package com.kijeye.fnshare.core

/**
 * 图片缓存的两个上限：都按设备实际能力算，不写死。
 *
 * ## 为什么要改
 *
 * 0.22.5 之前是两个常量：内存 64MB、磁盘 3GB。在测试机上够用，但这是要发布给
 * 各种电视的——1.5GB 内存的低端盒子和 8GB 的旗舰电视不该用同一个数。
 *
 * 实测参考（创维 A7E Pro，Android 13）：总 RAM 3.83GB、单进程 Java 堆上限
 * 192MB、可用存储 38GB。4K 原图解码后一张约 50MB（4096×3072×4 字节），
 * 所以「能来回倒几张」直接由内存池大小决定。
 *
 * ## 内存池为什么取 8%
 *
 * Android 8 之后位图像素分配在 native heap，不受 Java 堆那 192MB 的硬限制，
 * 但仍计入整机 PSS——给太多会被 lmkd 在后台杀掉。这里取总 RAM 的 1/12（约 8.3%），
 * 比 Glide 的 1/8 略保守、比 Coil 默认的 25% 克制得多，两端再夹死：
 *  - 下限 96MB：再小就一张 4K 原图都放不下（50MB），预取等于白做；
 *  - 上限 384MB：7~8 张 4K 原图。电视通常只开一个前台应用、很少需要后台驻留，
 *    所以可以比手机激进一档；再往上就纯粹是占着内存不办事。
 *
 * 取值依据（0.23.1 调过一轮）：4K 原图解码后约 50MB/张，183MB 只够 3.6 张，
 * 遇到 6000×4000 的大图（单张 96MB）只放得下 1.9 张，左右翻页会互相挤掉预取。
 * 测试机 3.83GB RAM 现在得 305MB ≈ 6 张，覆盖「当前 ±1」还留了余量。
 *
 * ## 磁盘池为什么要 clamp 到可用空间的 1/3
 *
 * 上限 10GB 是给「全库缩略图 130MB + 全库 1080p 预览 2~4GB」留足余量。
 * 但模拟器和低端盒子的 /data 可能只有几 GB，硬写 10GB 会让 Coil 把系统
 * 挤爆。取 1/3 留出余量给相册索引、其他应用和系统。
 *
 * ⚠️ 原图**不进磁盘池**（请求带 `CachePolicy.DISABLED`），所以这 10GB 里
 * 实际只会装缩略图与 mUrl 预览，稳态占用 2~4GB。
 */
object ImageBudget {

    /** 内存池下限：一张 4K 原图约 50MB，至少得放得下当前 + 下一张。 */
    const val MEMORY_MIN_BYTES = 96L * 1024 * 1024

    /** 内存池上限：7~8 张 4K 原图，再多对翻页体验没有增益。 */
    const val MEMORY_MAX_BYTES = 384L * 1024 * 1024

    /** 内存池 = 总 RAM ÷ 这个数（12 ≈ 8.3%）。 */
    const val MEMORY_DIVISOR = 12

    /** 磁盘池上限。全库预览 2~4GB，留一倍余量给照片变多。 */
    const val DISK_MAX_BYTES = 10L * 1024 * 1024 * 1024

    /** 可用空间未知时的磁盘兜底（正常情况走不到）。 */
    const val DISK_FALLBACK_BYTES = 1L * 1024 * 1024 * 1024

    /**
     * 内存缓存上限：总 RAM 的 1/12，夹在 [MEMORY_MIN_BYTES] 与 [MEMORY_MAX_BYTES] 之间。
     *
     * 用整数除法而不是浮点系数，避免 0.083 这类小数的二进制误差让边界值抖动。
     *
     * @param totalRamBytes `ActivityManager.MemoryInfo.totalMem`，即整机物理内存。
     *   传 0 或负数（取不到）时按下限处理。
     */
    fun memoryCacheBytes(totalRamBytes: Long): Long {
        if (totalRamBytes <= 0L) return MEMORY_MIN_BYTES
        return (totalRamBytes / MEMORY_DIVISOR).coerceIn(MEMORY_MIN_BYTES, MEMORY_MAX_BYTES)
    }

    /**
     * 磁盘缓存上限：不超过 10GB，也不超过可用空间的 1/3。
     *
     * @param usableSpaceBytes `File(cacheDir).usableSpace`（非 totalSpace——后者
     *   包含已用部分，算出来的 1/3 会在磁盘快满时仍然偏大）。
     */
    fun diskCacheBytes(usableSpaceBytes: Long): Long {
        if (usableSpaceBytes <= 0L) return DISK_FALLBACK_BYTES
        return minOf(DISK_MAX_BYTES, usableSpaceBytes / 3)
    }
}
