package com.kijeye.fnshare.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kijeye.fnshare.BuildConfig
import com.kijeye.fnshare.R
import kotlinx.coroutines.delay

/** 落焦点前等一下：焦点目标要先挂上组合，FocusRequester 才认得它。和网格页同一个值。 */
private const val FOCUS_SETTLE_MS = 80L

/**
 * 低于这个屏高就收紧组间距与上下留白，尽量保住「6 行一屏」。
 * MuMu 横屏 617dp、常见电视 720dp 都在阈值之上；540dp 级的矮屏会命中。
 * 真正的兜底仍是 verticalScroll，收紧只是让矮屏不必滚。
 */
private const val COMPACT_HEIGHT_DP = 580

/**
 * 设置页的二维焦点网格：每行几个可聚焦格子，顺序必须和页面里的行顺序一致。
 *
 * 显示组 2 行、缓存组 2 行、更多组 1 行、底部「更多 | 退出登录」1 行，
 * 全页 6 行都是「左右各半」的双列，排满一屏不需要滚动。
 * 自己管这张表而不靠 Compose 的默认方向焦点搜索：默认的方向搜索从半宽格子
 * 跨行会落到哪一格是不确定的（实测会飘），电视遥控器上很别扭。
 */
internal val ROW_COLS = intArrayOf(2, 2, 2, 2, 2, 2)
private const val ROW_FOCUS = 0          // 聚焦动效 | 焦点颜色
private const val ROW_BACKDROP = 1       // 相册背景 | 相片背景
private const val ROW_CACHE_SIZE = 2     // 照片索引 | 缩略图
private const val ROW_CACHE_ACTION = 3   // 刷新相册 | 清除缓存
private const val ROW_MORE = 4           // 闲置保护 | 飞牛音乐
private const val ROW_ACCOUNT = 5        // 更多 | 退出登录（独立一行，不挂组标签）
private const val COL_LEFT = 0
private const val COL_RIGHT = 1

/** 二维导航的方向。独立于 Compose 的 Key，好让按键走向能做纯 JVM 单测。 */
internal enum class NavDirection { LEFT, RIGHT, UP, DOWN }

/**
 * 算出方向键该把焦点挪到哪一格；null = 已经在该方向的边界，焦点不动。
 *
 * 上下跨行时尽量保持同一列，目标行只有一格就钳到第 0 列；到顶/到底循环回另一端。
 * 纯函数，不碰焦点系统——[SettingsScreen] 拿到结果再 requestFocus，这样双列 + 整行
 * 混排的走向可以离线验证，不用靠遥控器一格格点。
 */
internal fun nextFocusCell(
    row: Int,
    col: Int,
    dir: NavDirection,
    rowCols: IntArray,
): Pair<Int, Int>? = when (dir) {
    NavDirection.LEFT -> if (col > 0) row to col - 1 else null
    NavDirection.RIGHT -> if (col < rowCols[row] - 1) row to col + 1 else null
    NavDirection.UP -> {
        val target = if (row == 0) rowCols.lastIndex else row - 1
        target to minOf(col, rowCols[target] - 1)
    }
    NavDirection.DOWN -> {
        val target = if (row == rowCols.lastIndex) 0 else row + 1
        target to minOf(col, rowCols[target] - 1)
    }
}

/** 闲置档位：0 是关，其余是分钟数。 */
private val IDLE_CHOICES = listOf(0, 5, 10, 30)

/** 同上，走闲置档位表。 */
private fun shiftIdle(current: Int, delta: Int): Int =
    shiftIn(IDLE_CHOICES, current, delta)

/**
 * 循环取下标：越界回绕到另一端，负数也回绕（floorMod）。
 * 抽成纯函数是为了让「最后一档再按 OK 回到第一档」能被 JVM 单测钉住。
 */
internal fun wrapIndex(index: Int, delta: Int, size: Int): Int =
    Math.floorMod(index + delta, size)

/** 当前值不在档位表里时（老存档里的自定义值）从中位档起步，再按方向挪一格，循环回绕。 */
private fun shiftIn(choices: List<Int>, current: Int, delta: Int): Int {
    val index = choices.indexOf(current).let { if (it < 0) choices.size / 2 else it }
    return choices[wrapIndex(index, delta, choices.size)]
}

