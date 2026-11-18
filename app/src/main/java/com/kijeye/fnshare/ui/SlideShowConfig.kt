package com.kijeye.fnshare.ui

/**
 * 幻灯片里单张照片的运动参数。一个档位一套，跟着「时长」选项走。
 *
 * 三档的放大比例**随时长递增**（7% / 8% / 10%），不是递减。看着反直觉，但这是按
 * 「每秒放大百分之几」定的：3 秒里推 10% 就是 2.6%/s，画面会急；8 秒里推 5% 只有
 * 0.56%/s，3 米外基本是静止画面。所以短镜头小幅、长镜头大幅，角速度才落在
 * 1.1~1.8%/s 这个「看得出在动，但不分神」的区间里。
 *
 * [animDuration] 恒等于 `displayDuration + crossfadeDuration`，三档分别是 3.8 / 6.0 / 9.0。
 * 这不是巧合：Ken Burns 从画面出现一直跑到完全不可见，一帧都不浪费。
 */
data class SlideShowConfig(
    /** 独占屏幕的时长（ms）。到点开始叠化。 */
    val displayDuration: Int,
    /** 起始缩放。永远 ≥ 1，小于 1 会露出黑边。 */
    val scaleStart: Float,
    /** 结束缩放。 */
    val scaleEnd: Float,
    /** Ken Burns 总时长（ms）= displayDuration + crossfadeDuration。 */
    val animDuration: Int,
    /** 叠化时长（ms）。0 表示硬切。 */
    val crossfadeDuration: Int,
) {
    /** 第几毫秒开始叠化。独占期结束的那一刻。 */
    val fadeStartAt: Int get() = displayDuration

    /** 缩放比例有没有变化。没变化就不用跑动画，省一路图层。 */
    val kenBurns: Boolean get() = scaleEnd != scaleStart

    /**
     * 按「推近 / 拉远」取这一张的起止比例。
     *
     * 为什么要交替：每张都从 1.0 推到 1.07，硬切回下一张的 1.0 时画面会「缩一下」，
     * 连续看是一条规律的呼吸节奏。改成奇数张推近、偶数张拉远，两张的尺寸正好接得上——
     * 推近收在 1.07，下一张就从 1.07 开始往回走，视觉上像镜头连续运动，没有那种一顿一顿的胀缩。
     *
     * 拉远的最小值是 1.0，不会露出黑边。
     */
    fun scaleFor(pushIn: Boolean): Pair<Float, Float> =
        if (pushIn) scaleStart to scaleEnd else scaleEnd to scaleStart

    /**
     * 按用户在二级菜单里的两个开关，把这张表「剪」成实际要跑的样子。
     *
     * 关掉放大：`scaleEnd` 归到起点，[kenBurns] 变 false，界面那边直接不跑动画。
     * 关掉叠化：时长归零，同时 [animDuration] 也要跟着缩回 [displayDuration]——
     * 画面已经硬切走了，推镜还按原时长跑就是空转（而且下一张会从上一张的半路接手，
     * 节奏对不上）。两个开关互不影响，四种组合都要能自洽。
     */
    fun withSwitches(fade: Boolean, zoom: Boolean): SlideShowConfig {
        val cf = if (fade) crossfadeDuration else 0
        return copy(
            scaleEnd = if (zoom) scaleEnd else scaleStart,
            crossfadeDuration = cf,
            animDuration = displayDuration + cf,
        )
    }
}

/**
 * 秒数 → 参数。表里没有的值走 5 秒档（[com.kijeye.fnshare.data.FnSettings.SLIDE_STEPS]
 * 只有 3/5/8，老存档里可能残留 6/10/15 这类旧档位，读的时候已经被归到 5，这里再兜一次）。
 */
fun slideShowConfig(seconds: Int): SlideShowConfig = when (seconds) {
    3 -> SlideShowConfig(
        displayDuration = 3000,
        scaleStart = 1f,
        scaleEnd = 1.07f,
        animDuration = 3800,
        crossfadeDuration = 800,
    )
    8 -> SlideShowConfig(
        displayDuration = 8000,
        scaleStart = 1f,
        scaleEnd = 1.10f,
        animDuration = 9000,
        crossfadeDuration = 1000,
    )
    else -> SlideShowConfig(
        displayDuration = 5000,
        scaleStart = 1f,
        scaleEnd = 1.08f,
        animDuration = 6000,
        crossfadeDuration = 1000,
    )
}
