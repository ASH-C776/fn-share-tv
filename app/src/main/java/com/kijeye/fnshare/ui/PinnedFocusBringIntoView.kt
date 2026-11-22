package com.kijeye.fnshare.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.kijeye.fnshare.domain.usecase.pinnedScrollDistance

/**
 * 照片网格的「浮动焦点」滚动规则：把获得焦点的格子顶边拉回视口四成高度处。
 *
 * 系统默认规则是「格子已经完整可见就返回 0」——焦点因此会一路滑到屏幕下沿才开始
 * 翻页。换成这里的实现后，每按一次上下键，画面滚动的距离正好等于一行，于是焦点框
 * 在屏幕上位置不变、照片从下往上涌；滚到列表两端滚不动时，焦点才自然继续走。
 *
 * 只覆写纵向主轴的 [BringIntoViewSpec.calculateScrollDistance]：
 * - 左右换格不会触发纵向滚动计算，所以横向导航完全不受影响；
 * - `scrollAnimationSpec` 在 foundation 1.11 已废弃且不再生效，不去碰它。
 *
 * 算式本身在 domain 的 `pinnedScrollDistance` 里，用单测钉死；这里只做 Compose 适配。
 */
private object PinnedFocusBringIntoViewSpec : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        // 视口还没量出来（首帧）时不要瞎算，交给下一帧。
        if (containerSize <= 0f) return 0f
        return pinnedScrollDistance(offset, containerSize)
    }
}

/**
 * 把 [PinnedFocusBringIntoViewSpec] 局部装进 [content] 的 Compose 子树。
 *
 * 用 `CompositionLocalProvider` 而不是全局设置：只有照片网格换了滚动规则，
 * 相册墙、设置页这些地方还是系统默认行为。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PinnedFocusScroll(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalBringIntoViewSpec provides PinnedFocusBringIntoViewSpec,
        content = content,
    )
}
