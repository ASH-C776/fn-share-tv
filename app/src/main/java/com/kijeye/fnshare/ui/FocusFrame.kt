package com.kijeye.fnshare.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.kijeye.fnshare.engine.api.ImageRequestFactory

/**
 * 卡片获得焦点时外框整体放大的倍数。只动绘制矩阵，布局尺寸一点没变。
 *
 * 保持 1.05：外框只是「这张卡被选中了」的轻微托起，真正的视觉冲击交给里面的图（[INNER_SCALE]）。
 * 外框放大太多会让整格面积变大，6 列时会挤到邻居的呼吸区。
 */
private const val OUTER_SCALE = 1.05f

/**
 * 内图放大的倍数。比外框多两档，图片才会在画框里「顶出来」。
 *
 * 276px 的格子乘 1.15 约 317px，仍在卡片请求 384px 的解码上限（工厂 `CARD_PX`）内，不会因为放大而糊。
 */
private const val INNER_SCALE = 1.15f

/**
 * 外框的缩放时长（进、出同一套），也是描边淡入的时长。
 *
 * 220ms 刚好是「手已经移到下一格、上一格收完」的长度；内外图都靠它对齐节奏。
 */
private const val OUTER_ANIM_MS = 220

/**
 * 内图**放大**的时长，比外框长 280ms。
 *
 * 焦点落下的那一帧内外同时起跑，220ms 时外框已经停稳、图还在长最后一截——
 * 「框先立住、图再缓缓顶满」，读起来是画框里换画，而不是整张卡在缩放。
 */
private const val INNER_IN_MS = 500

/**
 * 内图**缩回**的时长。
 *
 * 故意比 [INNER_IN_MS] 短：离场要快，不然连按方向键时上一格的图还在慢吞吞往回收，
 * 跟在后面的新焦点打架。进 320 / 出 220 的不对称就是「进来慢、出去快」。
 */
private const val INNER_OUT_MS = 220

/**
 * 减速曲线：起步快、收尾慢。
 *
 * 这里不用 spring：遥控器按住方向键时焦点会连续搬家，弹簧每次都被重新拽起来，
 * 会看见卡片来回「追」；这种小位移还容易过冲成抖一下。
 */
private val FOCUS_EASING = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/**
 * 焦点描边的粗细。
 *
 * 描边是**居中**画在卡片边缘的圆角矩形上，外半圈会被外层 `.clip` 切掉，所以真正露在画面里的
 * 只有一半宽度。3dp 时实际只露出约 1.5dp，在 1920 宽、3 米外看电视几乎看不见——所以加粗到
 * 5dp（露出约 2.5dp / 近 4px）。还想更厚就往 6~7dp 加，但别超过卡片间距的一半，否则相邻两格的
 * 焦点框会糊到一起。
 */
private val RING_WIDTH = 5.dp

/**
 * 卡片图的请求。尺寸写死在工厂里（`CARD_PX = 384`），不跟着卡片实际大小算——
 * 同一张图在网格和相册墙里可能落在不同的约束下，写死才对得上内存缓存的 key，
 * 来回切屏不用重新解码。
 *
 * 构造表达式在 `engine/impl/CoilImageRequestFactory.cardRequest`（第 4 批逐字提取、
 * 第 7 批接线）：384px + crossfade(200)，内存缓存命中时 Coil 不播淡入，这是对的。
 */
@Composable
fun rememberCardImageRequest(url: String?, factory: ImageRequestFactory): ImageRequest? {
    return remember(url, factory) { factory.cardRequest(url) }
}

/**
 * 一张「焦点画框」卡片。
 *
 * 两层，从外到内：
 *   1. 外框放大 1.05 —— [graphicsLayer] 只改绘制矩阵，不触发 remeasure，
 *      也不会把邻居挤开。卡片间距（16dp）远大于放大溢出（约 7px），缩放出来的边只落在空隙里，
 *      不会被后绘制的那张盖掉，所以不需要 [androidx.compose.ui.zIndex]（旧的动态 zIndex 会在每次
 *      获焦时触发一次重排，是纵向滚动卡顿的元凶之一，已移除）。进、出都是 220ms。
 *   2. 内图放大 1.15 —— 单独一层，被外层圆角 clip 切掉溢出的部分，
 *      所以是「图在框里放大」，不是卡片长胖了压到旁边。设置里可以关掉这一层（[innerZoom]）。
 *      **进 320ms、出 220ms**：进来慢慢顶满，出去干脆收回。
 *
 * 两层同一帧起跑、各按自己的时长收：外框 220ms 先停住，图还在长最后 100ms。
 *
 * 白描边只做**淡入**（220ms，跟外框同步），**退出时直接消失**——收的时候不需要仪式感。
 *
 * 只在卡片内侧描一圈白边，**外面不再加任何东西**：原先那圈外扩亮环在深色底上读起来
 * 是一道灰边，像卡片带了个模糊的边框，反而糊掉了「白框」这个唯一要表达的东西。
 *
 * [image] 会跟着焦点放大，[overlay] 不会——角标、日期、相册名属于「框」，
 * 跟着图一起放大会显得字在抖。
 */
