package com.kijeye.fnshare.ui

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/**
 * 真机纵向滚动优化：把「焦点跨行时整页平滑滚动」从系统默认的偏长弹簧曲线，换成固定时长的干脆减速曲线。
 *
 * 为什么是这里：横向移动不滚动所以顺滑；纵向跨行时 `LazyVerticalGrid` 通过 `BringIntoViewSpec`
 * 自动把焦点行滚进视口。这个接口的 [getScrollAnimationSpec] 就是那一段滚动动画的规格，
 * 把它换成固定时长的 tween，GPU 持续合成整页的时间可控，画面只是「干脆地挪一下」就停。
 *
 * 时长：300ms。比最初的 150ms 更从容，适合 3 米外电视观看；想更利落可调小、想更悠闲可调大
 * （见本文件顶部的说明）。焦点卡的放大/白描边动画完全不受影响——那是卡片自己的
 * `animateFloatAsState`，与滚动规格无关。
 *
 * `calculateScrollDistance` 沿用默认（按当前/目标/视口算每步距离），不动它。
 */
@OptIn(ExperimentalFoundationApi::class)
object TvBringIntoViewSpec : BringIntoViewSpec {
    override val scrollAnimationSpec: AnimationSpec<Float>
        get() = tween(300, easing = CubicBezierEasing(0.0f, 0.0f, 0.2f, 1.0f))
}

/** 在应用根处注入上面的滚动规格，让相册墙和照片网格都生效。opt-in 集中在这里，调用方无感。 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
fun TvBringIntoViewRoot(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalBringIntoViewSpec provides TvBringIntoViewSpec) {
        content()
    }
}
