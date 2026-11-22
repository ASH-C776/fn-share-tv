package com.kijeye.fnshare.domain.usecase

/**
 * 照片网格「浮动焦点」的滚动距离计算。
 *
 * 纯 Kotlin，不碰 Compose，单测直接把算式钉死。
 *
 * 背景：默认的焦点滚动只在「目标格已经看不见」时才滚，于是焦点会一路滑到屏幕
 * 下沿才开始翻页。这里改成「把焦点格顶边始终拉回视口四成高度处」，人眼看到
 * 的就是焦点框钉在中间不动、照片从下往上涌。
 *
 * 三个阶段不用写分支，靠列表自身的滚动边界自然出现：
 * - 刚进相册：焦点在第 0 行，算法算出来是负数（想往下拉），但列表已经在顶部
 *   滚不动，于是焦点老实待在顶部；
 * - 中段：正数距离正常滚动，焦点钉在四成处；
 * - 到底：滚到 maxScroll 卡住，焦点只能继续往下走。
 *
 * @param offset 焦点格顶边相对视口顶部的像素距离，可为负（格子在视口上方）。
 * @param containerSize 视口（网格可视区）的高度，像素。
 * @return 需要滚动的距离。正数=内容上移（往下翻），负数=内容下移（往上翻），0=已到位。
 */
internal fun pinnedScrollDistance(offset: Float, containerSize: Float): Float =
    offset - containerSize * FOCUS_ANCHOR_RATIO

/**
 * 焦点钉住的位置：视口高度的四成。
 *
 * 四成而不是正中：上方留约四行、下方留六行，往下翻时能提前看到更多待看照片，
 * 符合电视上「往下翻相册」的阅读方向。
 */
internal const val FOCUS_ANCHOR_RATIO = 0.4f
