package com.kijeye.fnshare.domain.usecase

import com.kijeye.fnshare.domain.model.SharedAlbum
import java.security.MessageDigest

/**
 * 相册缓存「内容有没有变」的判据，纯 Kotlin，不碰 Android、不碰 ViewModel，单测能直接钉死。
 *
 * 背景：列表接口给的 `photoCount` 不是内容版本号——NAS 刚同步完时它可能是旧值，
 * 一删一加时它可能不变。只靠它判「缓存过没过期」，就会出现「张数没变 → 不重建 →
 * 新增照片永远看不见 → 清缓存才看得到」。
 *
 * 于是再加一个信号：照片列表按拍摄时间倒序，取头 [SIG_SIZE] 张的 id 做指纹。
 * 只要头几张换了（最常见的「刚同步进来新照片」），指纹就会变。
 */
internal const val SIG_SIZE = 24

/** 冷启动 / 手动刷新两种检查力度。 */
enum class CheckMode {
    /** 冷启只看 photoCount——启动路径上不额外发探测请求，零行为变化。 */
    COLD,

    /** 手动刷新在 count 相同的情况下再比对指纹，抓出「张数没变但照片变了」。 */
    MANUAL,
}

/** 一本相册缓存时的快照：列表报的张数 + 建缓存那次的前 [SIG_SIZE] 张 id 指纹。 */
data class CacheSnapshot(
    val photoCount: Int,
    /** 老缓存没有这个字段，读出来是空串——视为「未知」，手动刷新探测一次即可自愈。 */
    val sig: String = "",
)

/**
 * 前 [SIG_SIZE] 张照片 id 的指纹：拼串后取 SHA-256 前 6 字节，12 位十六进制。
 *
 * 只在函数内部截断，调用方传整页 id 也安全——避免「写入时取 24 张、探测时取 30 张」
 * 这种口径差导致指纹抖动、每次刷新都误判变化。
 */
internal fun photoIdSig(ids: List<Long>): String {
    val joined = ids.take(SIG_SIZE).joinToString(",")
    val digest = MessageDigest.getInstance("SHA-256").digest(joined.toByteArray())
    return digest.take(6).joinToString("") { "%02x".format(it) }
}

/**
 * 这本相册要不要重建整本清单。
 *
 * [probeIds] 是探测拿到的第一页照片 id；count 不同时不需要它，探测失败时传 null
 * （此时不冒然重建，由调用方单独记失败并把这次刷新标成失败）。
 *
 * 已知边界：新照片如果排在头 [SIG_SIZE] 张之外（比如补了一张很早以前的照片），
 * 指纹不会变，手动刷新也发现不了。为了不让每次刷新都发 N 个探测请求，接受这个边界。
 */
internal fun needsRebuild(
    album: SharedAlbum,
    snapshot: CacheSnapshot?,
    probeIds: List<Long>?,
    mode: CheckMode,
): Boolean {
    if (snapshot == null) return true                       // 新相册，manifest 里还没有
    if (album.photoCount != snapshot.photoCount) return true // 张数不同：两种模式都要重建
    if (mode == CheckMode.COLD) return false                 // 冷启只看张数，探测结果一律不采信
    if (probeIds == null) return false                       // 探测失败，不冒进
    return photoIdSig(probeIds) != snapshot.sig              // 老缓存 sig 为空 → 重建一次完成迁移
}
