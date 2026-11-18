package com.kijeye.fnshare.ui

import java.util.Locale

/**
 * 信息栏里那行「技术信息」：视频给时长 + 像素，照片只给像素。
 *
 * 例：视频 `01:23 · 1080 × 1920`，照片 `4096 × 3072`。
 * 两项都拿不到（老缓存 / 服务端没给）就返回 null，整行不出现——宁可少一行，
 * 也不要在电视上挂一行 `0:00 · 0 × 0`。
 */
internal fun mediaFactsText(width: Int, height: Int, durationSeconds: Int): String? =
    listOfNotNull(
        formatMediaDuration(durationSeconds),
        formatPixels(width, height),
    ).joinToString(" · ").ifEmpty { null }

/**
 * 秒 → `mm:ss`（满一小时变 `h:mm:ss`）。0 或负数＝不知道，返回 null。
 *
 * 和播放器进度条的时间格式保持一致，人不用在两处换算。
 */
internal fun formatMediaDuration(seconds: Int): String? {
    if (seconds <= 0) return null
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) {
        String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    } else {
        String.format(Locale.US, "%02d:%02d", m, s)
    }
}

/** 像素尺寸 `1920 × 1080`。任一为 0＝不知道，返回 null。 */
internal fun formatPixels(width: Int, height: Int): String? =
    if (width > 0 && height > 0) "$width × $height" else null