@Composable
fun FocusFrame(
    focused: Boolean,
    modifier: Modifier = Modifier,
    corner: Dp = CardCorner,
    ringColor: Color = MaterialTheme.colorScheme.primary,
    /** 内图是否跟着焦点放大。关掉之后只剩外框 1.05 和描边，画面内容完全不裁。 */
    innerZoom: Boolean = true,
    /**
     * 整个网格是否正在滚动。
     *
     * 纵向跨行滚动时，离屏/非焦点卡没必要再跑放大动画——它们本来就停在 1.0，
     * 强行跑 `animateFloatAsState` 只会让每一帧都重组一堆卡片，和整页平移叠在一起就是卡顿。
     * 所以滚动中且不是焦点卡时直接给静态 1f，不创建动画对象、不每帧重组。
     * 焦点卡永远保持放大/描边动画（核心视觉要求），不受这个开关影响。
     */
    scrolling: Boolean = false,
    image: @Composable BoxScope.() -> Unit,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    // 只有「非滚动 或 是焦点卡」才允许动画。这条判断在组合期算一次、值稳定，
    // 滚动开始/停止时整页重组一次即可，滚动过程中不再逐帧变化。
    val animate = !scrolling || focused
    val outer = if (animate) {
        animateFloatAsState(
            targetValue = if (focused) OUTER_SCALE else 1f,
            animationSpec = tween(OUTER_ANIM_MS, easing = FOCUS_EASING),
            label = "focusOuter",
        ).value
    } else {
        1f
    }
    // 内图：进 320ms、出 220ms。时长按方向取——spec 改了但 target 没变不会重启动画，
    // 所以只有焦点真的翻面的那一帧才吃新时长。
    val inner = if (animate) {
        animateFloatAsState(
            targetValue = if (focused && innerZoom) INNER_SCALE else 1f,
            animationSpec = tween(
                if (focused) INNER_IN_MS else INNER_OUT_MS,
                easing = FOCUS_EASING,
            ),
            label = "focusInner",
        ).value
    } else {
        1f
    }
    // 描边只淡入、不淡出：进的时候跟外框一起 220ms 亮起来，退出那一帧直接归零。
    val ringIn = if (animate) {
        animateFloatAsState(
            targetValue = if (focused) 1f else 0f,
            animationSpec = tween(OUTER_ANIM_MS, easing = FOCUS_EASING),
            label = "focusRing",
        ).value
    } else {
        0f
    }

    val shape = RoundedCornerShape(corner)
    // 描边的粗细与圆角半径提前换算成像素：drawBehind 的回调在绘制期跑，里面不能碰 dp。
    val density = LocalDensity.current
    val ringWidthPx = with(density) { RING_WIDTH.toPx() }
    val cornerPx = with(density) { corner.toPx() }

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = outer
                scaleY = outer
            }
            // 圆角裁切：内图放大的部分就切在这一层，不会溢到邻居身上。
            .clip(shape),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = inner
                    scaleY = inner
                },
            content = image,
        )

        overlay()

        // 描边压在最上层：内图放大后会把原来贴在卡片边上的边框盖住。
        //
        // 透明度只在**绘制期**读（读进 drawBehind 的回调里）：焦点动画的 220~500ms 里
        // 每帧只是重描这一圈边，不再触发重组——以前 `if (ring > 0.01f)` 在组合期读动画值，
        // 每帧都把整张卡片（含 AsyncImage 和 overlay）重组一遍，网格上连续滑焦点时
        // 掉帧多半来自这里。
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    val ring = if (focused) ringIn else 0f
                    if (ring > 0.01f) {
                        drawRoundRect(
                            color = ringColor.copy(alpha = ring),
                            cornerRadius = CornerRadius(cornerPx),
                            style = Stroke(width = ringWidthPx),
                        )
                    }
                },
        )
    }
}

/** 卡片图未加载出来时的底色，跟着内图一起放大——纯色裁过还是纯色，看不出边界。 */
@Composable
internal fun CardPlaceholder(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant))
}

/** 卡片图的统一写法：占位底色 + Coil 请求（尺寸锁死在工厂的 384px）。 */
@Composable
internal fun CardImage(
    url: String?,
    factory: ImageRequestFactory,
    contentDescription: String? = null,
    onError: () -> Unit = {},
    onSuccess: () -> Unit = {},
) {
    Box(Modifier.fillMaxSize()) {
        CardPlaceholder()
        AsyncImage(
            model = rememberCardImageRequest(url, factory),
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            onError = { onError() },
            onSuccess = { onSuccess() },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
