package com.kijeye.fnshare.domain.usecase

import com.kijeye.fnshare.domain.model.GRID_COLUMNS
import com.kijeye.fnshare.domain.model.GridRow
import com.kijeye.fnshare.domain.model.SharedAlbum
import com.kijeye.fnshare.domain.model.SharedPhoto
import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.util.Calendar
import java.util.Locale

/**
 * 该代码是从哪个原文件提取的：
 * `ui/GridRows.kt:39-112` 的 `internal fun buildGridRows()` 及其两个私有辅助函数
 * `dayLabelOf()` / `sameDay()`，**函数体逐字搬移**（列号计算、`isRowEnd` 回写、
 * `SimpleDateFormat` 的中英文 pattern、`Calendar` 的今天/昨天判断全部未改）。
 * 唯一变化：第 1 批为绕开「typealias 不能访问嵌套类」而在 ui 包加的
 * `GridRowDay` / `GridRowPic` 两个别名，在这里写回 `GridRow.Day` / `GridRow.Pic`
 * ——编译后是**同一组 data class**，不是新类型。
 * `@Composable DayHeaderCell()` 留在 `ui/GridRows.kt`（它是渲染，不是计算）。
 * 原 `ui/GridRows.kt` 保留同名同签名的转发函数，所有调用点零改动。
 *
 * 对应的生命周期清理应该在原文件的哪个位置调用：
 * 【无需清理】。纯计算，无状态、无协程、无定时器、无缓存、无 Context。
 * `SimpleDateFormat` 每次调用新建局部实例并随函数返回被回收，不持有资源。
 * 原 `buildGridRows()` 在 `ui/GridScreen.kt` 的 `remember(...)` 里同步调用，
 * `MainActivity.onDestroy()` / `AppViewModel.onCleared():1198` 不需要新增调用。
 *
 * 线程约定：纯 CPU 计算，由调用方决定线程。当前在 Compose 组合期（主线程）同步执行，
 * 本重构**不改变**这一事实；不要顺手挪到 `Dispatchers.IO`，那会破坏 `remember` 的
 * 同步语义并让网格首帧空白。
 */
internal object BuildGridRowsUseCase {

    /**
     * 把照片按拍摄日切成连续的段，每段前面加一个日期头。
     *
     * 只在「天变了」的地方构造标签，而不是每张照片都格式化一次：SimpleDateFormat 每次
     * 都会新建格式化器并读本地化数据，几千张的相册会在这上面白白卡住滚动。
     *
     * 宝宝相册的标签是「X岁X月X天」——用 [SharedAlbum.ageOn] 算拍摄当天宝宝的年龄；
     * 出生日期缺失或早于出生日期（异常数据）时退回普通日期标签。
     */
    operator fun invoke(
        photos: List<SharedPhoto>,
        album: SharedAlbum,
        todayLabel: String,
        yesterdayLabel: String,
        ageFormat: String,
    ): List<GridRow> {
        val today = Calendar.getInstance()
        val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        val rows = ArrayList<GridRow>(photos.size + photos.size / 20 + 1)
        var lastKey: String? = null
        var column = 0
        photos.forEachIndexed { index, photo ->
            val t = photo.takenAt
            val key = if (t == null) {
                "unknown"
            } else {
                "%04d-%02d-%02d".format(t.year, t.monthValue, t.dayOfMonth)
            }
            if (key != lastKey) {
                val label = when {
                    t == null -> ""
                    album.isBabyAlbum -> album.ageOn(key)?.let { age ->
                        String.format(Locale.getDefault(), ageFormat, age.first, age.second, age.third)
                    } ?: dayLabelOf(t, today, yesterday, todayLabel, yesterdayLabel)
                    else -> dayLabelOf(t, today, yesterday, todayLabel, yesterdayLabel)
                }
                rows += GridRow.Day(dayKey = key, label = label)
                lastKey = key
                // 日期头占满整行，它后面那张从第 0 列重新开始。
                column = 0
            }
            rows += GridRow.Pic(photo = photo, photoIndex = index, column = column)
            column = (column + 1) % GRID_COLUMNS
        }
        // 行尾标记：满 6 列，或者下一条已经不是照片（日期头 / 到底）。
        for (i in rows.indices) {
            val row = rows[i] as? GridRow.Pic ?: continue
            val rowEnd = row.column == GRID_COLUMNS - 1 || rows.getOrNull(i + 1) !is GridRow.Pic
            if (rowEnd != row.isRowEnd) rows[i] = row.copy(isRowEnd = rowEnd)
        }
        return rows
    }

    /** 一天的标签：今天 / 昨天 / 完整日期（年 月 日 + 星期）。每个「天」只调用一次。 */
    private fun dayLabelOf(
        t: LocalDateTime,
        today: Calendar,
        yesterday: Calendar,
        todayLabel: String,
        yesterdayLabel: String,
    ): String {
        val cal = Calendar.getInstance().apply {
            clear()
            set(t.year, t.monthValue - 1, t.dayOfMonth)
        }
        return when {
            sameDay(cal, today) -> todayLabel
            sameDay(cal, yesterday) -> yesterdayLabel
            else -> {
                val locale = Locale.getDefault()
                val pattern = if (locale.language.startsWith("zh")) {
                    "yyyy年M月d日 EEEE"
                } else {
                    "EEEE, MMM d, yyyy"
                }
                SimpleDateFormat(pattern, locale).format(cal.time)
            }
        }
    }

    private fun sameDay(a: Calendar, b: Calendar): Boolean =
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
            a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
}