/** 0 显示「关」，其余显示「N 分钟」。 */
private fun idleLabel(minutes: Int, off: String, fmt: String): String =
    if (minutes <= 0) off else String.format(fmt, minutes)

/**
 * 设置页。
 *
 * 每行**左右键在两格之间移动焦点、OK/确认键改当前格的值或触发动作**；上下键跨行
 * 并尽量保持同一列。底部「更多 | 退出登录」也是双列，左右键可切格。
 * 6 行格子排满一屏，不需要滚动；返回键退出，不需要「确定」按钮。
 */
@Composable
fun SettingsScreen(
    state: AppState,
    onIdle: (Int) -> Unit,
    onBackdropWall: (Boolean) -> Unit,
    onBackdropGrid: (Boolean) -> Unit,
    onFocusZoom: (Boolean) -> Unit,
    onFocusRingColor: (String) -> Unit,
    onRefresh: () -> Unit,
    onClearCache: () -> Unit,
    onMore: () -> Unit,
    onSignOut: () -> Unit,
    onFnMusicSubmit: (String, String, String) -> Unit,
    onFnMusicSignOut: () -> Unit,
    onFnMusicDismissError: () -> Unit,
    onBack: () -> Unit,
) {
    // 矮屏收紧：只改间距，不动行高、格子比例和焦点网格，导航逻辑一行不碰。
    val compact = LocalConfiguration.current.screenHeightDp < COMPACT_HEIGHT_DP
    val focusGrid = remember {
        Array(ROW_COLS.size) { r -> Array(ROW_COLS[r]) { FocusRequester() } }
    }
    var confirmingSignOut by remember { mutableStateOf(false) }
    var confirmingClearCache by remember { mutableStateOf(false) }
    // 纯视觉状态留在本屏，不进 AppState：登录结果与凭据才需要跨重组保存。
    var fnMusicLoginOpen by remember { mutableStateOf(false) }

    fun focusCell(row: Int, col: Int) {
        val c = col.coerceIn(0, ROW_COLS[row] - 1)
        runCatching { focusGrid[row][c].requestFocus() }
    }

    /**
     * 二维方向键。全页都是双列：左右切格、上下跨行（尽量保持列），到顶/到底循环回另一端。
     * 档位/颜色只由 OK 改，左右键一律归切格 —— 全页按键语义统一。
     * 方向键一律吃掉（返回 true），免得事件再冒泡给 Compose 的默认焦点搜索乱跳。
     * OK/确认键不在这里处理，交回各格子自己改值或触发动作。
     */
    fun navigate(row: Int, col: Int, key: Key): Boolean {
        val dir = when (key) {
            Key.DirectionLeft -> NavDirection.LEFT
            Key.DirectionRight -> NavDirection.RIGHT
            Key.DirectionUp -> NavDirection.UP
            Key.DirectionDown -> NavDirection.DOWN
            else -> return false
        }
        val target = nextFocusCell(row, col, dir, ROW_COLS)
        if (target != null) focusCell(target.first, target.second)
        // 方向键一律吃掉：返回 false 会让事件冒泡给 Compose 默认焦点搜索乱跳。
        return true
    }

    // 首帧组合时焦点目标还没挂上去，requestFocus 会抛「FocusRequester is not initialized」，
    // 原先被 runCatching 默默吞掉 —— 结果焦点落在滚动容器上，方向键被它当滚动吃掉，
    // 整页按键全无反应。等一帧多再要焦点，和网格页同一个套路。
    LaunchedEffect(Unit) {
        delay(FOCUS_SETTLE_MS)
        runCatching { focusGrid[ROW_FOCUS][COL_LEFT].requestFocus() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            // 容器自己也留一个焦点目标：各行按键冒泡到这里才能接住返回键，
            // 万一某次行焦点没拿到，也不会变成「整页按键全无响应」。
            .focusable()
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Back) {
                    onBack()
                    true
                } else {
                    false
                }
            }
            .padding(horizontal = 64.dp, vertical = if (compact) 8.dp else 16.dp),
    ) {
        // 账号和版本放标题同一行。原先挂在最底部，可视高度短的屏幕上根本滚不到——
        // 那里没有可聚焦项，遥控器不会把人带下去，等于这两条信息不存在。
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = if (compact) 8.dp else 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                text = stringResource(R.string.settings_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = stringResource(
                    R.string.settings_footer,
                    state.account.ifEmpty { "—" },
                    BuildConfig.VERSION_NAME,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        GroupLabel(stringResource(R.string.settings_group_display), compact)

        // 卡片焦点反馈里的「图在框里放大」那一层。外框的轻微放大和白边不受这个开关影响——
        // 关掉它只是不再裁画面，遥控器停在哪一张仍然认得出来。
        TwoColumnRow(
            left = {
                SettingRow(
                    title = stringResource(R.string.settings_focus_zoom),
                    value = stringResource(
                        if (state.focusZoom) R.string.settings_on else R.string.settings_off,
                    ),
                    modifier = Modifier.focusRequester(focusGrid[ROW_FOCUS][COL_LEFT]),
                    onKey = { key ->
                        when {
                            navigate(ROW_FOCUS, COL_LEFT, key) -> true
                            key == Key.DirectionCenter || key == Key.Enter -> {
                                onFocusZoom(!state.focusZoom)
                                true
                            }
                            else -> false
                        }
                    },
                )
            },
            right = {
                // 焦点描边颜色：OK 在 5 种里循环；左右键归二维导航，用来切回左格。
                ColorRow(
                    title = stringResource(R.string.settings_focus_ring),
                    colors = FOCUS_RING_COLORS,
                    currentHex = state.focusRingColorHex,
                    onPick = onFocusRingColor,
                    modifier = Modifier.focusRequester(focusGrid[ROW_FOCUS][COL_RIGHT]),
                    onNavigate = { key -> navigate(ROW_FOCUS, COL_RIGHT, key) },
                )
            },
        )

        // 动态背景分开开关：相册墙大图淡入是「效果」，照片页隔着缩略图看是「氛围」，
        // 两台电视可能一台要一台不要，所以不做成一个总开关。
        TwoColumnRow(
            left = {
                SettingRow(
                    title = stringResource(R.string.settings_backdrop_wall),
                    value = stringResource(
                        if (state.backdropWall) R.string.settings_on else R.string.settings_off,
                    ),
                    modifier = Modifier.focusRequester(focusGrid[ROW_BACKDROP][COL_LEFT]),
                    onKey = { key ->
                        when {
                            navigate(ROW_BACKDROP, COL_LEFT, key) -> true
                            key == Key.DirectionCenter || key == Key.Enter -> {
                                onBackdropWall(!state.backdropWall)
                                true
                            }
                            else -> false
                        }
                    },
                )
            },
            right = {
                SettingRow(
                    title = stringResource(R.string.settings_backdrop_grid),
                    value = stringResource(
                        if (state.backdropGrid) R.string.settings_on else R.string.settings_off,
                    ),
                    modifier = Modifier.focusRequester(focusGrid[ROW_BACKDROP][COL_RIGHT]),
                    onKey = { key ->
                        when {
                            navigate(ROW_BACKDROP, COL_RIGHT, key) -> true
                            key == Key.DirectionCenter || key == Key.Enter -> {
                                onBackdropGrid(!state.backdropGrid)
                                true
                            }
                            else -> false
                        }
                    },
                )
            },
        )

        // 缓存：占用和上限摆在一格里，斜杠分隔「3.4 MB / 256 MB」——
        // 一眼看出离满还差多少。刷新相册从「更多」搬进来，和清除缓存并排。
        GroupLabel(stringResource(R.string.settings_group_cache), compact)

        TwoColumnRow(
            left = {
                SettingRow(
                    title = stringResource(R.string.settings_cache_index),
                    value = "${formatCacheBytes(state.cacheBytes)} / ${formatCacheBytes(CACHE_LIMIT_BYTES)}",
                    modifier = Modifier.focusRequester(focusGrid[ROW_CACHE_SIZE][COL_LEFT]),
                    // 只读行：方向键只用来切格/跨行，不改任何值。
                    onKey = { key -> navigate(ROW_CACHE_SIZE, COL_LEFT, key) },
                )
            },
            right = {
                SettingRow(
                    title = stringResource(R.string.settings_cache_thumb),
                    value = "${formatCacheBytes(state.thumbCacheBytes)} / " +
                        formatCacheBytes(state.thumbCacheLimitBytes),
                    modifier = Modifier.focusRequester(focusGrid[ROW_CACHE_SIZE][COL_RIGHT]),
                    onKey = { key -> navigate(ROW_CACHE_SIZE, COL_RIGHT, key) },
                )
            },
        )

        TwoColumnRow(
            left = {
                ActionRow(
                    title = stringResource(R.string.settings_refresh),
                    // 刷新是后台长任务：进度直接显示在这一行的值胶囊里。
                    // 写成行下面的一行小字会整个掉出视口，等于没写。
                    value = if (state.cacheRefreshing) {
                        stringResource(
                            R.string.settings_cache_rebuilding,
                            state.cacheRebuildDone,
                            state.cacheRebuildTotal,
                        )
                    } else {
                        null
                    },
                    modifier = Modifier.focusRequester(focusGrid[ROW_CACHE_ACTION][COL_LEFT]),
                    onKey = { key -> navigate(ROW_CACHE_ACTION, COL_LEFT, key) },
                    onActivate = onRefresh,
                )
            },
            right = {
                ActionRow(
                    title = stringResource(R.string.settings_cache_clear),
                    modifier = Modifier.focusRequester(focusGrid[ROW_CACHE_ACTION][COL_RIGHT]),
                    onKey = { key -> navigate(ROW_CACHE_ACTION, COL_RIGHT, key) },
                    onActivate = { confirmingClearCache = true },
                )
            },
        )

        // 更多：闲置保护 | 飞牛音乐并成一行双列，全页才刚好一屏放得下、不用滚动。
        // 两格的值都短（关/5 分钟、未登录/已登录：tv），半宽够用；登录弹窗是覆盖层，
        // 不占这一行的宽度。左右键切格，OK 改当前格 / 打开弹窗。
        GroupLabel(stringResource(R.string.settings_group_more), compact)

        TwoColumnRow(
            left = {
                SettingRow(
                    title = stringResource(R.string.settings_idle),
                    value = idleLabel(
                        state.idleMinutes,
                        stringResource(R.string.settings_off),
                        stringResource(R.string.settings_idle_minutes),
                    ),
                    modifier = Modifier.focusRequester(focusGrid[ROW_MORE][COL_LEFT]),
                    // 左右键归二维导航切格；档位只由 OK 走到下一档（到头循环回第一档）。
                    onKey = { key ->
                        when {
                            navigate(ROW_MORE, COL_LEFT, key) -> true
                            key == Key.DirectionCenter || key == Key.Enter -> {
                                onIdle(shiftIdle(state.idleMinutes, 1))
                                true
                            }
                            else -> false
                        }
                    },
                )
            },
            right = {
                // 飞牛音乐子账户：只放一个入口，账号密码访问码都在弹窗里。
                // 相册用 NAS 系统账号，音乐是音乐服务里的子账户，两套凭据互不相干。
                SettingRow(
                    title = stringResource(R.string.fnm_settings_entry),
                    value = if (state.fnMusicLoggedIn) {
                        stringResource(R.string.fnm_status_on, state.fnMusicAccount)
                    } else {
                        stringResource(R.string.fnm_status_off)
                    },
                    modifier = Modifier.focusRequester(focusGrid[ROW_MORE][COL_RIGHT]),
                    onKey = { key ->
                        when {
                            navigate(ROW_MORE, COL_RIGHT, key) -> true
                            key == Key.DirectionCenter || key == Key.Enter -> {
                                fnMusicLoginOpen = true
                                true
                            }
                            else -> false
                        }
                    },
                )
            },
        )

        // 底部账号行：左「更多」入口、右「退出登录」，和上面各組一样左右各半。
        // 用户要求全页统一双列；退出登录仍保留红色描边和确认弹窗，按错代价不变。
        Spacer(Modifier.height(12.dp))
        TwoColumnRow(
            left = {
                ActionRow(
                    title = stringResource(R.string.settings_more),
                    modifier = Modifier.focusRequester(focusGrid[ROW_ACCOUNT][COL_LEFT]),
                    onKey = { key -> navigate(ROW_ACCOUNT, COL_LEFT, key) },
                    onActivate = onMore,
                )
            },
            right = {
                ActionRow(
                    title = stringResource(R.string.settings_sign_out),
                    danger = true,
                    modifier = Modifier.focusRequester(focusGrid[ROW_ACCOUNT][COL_RIGHT]),
                    onKey = { key -> navigate(ROW_ACCOUNT, COL_RIGHT, key) },
                    onActivate = { confirmingSignOut = true },
                )
            },
        )
    }

    if (fnMusicLoginOpen) {
        FnMusicLoginDialog(
            state = state,
            onSubmit = onFnMusicSubmit,
            onSignOut = onFnMusicSignOut,
            onDismissError = onFnMusicDismissError,
            onDismiss = { fnMusicLoginOpen = false },
        )
    }

    if (confirmingClearCache) {
        // 焦点显式落在「取消」上：清缓存不是按错的代价，是「想清楚再按」。
        val cancelFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { cancelFocus.requestFocus() } }

        AlertDialog(
            onDismissRequest = { confirmingClearCache = false },
            title = { Text(stringResource(R.string.settings_cache_clear_title)) },
            text = { Text(stringResource(R.string.settings_cache_clear_body)) },
            confirmButton = {
                DialogButton(
                    text = stringResource(R.string.settings_cache_clear),
                    danger = true,
                    onClick = {
                        confirmingClearCache = false
                        onClearCache()
                    },
                )
            },
            dismissButton = {
                DialogButton(
                    text = stringResource(R.string.wall_cancel),
                    onClick = { confirmingClearCache = false },
                    modifier = Modifier.focusRequester(cancelFocus),
                )
            },
            containerColor = MaterialTheme.colorScheme.surface,
        )
    }

    if (confirmingSignOut) {
        // 焦点显式落在「取消」上。默认焦点落到「退出登录」的代价是：手一抖就把账号清了。
        val cancelFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { cancelFocus.requestFocus() } }

        AlertDialog(
            onDismissRequest = { confirmingSignOut = false },
            title = { Text(stringResource(R.string.wall_sign_out_title)) },
            text = { Text(stringResource(R.string.wall_sign_out_body)) },
            confirmButton = {
                DialogButton(
                    text = stringResource(R.string.wall_sign_out),
                    danger = true,
                    onClick = {
                        confirmingSignOut = false
                        onSignOut()
                    },
                )
            },
            dismissButton = {
                DialogButton(
                    text = stringResource(R.string.wall_cancel),
                    onClick = { confirmingSignOut = false },
                    modifier = Modifier.focusRequester(cancelFocus),
                )
            },
            containerColor = MaterialTheme.colorScheme.surface,
        )
    }
}

