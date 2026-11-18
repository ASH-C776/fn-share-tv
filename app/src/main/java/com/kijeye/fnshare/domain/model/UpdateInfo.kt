package com.kijeye.fnshare.domain.model

/**
 * 远端发现的一个可下载版本。
 *
 * 只保留弹窗与下载真正用得到的四个字段：
 * 版本号（比较用）、版本名（显示用）、APK 直链、来自哪个端（日志用）。
 * Release 正文不进模型——电视上长篇更新说明要翻页，得不偿失。
 */
data class UpdateInfo(
    /** 纯数字版本名，如 "0.31.0"（tag 去掉前导 v）。 */
    val versionName: String,
    /** 与 build.gradle 同算法：major*10000 + minor*100 + patch。 */
    val versionCode: Int,
    /** APK 直链。两端都发版时优先 Gitee 的。 */
    val downloadUrl: String,
    /** "gitee" / "github"，只用于日志前缀。 */
    val source: String,
)

/**
 * 解析版本名成可比较的整数，如 "0.31.0" → 3100。
 *
 * 规则与 app/build.gradle.kts 里的 appVersionCode 完全一致，否则「谁更新」会判反。
 * 只认 major.minor.patch 三段；带 v 前缀、带后缀（v0.31.0-beta）先剥掉非数字段。
 * 解析不出来返回 null —— 宁可忽略这个 Release，也不能拿坏版本号弹窗。
 */
fun parseVersionCode(raw: String): Int? {
    val trimmed = raw.trim().removePrefix("v").removePrefix("V")
    val core = trimmed.substringBefore('-').substringBefore('+')
    val parts = core.split('.')
    if (parts.isEmpty() || parts.size > 3) return null
    val nums = parts.map { it.toIntOrNull() ?: return null }
    if (nums.any { it < 0 || it > 9999 }) return null
    val major = nums[0]
    val minor = nums.getOrElse(1) { 0 }
    val patch = nums.getOrElse(2) { 0 }
    return major * 10000 + minor * 100 + patch
}

/**
 * 从两端的 Release 列表里挑出「最高且比当前新」的版本。
 *
 * 传入的两端列表各自已按发布时间排好（API 返回顺序），本函数不看时间、只看版本号：
 * - 遍历两个列表所有 Release，取版本号最大的那个（防止一端漏发）；
 * - 版本号相同则优先 Gitee（下载快、国内可达）；
 * - 只认带 .apk 附件的 Release，没附件的等于发了个空壳，跳过；
 * - 结果不比 [currentCode] 大就返回 null（没有更新）。
 *
 * 纯函数，接受已解析好的 [candidates]，网络与 JSON 都在外层，单测可离线覆盖。
 */
fun pickLatestUpdate(
    candidates: List<UpdateInfo>,
    currentCode: Int,
): UpdateInfo? {
    val fresh = candidates.filter { it.versionCode > currentCode }
    if (fresh.isEmpty()) return null
    // 先按版本号降序；同版本时 gitee 排前面（国内可达、下载快）。
    // maxWith 取「比较结果最大」的那个：版本号大的赢，同版本时 isGitee=true 的赢。
    return fresh.maxWith(
        compareBy<UpdateInfo> { it.versionCode }
            .thenBy { it.source == "gitee" },
    )
}
