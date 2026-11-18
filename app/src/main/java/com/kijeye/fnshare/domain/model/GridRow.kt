package com.kijeye.fnshare.domain.model

/**
 * 该代码是从哪个原文件提取的：
 * `ui/GridRows.kt:22-56`（`GridRow` sealed interface 及其 `Day` / `Pic` 两个子类、
 * `GRID_COLUMNS` 常量）。**逐字搬移，未改动任何一行逻辑、`key` 计算或默认值**。
 * 原 `ui/GridRows.kt` 同一位置改为 typealias + 常量引用；`buildGridRows()` 与下面
 * 所有 Compose 渲染代码一律不动，留到第 2 批抽成 `domain/usecase/BuildGridRowsUseCase`。
 *
 * 对应的生命周期清理应该在原文件的哪个位置调用：
 * 【无需清理】。`GridRow` 是不可变数据类，`key` 是无状态 getter，`GRID_COLUMNS` 是
 * 编译期常量。不持有线程、定时器或 Context，原 `GridRows.kt` 也没有清理点。
 */

/**
 * 网格里的一行：要么是一天的日期头，要么是一张照片。
 *
 * 从 GridScreen 挪出来单独放，是因为「怎么分组」和「长什么样」是两件事——
 * 前者是一段纯逻辑，可以单独验证；后者全是 Compose。
 */
internal sealed interface GridRow {

    /** Compose 的稳定 key。日期头用 String，照片用 Long id，两者不会撞。 */
    val key: Any

    /** 一天的照片头。[label] 为空表示这张照片没有可解析的拍摄时间。 */
    data class Day(val dayKey: String, val label: String) : GridRow {
        override val key: Any get() = "day:$dayKey"
    }

    /**
     * 一张照片。[column] 是它在自己那一行里的列号（0..[GRID_COLUMNS]-1)。
     *
     * 必须在这里算：日期头是整行（`maxLineSpan`），日期头后面那张永远从第 0 列开始，
     * 所以「第几列」既不等于 `photoIndex % 6`，也不能从行里数出来。
     *
     * [isRowEnd] 是「这一行后面没有照片了」——要么排满 6 列，要么下一条就是日期头。
     * 时间轴只认这个：相册里大量「一天一张」的日子，那张照片并不在第 6 列，但它按右键
     * 本来也没处可去（焦点会飞到右上角的「播放」上去），这时候就该溢出。
     */
    data class Pic(
        val photo: SharedPhoto,
        val photoIndex: Int,
        val column: Int = 0,
        val isRowEnd: Boolean = false,
    ) : GridRow {
        override val key: Any get() = photo.id
    }
}

/** 网格列数。日期头分列、时间轴判断最右列都要用同一个数，别在两处各写一个字面量。 */
internal const val GRID_COLUMNS = 6