/**
 * 一行两格，左右各半。
 *
 * 只负责均分宽度和留缝：两格各有自己的焦点目标（调用方挂 focusRequester）、
 * 各自的焦点视觉。左右键切格、上下键跨行统一由 [SettingsScreen] 的二维 navigate
 * 处理，不在这里碰按键，免得和格子内部「OK 改值」抢事件。
 */
@Composable
private fun TwoColumnRow(
    left: @Composable () -> Unit,
    right: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.weight(1f)) { left() }
        Box(Modifier.weight(1f)) { right() }
    }
}

/**
 * 一行设置。
 *
 * 焦点用「整行提亮 + 左侧竖条」表达，不缩放：设置页的行是整行宽的，
 * 缩放会让相邻两行在视觉上打架（这是设置页和卡片墙的分界）。
 */
@Composable
private fun SettingRow(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
    onKey: (Key) -> Boolean,
) {
    // 焦点从 clickable 的交互源读。onFocusChanged 挂在 clickable 之后是不生效的
    // （它只观察链右侧的焦点目标），这里在模拟器上实测过：那样写等于没有焦点视觉。
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val bar by animateFloatAsState(if (focused) 1f else 0f, label = "bar")

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (focused) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
            )
            .border(
                1.dp,
                if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                RoundedCornerShape(10.dp),
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
            ) {}
            .onKeyEvent { event ->
                event.type == KeyEventType.KeyDown && onKey(event.key)
            }
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 左侧竖条：0 宽度时也要占位，否则获得焦点的一瞬间整行文字会横跳。
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(
                    MaterialTheme.colorScheme.primary.copy(alpha = bar),
                ),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 16.dp).weight(1f),
        )
        ValuePill(value)
    }
}

