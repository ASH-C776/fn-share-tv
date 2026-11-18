package com.kijeye.fnshare.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import com.kijeye.fnshare.R
import com.kijeye.fnshare.ui.theme.BorderColor
import kotlinx.coroutines.delay

private val RailWidth = 132.dp
private val MonthWidth = 104.dp

/** 宝宝相册的二级项是「11个月」这种四字标签，比「12月」宽，面板得跟着放宽。 */
private val BabyMonthWidth = 122.dp
private val YearItemHeight = 52.dp
private val MonthItemHeight = 40.dp

/** 面板内边距：上下 10、左右 8。窗口高度要把它算进去。 */
private val RailPadV = 10.dp
private val RailPadH = 8.dp

/** 一级最多显示几行。再多的年份靠滚动，别让面板长到天上去。 */
private const val RailWindowRows = 10

/**
 * 光标钉在第几行（0-based）。5 = 从上往下数第 6 行：上 5 行、下 4 行，
 * 视觉上略低于正中——视线落点本来就在中线偏下一点。
 */
private const val RailPinRow = 5

private val RailShape = RoundedCornerShape(16.dp)
private val ItemShape = RoundedCornerShape(10.dp)

/** 和 `TvScroll` 同一条曲线：快起、缓收。 */
private val RailEasing = CubicBezierEasing(0f, 0f, 0.2f, 1f)

/**
 * 右侧动态时间轴：一级轨 + 二级面板。
 *
 * 普通相册是「年 → 月」，宝宝相册是「岁 → 月龄」（[Timeline.baby] 决定文案）。两级的行为
 * 完全一样，所以只有一套代码。
 *
 * 焦点完全由这里自己管——整个浮层只有**一个**焦点节点（外框），两级的高亮是画出来的，
 * 不是靠焦点搜索。这么做有三个理由：
 *   1. 上下键在列表两端不会「跑」到别的地方去，也不用去调 focus group；
 *   2. 面板显示期间网格收不到任何按键，焦点穿透在物理上不存在；
 *   3. 焦点框不会因为列表项变多而重排。
 *
 * 一级轨是个**固定高度的滚动窗口**（最多 [RailWindowRows] 行）：光标走到第 [RailPinRow] 行
 * 之后就不再往下走，改成整块文字往上滚；滚到剩最后 [RailWindowRows] 行时窗口夹紧，光标再
 * 接着走。这样无论相册有一年还是二十年，面板高度和光标位置都是固定的，用户第二次打开不用
 * 重新找「高亮在哪」。
 *
 * [anchorMajor]/[anchorMinor] 是当前网格焦点照片所属的年月（或岁/月龄），每次打开都把光标
 * 落在这儿。照片还没加载完时它们可能为 null，那就落到最上面（最新）的一组。
 */
