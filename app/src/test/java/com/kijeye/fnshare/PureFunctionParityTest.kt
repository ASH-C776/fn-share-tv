package com.kijeye.fnshare

import com.kijeye.fnshare.domain.model.GRID_COLUMNS
import com.kijeye.fnshare.domain.model.GridRow
import com.kijeye.fnshare.domain.model.SharedAlbum
import com.kijeye.fnshare.domain.model.SharedPhoto
import com.kijeye.fnshare.domain.model.Timeline
import com.kijeye.fnshare.domain.model.TimelineBucket
import com.kijeye.fnshare.domain.model.TimelineGroup
import com.kijeye.fnshare.domain.usecase.BuildGridRowsUseCase
import com.kijeye.fnshare.domain.usecase.BuildTimelineUseCase
import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.util.Calendar
import java.util.Locale

/**
 * 该代码是从哪个原文件提取的：
 * 下面 `LegacyTimeline` / `LegacyGridRows` 两个参照实现，是从重构前基线 `b3a9171` 的
 * `app/src/main/java/com/kijeye/fnshare/ui/Timeline.kt:22-90` 与
 * `ui/GridRows.kt:39-112` **逐字复制**过来的（只把类型改成 `domain.model` 的同一组类，
 * 把 `GridRowDay`/`GridRowPic` 别名写回 `GridRow.Day`/`GridRow.Pic`）。
 *
 * 目的：证明第 2 批「搬进 `domain/usecase` + 原文件留一行转发」没有改变任何输出。
 * 测试只比较两份实现的返回值是否 `==`，不修改产品代码、不改动画/焦点/按键逻辑。
 *
 * 对应的生命周期清理应该在原文件的哪个位置调用：
 * 【无需清理】。参照实现和被测实现都是纯函数，无状态、无协程、无定时器、无 Context。
 * `MainActivity.onDestroy()` / `AppViewModel.onCleared():1198` 不需要新增调用。
 */
class PureFunctionParityTest {

    // ---------- 测试数据 ----------

    private val plain = SharedAlbum(
        id = 1L, name = "家人", ownerName = "jie", photoCount = 0, posterUrl = null,
    )
    private val baby = SharedAlbum(
        id = 2L, name = "宝宝", ownerName = "jie", photoCount = 0, posterUrl = null,
        category = 1, birthday = "2023-01-15",
    )

    private fun photo(id: Long, date: String? = null) = SharedPhoto(
        id = id,
        thumbUrl = "t/$id",
        fullUrl = "f/$id",
        isVideo = false,
        videoUrl = null,
        takenAt = date?.let { LocalDateTime.parse(it) },
    )

    private val cases = listOf(
        "empty" to emptyList(),
        "no date at all" to listOf(photo(1), photo(2), photo(3)),
        "plain multi month" to listOf(
            photo(1, "2026-10-28T18:25:41"),
            photo(2, "2026-10-02T08:00:00"),
            photo(3, "2026-09-30T23:59:59"),
            photo(4, "2025-12-31T10:00:00"),
        ),
        "out of order" to listOf(
            photo(1, "2025-01-05T10:00:00"),
            photo(2, "2026-10-28T18:25:41"),
            photo(3, "2025-01-20T10:00:00"),
            photo(4, "2026-10-01T10:00:00"),
        ),
        "mixed dated / undated" to listOf(
            photo(1), photo(2, "2026-10-28T18:25:41"), photo(3),
            photo(4, "2026-10-27T18:25:41"), photo(5),
        ),
        "baby month buckets" to listOf(
            photo(1, "2026-10-28T18:25:41"),   // 3岁9个月
            photo(2, "2026-10-02T08:00:00"),   // 3岁8个月
            photo(3, "2026-09-10T08:00:00"),   // 3岁7个月
            photo(4, "2025-12-20T08:00:00"),   // 2岁11个月
            photo(5, "2022-01-01T08:00:00"),   // 早于生日 → 无日期
            photo(6),
        ),
        "row boundary 7 in one day" to (1L..7L).map { photo(it, "2026-10-28T18:25:41") },
    )

    private val albums = listOf(null, plain, baby)

    // ---------- 等价性断言 ----------

