package com.kijeye.fnshare.domain.model

/**
 * 该代码是从哪个原文件提取的：
 * `ui/Timeline.kt:10-57`（`TimelineBucket` / `TimelineGroup` / `Timeline` 三个 data class，
 * 含 `Timeline.isEmpty` 的 getter）。**逐字搬移，未改动任何一行逻辑或默认值**。
 * 原 `ui/Timeline.kt` 同一位置改为三行 `internal typealias`；`buildTimeline()` 的
 * 函数体、返回类型写法、所有调用点一律不动，留到第 2 批抽成
 * `domain/usecase/BuildTimelineUseCase` 时再整体搬。
 *
 * 对应的生命周期清理应该在原文件的哪个位置调用：
 * 【无需清理】。三个类都是不可变数据类，不持有线程、定时器或 Context。
 * 后台建日期索引的协程 `AppViewModel.dateIndexJob` 不属于本文件，它的 `cancel()`
 * 仍在 `ui/AppViewModel.kt` 现有三处调用点，本批不触碰。
 */

/**
 * 时间轴的二级项。
 *
 * 普通相册是「一个月」，宝宝相册是「一个月龄」——两者都是 [major] 底下的一个可跳转段落，
 * 所以共用一套结构，只是 [minor] 的取值范围不同：普通相册 1..12，宝宝相册 0..11。
 */
internal data class TimelineBucket(
    /** 普通相册 = 年份；宝宝相册 = 岁数。 */
    val major: Int,
    /** 普通相册 = 月份 1..12；宝宝相册 = 月龄 0..11。 */
    val minor: Int,
    /** 该段第一张在 photos 里的下标，也就是网格要跳到的位置。 */
    val firstIndex: Int,
    val count: Int,
    /**
     * 该段第一张的**照片 id**。
     *
     * 下标是靠不住的：照片按拍摄时间倒序排，NAS 上新加一张会插到最前面，
     * 所有下标全体位移一位。缓存里的清单是建缓存那一刻的快照，更容易被这种变化打乱。
     * 所以跳转优先按 id 找位置，找不到（那张照片被删了）再退回 [firstIndex]。
     */
    val firstPhotoId: Long = 0L,
)

/** 时间轴的一级组：普通相册是一年，宝宝相册是一岁。 */
internal data class TimelineGroup(
    /** 普通相册 = 年份；宝宝相册 = 岁数。 */
    val major: Int,
    val buckets: List<TimelineBucket>,
    val count: Int,
)

/**
 * 由已加载的照片算出的时间轴。
 *
 * **没有照片的年月（月龄）不会出现在这里**，面板上也就不会出现空项——这正是「动态时间轴」
 * 的意思。一级 / 二级都按 major、minor 倒序排，也就是「最近的在上」：普通相册是 2026 在最上，
 * 宝宝相册是 3 岁在最上、0 岁沉到最下——年龄越大时间越近，方向和其他相册一致，不用反过来。
 *
 * [baby] 决定 UI 用哪套文案（`2026 / 10月` 还是 `3岁 / 10个月`）。
 *
 * [undatedFirstIndex] 是没有拍摄时间（`takenAt == null`）、或者宝宝相册里拍照日早于生日的
 * 那批照片里第一张的下标。它们分不出年月，不参与分组，也不进面板。
 */
internal data class Timeline(
    val groups: List<TimelineGroup>,
    val baby: Boolean = false,
    val undatedFirstIndex: Int? = null,
    val undatedCount: Int = 0,
) {
    val isEmpty: Boolean get() = groups.isEmpty() && undatedFirstIndex == null
}