@Composable
internal fun TimelineRail(
    timeline: Timeline,
    anchorMajor: Int?,
    anchorMinor: Int?,
    visible: Boolean,
    onJump: (TimelineBucket) -> Unit,
    onClose: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val baby = timeline.baby
    // 二级项的中文标签「11个月」比「12月」宽，宝宝相册的面板要放宽一点，否则会挤到两边。
    val monthWidth = remember(baby) { if (baby) BabyMonthWidth else MonthWidth }

    // 没有拍摄时间的照片不进时间轴（用户定的）：它们分不出年月，混在一级项里只会让人以为
    // 那一段还有一批照片。要看得回到网格里往下翻——它们排在最后。
    val rails = remember(timeline) { timeline.groups.map { it.major } }

    var cursor by remember { mutableIntStateOf(0) }
    var monthsOpen by remember { mutableStateOf(false) }
    var monthCursor by remember { mutableIntStateOf(0) }

    // ── 一级窗口的几何 ────────────────────────────────────────────────────
    val count = rails.size

    // 不同分辨率的电视能放下的行数不一样：1080p 上恒为 10，别在小屏上把面板顶出画面。
    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    val maxRows = remember(screenHeightDp) {
        val usable = screenHeightDp - 48 - (RailPadV.value * 2).toInt() - 12
        (usable / YearItemHeight.value.toInt()).coerceIn(3, RailWindowRows)
    }
    val rows = remember(count, maxRows) { minOf(maxRows, count).coerceAtLeast(1) }

    // 窗口顶端该停在哪一项。一条式子覆盖三种情况：
    //   cursor ≤ 5            → 0，光标自己走
    //   6 ≤ cursor ≤ 末尾-10   → cursor-5，光标钉在第 6 行、文字整体上滚
    //   末尾 10 项            → 夹紧在 count-rows，光标重新往下走（否则下方会留空行）
    val topFor: (Int) -> Int = { index ->
        (index - RailPinRow).coerceIn(0, (count - rows).coerceAtLeast(0))
    }
    val topTarget = topFor(cursor)

    // 用「项」做单位的浮点位置，动画期间是小数；切片起点 = floor(windowTop)，小数部分用 offset 平移。
    val windowTop = remember { Animatable(0f) }
    val yearPx = with(LocalDensity.current) { YearItemHeight.toPx() }

    // 每次打开都回到「当前照片所属的那一段」，不记住上次停在哪。
    LaunchedEffect(visible) {
        if (!visible) return@LaunchedEffect
        monthsOpen = false
        val start = rails.indexOfFirst { it == anchorMajor }.takeIf { it >= 0 } ?: 0
        // 先 snap 再改 cursor：锚点可能在很下面，一帧到位，别从最新一年一路滚下去
        // （连滚二十行会晕，看着也像卡住了）。反过来的话 cursor 一改就会触发下面那个动画
        // effect，snap 就白做了。
        windowTop.snapTo(topFor(start).toFloat())
        cursor = start
        monthCursor = 0
    }

    // 按键移动才走动画。被打断时 Animatable 从当前值接着走，不会回跳。
    LaunchedEffect(cursor, count, rows) {
        if (windowTop.value == topTarget.toFloat()) return@LaunchedEffect
        windowTop.animateTo(topTarget.toFloat(), tween(220, easing = RailEasing))
    }

    val selectedGroup: TimelineGroup? = timeline.groups.getOrNull(cursor)
    val selectedBuckets: List<TimelineBucket> = selectedGroup?.buckets ?: emptyList()
    val monthIndex = monthCursor.coerceIn(0, (selectedBuckets.lastIndex).coerceAtLeast(0))

    val handleKey: (KeyEvent) -> Boolean = handle@{ event ->
        if (event.type != KeyEventType.KeyDown) return@handle false
        val key = event.key
        if (!monthsOpen) {
            when (key) {
                Key.DirectionUp -> {
                    if (cursor > 0) cursor--
                    true
                }

                Key.DirectionDown -> {
                    if (cursor < rails.lastIndex) cursor++
                    true
                }

                // 左右键在这个面板里**什么都不做**（用户定的）。
                // 之前左右是「在两极之间来回走」，看着有方位感，实际用起来是两套动作记两遍，
                // 还容易右键按两下就退回一级了。现在只有一条路：上下选、确定进/跳、返回退。
                // 仍然返回 true —— 吞掉，不让它漏到底下的网格去。
                Key.DirectionLeft, Key.DirectionRight -> true

                Key.Back -> {
                    onClose()
                    true
                }

                Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                    if (selectedBuckets.isEmpty()) {
                        false
                    } else {
                        monthCursor = selectedBuckets
                            .indexOfFirst { it.minor == anchorMinor }
                            .coerceAtLeast(0)
                        monthsOpen = true
                        true
                    }
                }

                else -> false
            }
        } else {
            when (key) {
                Key.DirectionUp -> {
                    if (monthIndex > 0) monthCursor = monthIndex - 1
                    true
                }

                Key.DirectionDown -> {
                    if (monthIndex < selectedBuckets.lastIndex) monthCursor = monthIndex + 1
                    true
                }

                // 左右同样不做事（见一级分支的说明）：二级只有上下挪、确定跳、返回退一级。
                Key.DirectionLeft, Key.DirectionRight -> true

                // 返回只退一级，不整个收起 —— 二级退完还要能接着改一级。
                Key.Back -> {
                    monthsOpen = false
                    true
                }

                Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                    selectedBuckets.getOrNull(monthIndex)?.let { onJump(it) }
                    true
                }

                else -> false
            }
        }
    }

    // 面板自己是否拿着焦点。下面那个循环靠它判断「要不要抢回来」。
    var panelFocused by remember { mutableStateOf(false) }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(180)) +
            slideInHorizontally(tween(180, easing = RailEasing)) { it / 5 },
        // 收起是纯淡出、不位移：眼睛刚被面板吸过去，让它原地消失，别再多给一个动势。
        exit = fadeOut(tween(150)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(end = 20.dp)
                .focusRequester(focusRequester)
                .onFocusChanged { panelFocused = it.isFocused }
                .focusable()
                .onKeyEvent(handleKey),
        ) {
            // 面板在屏幕上的**整段时间**守住焦点，不是「抢到一次就撒手」。
            //
            // 只重试到成功是不够的：之后焦点一旦被抢走（浮层动画重建节点、网格那边又
            // requestFocus），面板就变成「看得见、收不到键」——按键会一路漏到系统层，
            // 实测能把 LEFT 当成返回、把相册整个换掉。80ms 一次，丢了立刻抢回来。
            LaunchedEffect(visible) {
                if (!visible) return@LaunchedEffect
                while (true) {
                    if (!panelFocused) runCatching { focusRequester.requestFocus() }
                    delay(80)
                }
            }

            Row(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(vertical = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AnimatedVisibility(
                    visible = monthsOpen,
                    enter = fadeIn(tween(160)) +
                        slideInHorizontally(tween(160, easing = RailEasing)) { it / 2 },
                    exit = fadeOut(tween(120)),
                ) {
                    Column(
                        modifier = Modifier
                            .width(monthWidth)
                            .clip(RailShape)
                            .background(RailBg)
                            .border(1.dp, BorderColor, RailShape)
                            .padding(horizontal = RailPadH, vertical = RailPadV),
                    ) {
                        selectedBuckets.forEachIndexed { index, bucket ->
                            RailItem(
                                // 宝宝相册的二级是月龄：0 个月就写着「0个月」，不换成「出生」——
                                // 一排里只有它是中文词，会显得像另一种东西。
                                label = if (baby) {
                                    stringResource(R.string.timeline_baby_months, bucket.minor)
                                } else {
                                    monthLabel(bucket.minor)
                                },
                                active = index == monthIndex,
                                height = MonthItemHeight,
                                style = MaterialTheme.typography.titleMedium,
                                barHeight = 20.dp,
                            )
                        }
                    }
                }

                Spacer(Modifier.width(10.dp))

                // 一级轨：固定高度的窗口。项数变了高度会跟着变（索引补年份时会从 8 行变
                // 10 行），给个 180ms 过渡，别让它硬跳一下。
                val windowHeight by animateDpAsState(
                    targetValue = YearItemHeight * rows + RailPadV * 2,
                    animationSpec = tween(180, easing = RailEasing),
                )
                Box(
                    modifier = Modifier
                        .width(RailWidth)
                        .height(windowHeight)
                        .clip(RailShape)
                        .background(RailBg)
                        .border(1.dp, BorderColor, RailShape)
                        .padding(horizontal = RailPadH, vertical = RailPadV),
                ) {
                    // 内容槽：右边缘让出滚动条的位置。
                    //
                    // 滚动方案：不把整列 23 行都塞进一个 requiredHeight 的 Column——那样 child
                    // 比 clip 父级高，Compose 的 Box 会「居中溢出」（上下各溢一半），第 0 行就
                    // 跑到屏幕上方外面、高亮也跟着没了（已在 0.15.3 上验过这个坑）。
                    // 改成只渲染**可见窗口 + 1 行**的切片，用 layout 级 offset 做亚行级平移：
                    // windowTop 是浮点（动画期间是小数），floor 取整数行号作切片起点，小数部分
                    // 用 offset 上移，过渡帧也能露出下一行的头。
                    //
                    // 上下渐隐和右侧滚动条按用户要求都去掉：面板里能同时看到多行年份，
                    // 末行是否完整本身就是「还能不能滚」的线索，不再需要额外装饰。
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clipToBounds(),
                    ) {
                        val baseTop = windowTop.value.toInt()
                            .coerceIn(0, (count - rows).coerceAtLeast(0))
                        val subPx = (windowTop.value - baseTop) * yearPx
                        val sliceEnd = (baseTop + rows + 1).coerceAtMost(count)
                        val sliceIndices = baseTop until sliceEnd

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                // offset 是 layout 级平移，会把整个 Column 上移 subPx；
                                // graphicsLayer 的 translationY 只在绘制阶段生效，positionInRoot
                                // 不变，焦点和命中测试也不会偏——但这里不需要命中（按键自己管），
                                // 用 offset 是为了和 requiredHeight 互斥不踩居中溢出的坑。
                                .offset { IntOffset(x = 0, y = -subPx.roundToInt()) },
                        ) {
                            sliceIndices.forEach { index ->
                                val major = rails[index]
                                RailItem(
                                    // 宝宝相册一级是「岁」：0岁在最下，越往上越大——年龄越大
                                    // 时间越近，和普通相册「新年份在上」是同一个方向。
                                    label = if (baby) {
                                        stringResource(R.string.timeline_baby_years, major)
                                    } else {
                                        stringResource(R.string.timeline_year, major)
                                    },
                                    active = index == cursor,
                                    height = YearItemHeight,
                                    style = MaterialTheme.typography.titleLarge,
                                    barHeight = 24.dp,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 面板里的一行。高亮不用焦点态，用「填充 + 左侧亮条」——和设置页 `FocusableSurface`
 * 同一套语言，一眼就知道这个应用里「选中」长什么样。
 *
 * **不缩放**：缩放在列表里会推挤邻居，而且 3 米外那点比例变化看不出来，只见文字在抖。
 *
 * 文字一律左对齐：标签宽度差得远（「2026年」5 字、「0岁」2 字），居中会让每一行的起点都不
 * 一样，扫视时眼睛得重新找落点。
 */
@Composable
private fun RailItem(
    label: String,
    active: Boolean,
    height: Dp,
    style: TextStyle,
    barHeight: Dp,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .clip(ItemShape)
            .background(if (active) scheme.surfaceVariant else Color.Transparent),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(3.dp)
                .height(barHeight)
                .clip(RoundedCornerShape(1.5.dp))
                .background(if (active) scheme.primary else Color.Transparent),
        )
        Spacer(Modifier.width(13.dp))
        Text(
            text = label,
            style = style,
            color = if (active) scheme.onSurface else scheme.onSurfaceVariant,
            maxLines = 1,
            textAlign = TextAlign.Start,
        )
    }
}

/** 面板底色：压得住照片，又不至于像一块黑。 */
private val RailBg = Color(0xFF141414).copy(alpha = 0.94f)

/** 月份名按系统语言取缩写：中文「10月」、英文「Oct」。 */
private fun monthLabel(month: Int): String =
    java.time.Month.of(month).getDisplayName(
        java.time.format.TextStyle.SHORT,
        java.util.Locale.getDefault(),
    )
