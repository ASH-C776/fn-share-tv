package com.kijeye.fnshare.domain.usecase

import com.kijeye.fnshare.domain.model.SharedAlbum
import com.kijeye.fnshare.domain.model.SharedPhoto
import com.kijeye.fnshare.domain.model.Timeline
import com.kijeye.fnshare.domain.model.TimelineBucket
import com.kijeye.fnshare.domain.model.TimelineGroup

/**
 * 该代码是从哪个原文件提取的：
 * `ui/Timeline.kt:22-90` 的 `internal fun buildTimeline()` **整个函数体逐字搬移**，
 * 包括局部函数 `markUndated`、`LinkedHashMap` 聚合、`compareByDescending` 排序、
 * 分组时的 `last.copy(...)`，以及宝宝相册走 `ageMonthsOn()` 的分支。
 * 唯一变化：三个数据类现在直接 import `domain.model`（第 1 批已搬过去），
 * 原文件里的 typealias 在编译期展开后指向的正是同一组类。
 * 原 `ui/Timeline.kt` 保留同名同签名的转发函数，所有调用点零改动。
 *
 * 对应的生命周期清理应该在原文件的哪个位置调用：
 * 【无需清理】。本对象是纯函数式计算，无状态、无协程、无定时器、无缓存。
 * 原 `buildTimeline()` 在 `ui/GridScreen.kt:123` 的 `remember(...)` 里同步调用，
 * 没有可泄漏的资源；`MainActivity.onDestroy()` / `AppViewModel.onCleared():1198`
 * 不需要为它新增任何调用。
 *
 * 线程约定：纯 CPU 计算，由调用方决定线程。当前调用点在 Compose 组合期（主线程），
 * 输入是已加载的 photos 列表前缀，规模有限；本重构**不改变**这个既有事实，
 * 也不把它挪到 `Dispatchers.IO`——那会改变 `remember` 的同步语义。
 */
internal object BuildTimelineUseCase {

    /**
     * 一遍扫描建表，O(n)。
     *
     * 照片是服务端按 `date_time desc` 给的，正常情况同一段的照片天然连续；这里仍然用
     * map 聚合再排序，是为了容忍服务端偶发的乱序——真乱序时也不会出现「2026 出现两次」。
     *
     * [album] 只有宝宝相册才影响结果：那时候一级换成「岁」、二级换成「月龄」。
     */
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
                    // 生日缺失 / 拍照日早于生日：这条分不出月龄，只能当无日期。
                    markUndated(index)
                    return@forEachIndexed
                }
                major = age.first
                minor = age.second
            }

            // 普通相册 key = 年*100+月；宝宝相册 key = 岁*100+月龄。两组都落在同一个 namespace，
            // 反正一次只会走一条分支，不会混。
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