@Composable
private fun ValuePill(value: String) {
    Box(
        modifier = Modifier
            .padding(start = 16.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(MaterialTheme.colorScheme.background)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(999.dp))
            .padding(horizontal = 20.dp, vertical = 6.dp),
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            // 双列后每一格只有半行宽：账号名再长也不许折成两行把行高撑起来，
            // 否则「刚好一屏」会被一个长名字破坏。
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 一行「焦点框颜色」设置。
 *
 * 和 [SettingRow] 同一个外壳（整行提亮 + 左侧竖条、不缩放），只是右边不是文字胶囊而是
 * 「一个色点 + 两字名」。OK/确认键跳到下一种颜色（到头停住）——电视遥控器没有鼠标，
 * 循环选择最顺手；左右键归二维导航 ([onNavigate])，用来切到同一行的另一格。
 */
@Composable
private fun ColorRow(
    title: String,
    colors: List<FocusRingColor>,
    currentHex: String,
    onPick: (String) -> Unit,
    onNavigate: (Key) -> Boolean = { false },
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val bar by animateFloatAsState(if (focused) 1f else 0f, label = "colorBar")
    val index = colors.indexOfFirst { it.hex == currentHex }.let { if (it < 0) 0 else it }
    val current = colors[index]

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (focused) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
            )
            .border(
                1.dp,
                if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                RoundedCornerShape(10.dp),
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
            ) {}
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                if (onNavigate(event.key)) return@onKeyEvent true
                when (event.key) {
                    Key.DirectionCenter, Key.Enter -> {
                        onPick(colors[wrapIndex(index, 1, colors.size)].hex)
                        true
                    }
                    else -> false
                }
            }
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 左侧竖条：和其它行一样，获得焦点才亮起。
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.primary.copy(alpha = bar)),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 16.dp).weight(1f),
        )
        // 当前颜色预览：小圆点，3 米外也认得出选的是哪个色。
        Box(
            Modifier
                .size(20.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(current.color)
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(999.dp)),
        )
        Spacer(Modifier.width(12.dp))
        ValuePill(current.label)
    }
}

