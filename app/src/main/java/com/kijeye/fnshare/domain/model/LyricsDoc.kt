package com.kijeye.fnshare.domain.model

/**
 * 该代码是从哪个原文件提取的：
 * `data/lyrics/LrcParser.kt:24-49`（`LrcLine` data class 与 `LyricsDoc` 含 `indexOf()`
 * 二分查找的完整实现）。**逐字搬移，未改动任何一行逻辑**。
 * 原 `LrcParser.kt` 同一位置改为两行 `typealias`，解析器 `LrcParser.parse()` 里
 * `LrcLine(...)` / `LyricsDoc(...)` 的构造调用、以及 19 个单元测试里
 * `com.kijeye.fnshare.data.lyrics.LyricsDoc` 的 import 全部保持原样。
 * 解析算法仍留在 `data/lyrics/LrcParser.kt`，第 3 批再整体移入 `data/lyrics`。
 *
 * 对应的生命周期清理应该在原文件的哪个位置调用：
 * 【无需清理】。`LyricsDoc` 是不可变数据类，`indexOf()` 是无状态二分查找，
 * 不持有线程、定时器或 Context。歌词**轮询定时器**不在这里，它仍在
 * `ui/AppViewModel.kt:973-1012`（100ms 循环），第 4 批抽成 `DefaultLyricsTicker` 后
 * 其 `cancel()` 必须挂在 `AppViewModel.onCleared():1198`。本文件不需要任何清理调用。
 */

/** 一句歌词：[timeMs] 是这句话开始显示的时刻。 */
data class LrcLine(val timeMs: Long, val text: String)

/** 一首歌的整份歌词。行已按时间排序。 */
data class LyricsDoc(val lines: List<LrcLine>) {

    /**
     * [timeMs] 时刻正在显示的行下标。
     * 约定：一行的显示区间是「本行开始 → 下一行开始」，最后一行显示到曲终。
     * 早于第一行（前奏）返回 -1，调用方据此整体隐藏。
     * 二分查找，100ms 轮询下每 tick 最多十几次比较。
     */
    fun indexOf(timeMs: Long): Int {
        if (lines.isEmpty() || timeMs < lines.first().timeMs) return -1
        var lo = 0
        var hi = lines.lastIndex
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (lines[mid].timeMs <= timeMs) lo = mid else hi = mid - 1
        }
        return lo
    }
}