    @Test
    fun `buildTimeline output is byte-for-byte equal to pre-refactor implementation`() {
        for ((name, photos) in cases) {
            for (album in albums) {
                assertEquals(
                    "timeline mismatch: case=$name album=${album?.name}",
                    LegacyTimeline(photos, album),
                    BuildTimelineUseCase(photos, album),
                )
            }
        }
    }

    @Test
    fun `buildGridRows output is byte-for-byte equal to pre-refactor implementation`() {
        for ((name, photos) in cases) {
            for (album in listOf(plain, baby)) {
                assertEquals(
                    "gridRows mismatch: case=$name album=${album.name}",
                    LegacyGridRows(photos, album, "今天", "昨天", "%d岁%d个月%d天"),
                    BuildGridRowsUseCase(photos, album, "今天", "昨天", "%d岁%d个月%d天"),
                )
            }
        }
    }

    /** 顺带钉住列数与行尾标记：焦点溢出判定依赖它，不能只靠两份实现互相背书。 */
    @Test
    fun `row end flag keeps its shape`() {
        val rows = BuildGridRowsUseCase(
            (1L..7L).map { photo(it, "2026-10-28T18:25:41") },
            plain, "今天", "昨天", "%d岁%d个月%d天",
        )
        assertEquals(GRID_COLUMNS, 6)
        val pics = rows.filterIsInstance<GridRow.Pic>()
        assertEquals(7, pics.size)
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 0), pics.map { it.column })
        assertEquals(listOf(false, false, false, false, false, true, true), pics.map { it.isRowEnd })
    }
}

// =====================================================================================
// 以下是重构前基线 b3a9171 的原实现，仅作为测试参照，永远不会被产品代码引用。
// =====================================================================================

private object LegacyTimeline {
    operator fun invoke(photos: List<SharedPhoto>, album: SharedAlbum?): Timeline {
        if (photos.isEmpty()) return Timeline(emptyList())

        val babyAlbum = album?.takeIf { it.isBabyAlbum }

        val buckets = LinkedHashMap<Int, TimelineBucket>(64)
        var undatedFirst: Int? = null
        var undatedCount = 0

        fun markUndated(index: Int) {
            if (undatedFirst == null) undatedFirst = index
            undatedCount++
        }

        photos.forEachIndexed { index, photo ->
            val t = photo.takenAt
            if (t == null) {
                markUndated(index)
                return@forEachIndexed
            }

            var major = t.year
            var minor = t.monthValue
            if (babyAlbum != null) {
                val age = babyAlbum.ageMonthsOn(t.toLocalDate())
                if (age == null) {
                    markUndated(index)
                    return@forEachIndexed
                }
                major = age.first
                minor = age.second
            }

            val key = major * 100 + minor
            val prev = buckets[key]
            buckets[key] = if (prev == null) {
                TimelineBucket(major, minor, index, 1, photo.id)
            } else {
                prev.copy(firstIndex = minOf(prev.firstIndex, index), count = prev.count + 1)
            }
        }

        val ordered = buckets.values.sortedWith(
            compareByDescending<TimelineBucket> { it.major }.thenByDescending { it.minor },
        )

        val groups = ArrayList<TimelineGroup>(ordered.size / 10 + 1)
        ordered.forEach { bucket ->
            val last = groups.lastOrNull()
            if (last != null && last.major == bucket.major) {
                groups[groups.lastIndex] = last.copy(
                    buckets = last.buckets + bucket,
                    count = last.count + bucket.count,
                )
            } else {
                groups += TimelineGroup(bucket.major, listOf(bucket), bucket.count)
            }
        }

        return Timeline(
            groups = groups,
            baby = babyAlbum != null,
            undatedFirstIndex = undatedFirst,
            undatedCount = undatedCount,
        )
    }
}

private object LegacyGridRows {
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
                column = 0
            }
            rows += GridRow.Pic(photo = photo, photoIndex = index, column = column)
            column = (column + 1) % GRID_COLUMNS
        }
        for (i in rows.indices) {
            val row = rows[i] as? GridRow.Pic ?: continue
            val rowEnd = row.column == GRID_COLUMNS - 1 || rows.getOrNull(i + 1) !is GridRow.Pic
            if (rowEnd != row.isRowEnd) rows[i] = row.copy(isRowEnd = rowEnd)
        }
        return rows
    }

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