@Composable
private fun GroupLabel(text: String, compact: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(
            top = if (compact) 8.dp else 16.dp,
            bottom = if (compact) 3.dp else 6.dp,
        ),
    )
}

/**
 * 一行「按下去会发生一件事」的设置。
 *
 * 和 SettingRow 长得一样，只是右边没有值胶囊、换成一个小箭头——电视上没法用鼠标，
 * 「这一行和上一行不是一类东西」只能靠这点差别说清楚。
 */
@Composable
private fun ActionRow(
    title: String,
    danger: Boolean = false,
    onActivate: () -> Unit,
    /** 右侧值胶囊。给「刷新相册」显示后台进度用；不给就没有胶囊。 */
    value: String? = null,
    /** 二维方向导航（切格 / 跨行）。OK/确认键返回 false，留给 clickable 触发动作。 */
    onKey: ((Key) -> Boolean)? = null,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val bar by animateFloatAsState(if (focused) 1f else 0f, label = "actionBar")

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            // 比 SettingRow 多 2dp：这一行没有值胶囊、本身更矮，不加的话会和上一行贴住。
            .padding(vertical = 4.dp)
            .onKeyEvent { event ->
                event.type == KeyEventType.KeyDown && (onKey?.invoke(event.key) ?: false)
            }
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (focused) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
            )
            .border(
                1.dp,
                when {
                    focused -> MaterialTheme.colorScheme.primary
                    danger -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.outline
                },
                RoundedCornerShape(10.dp),
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onActivate,
            )
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.primary.copy(alpha = bar)),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = if (danger) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.padding(start = 16.dp).weight(1f),
        )
        // 有进度时用胶囊显示，没进度就是原来那个「›」。
        if (value != null) {
            ValuePill(value)
        } else {
            Text(
                text = "›",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
