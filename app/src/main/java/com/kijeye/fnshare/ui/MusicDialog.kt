package com.kijeye.fnshare.ui

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kijeye.fnshare.R
import com.kijeye.fnshare.domain.model.MusicEntry
import com.kijeye.fnshare.domain.model.MusicGroup
import com.kijeye.fnshare.domain.model.MusicSourceKind
import com.kijeye.fnshare.presentation.state.MusicCatalogActions
import com.kijeye.fnshare.presentation.state.MusicCatalogUi
import kotlinx.coroutines.delay

/**
 * 幻灯片背景音乐的弹窗。从浏览页二级菜单的「音乐」项打开。
 *
 * 顶部是一条五格工具条：播放顺序 / 歌词 / 全选 / 扫描（或刷新歌单）/ 来源，左右键在格里横移。
 * 下面是列表：本地源直接是曲目；飞牛源是两级——歌单列表、进某歌单后是其曲目。
 * 歌单行按 OK = 勾选 / 取消整张歌单（行尾 ✓，与本地勾曲目同一套选择集），
 * 按 → = 进曲目步；曲目步按 ←/Back = 退回歌单列表。
 * 勾歌单**不播放**：背景乐一律由幻灯片开演驱动，切源、勾选都只改选择集。
 *
 * 「来源」格按 OK 就地展开一个两行的二级面板（本地音乐 / 飞牛音乐），不另起一层 Dialog：
 * 弹窗本身已有 58% scrim + 模糊，再叠 Dialog 只会更糊且多一层焦点层级。
 *
 * 键位自己吃：用 `onPreviewKeyEvent` 在预览阶段就消费方向 / OK / 返回，不会漏给底下的看图页。
 * 左右键在列表里也吞掉：弹窗开着时漏给看图页，就成了「背景照片被方向键翻走了」。
 *
 * 焦点只固定落在整张卡片上（[focusTarget]，不画系统焦点框），由内部状态驱动高亮；
 * 工具条与列表行都不单独抢焦点。
 */
@Composable
fun MusicDialog(
    catalog: MusicCatalogUi,
    /** 背景乐真的在出声（播放器监听器实时维护）。 */
    playing: Boolean,
    /** 这一轮里有曲子解不开，播放器已跳过。 */
    failed: Boolean,
    actions: MusicCatalogActions,
    onClose: () -> Unit,
) {
    val confirmKeys = remember { setOf(Key.DirectionCenter, Key.Enter, Key.Spacebar) }

    val isFn = catalog.sourceKind == MusicSourceKind.FN
    // 飞牛歌单步：列表显示歌单；其余（本地曲目 / 飞牛曲目步）显示曲目列表。
    val stepIsGroups = isFn && catalog.currentGroupId == null
    val listItems = if (stepIsGroups) catalog.groups.size else catalog.tracks.size

    var toolCol by remember { mutableStateOf(0) }
    var inList by remember { mutableStateOf(false) }
    var listRow by remember { mutableStateOf(0) }
    var panelOpen by remember { mutableStateOf(false) }
    var panelCursor by remember { mutableStateOf(if (isFn) 1 else 0) }

    // 列表变短后光标收回最后一行；列表空了就交还工具条，避免停在「幽灵行」上。
    LaunchedEffect(listItems, stepIsGroups) {
        if (listItems == 0) {
            inList = false
        } else if (listRow > listItems - 1) {
            listRow = listItems - 1
        }
    }

    // 屏蔽系统按键自动重复：长按方向键会连发 KeyDown，用「同一键是否已在按下」去重。
    var pressedKey by remember { mutableStateOf<Key?>(null) }

    val dialogFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { dialogFocus.requestFocus() }
    }

    // 歌单步「全选」勾的是全部歌单，曲目步勾的是全部曲目——两种都有可选项，
    // 所以这一格不再按步禁用；刷新在歌单步表示重拉歌单。
    val selectAllEnabled = true
    val sourceLabel = if (isFn) stringResource(R.string.music_source_fn_short) else stringResource(R.string.music_source_local_short)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x99000000))
            .onPreviewKeyEvent { event ->
                when (event.type) {
                    KeyEventType.KeyDown -> {
                        if (pressedKey == event.key) return@onPreviewKeyEvent true
                        pressedKey = event.key
                        when (event.key) {
                            Key.Back -> {
                                when {
                                    panelOpen -> panelOpen = false
                                    // 飞牛曲目步按返回先回歌单列表，不直接关弹窗。
                                    inList && !stepIsGroups -> actions.onBackToGroups()
                                    else -> onClose()
                                }
                                true
                            }
                            Key.DirectionUp -> {
                                if (panelOpen) {
                                    panelCursor = (panelCursor + 1) % 2
                                } else if (inList) {
                                    if (listRow == 0) inList = false else listRow--
                                }
                                true
                            }
                            Key.DirectionDown -> {
                                if (panelOpen) {
                                    panelCursor = (panelCursor + 1) % 2
                                } else if (!inList && listItems > 0) {
                                    inList = true
                                    listRow = listRow.coerceAtMost(listItems - 1)
                                } else if (inList && listRow < listItems - 1) {
                                    listRow++
                                }
                                true
                            }
                            Key.DirectionLeft -> {
                                if (!inList) toolCol = (toolCol - 1).coerceAtLeast(0)
                                true
                            }
                            Key.DirectionRight -> {
                                if (panelOpen) {
                                    // 面板里左右不做事，吞掉以免翻照片。
                                } else if (inList && stepIsGroups) {
                                    // 歌单行按 → 进曲目步。
                                    catalog.groups.getOrNull(listRow)?.let { actions.onOpenGroup(it.id) }
                                    listRow = 0
                                } else if (!inList) {
                                    toolCol = (toolCol + 1).coerceAtMost(TOOL_COUNT - 1)
                                }
                                true
                            }
                            in confirmKeys -> {
                                when {
                                    panelOpen -> {
                                        if (panelCursor == 0) {
                                            actions.onSourceChanged(MusicSourceKind.LOCAL)
                                        } else if (catalog.fnLoggedIn) {
                                            actions.onSourceChanged(MusicSourceKind.FN)
                                        }
                                        panelOpen = false
                                    }
                                    !inList && !catalog.hasPermission && !isFn -> actions.onRequestPermission()
                                    inList && stepIsGroups -> {
                                        // 与本地勾曲目同一套选择集：歌单整体当一项勾选，
                                        // 行尾出 ✓；不拉曲目、不立即起播，等幻灯片开演。
                                        catalog.groups.getOrNull(listRow)?.let { actions.onToggleGroup(it.id) }
                                    }
                                    inList -> catalog.tracks.getOrNull(listRow)?.let { actions.onToggle(it.id) }
                                    else -> when (toolCol) {
                                        0 -> actions.onToggleOrder()
                                        1 -> actions.onToggleLyrics()
                                        2 -> if (selectAllEnabled) actions.onToggleAll()
                                        3 -> actions.onRefresh()
                                        4 -> { panelCursor = if (catalog.sourceKind == MusicSourceKind.FN) 1 else 0; panelOpen = true }
                                    }
                                }
                                true
                            }
                            else -> false
                        }
                    }
                    KeyEventType.KeyUp -> {
                        pressedKey = null
                        false
                    }
                    else -> false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.7f)
                .fillMaxHeight(0.85f)
                .focusRequester(dialogFocus)
                .focusTarget()
                .background(SURFACE, RoundedCornerShape(16.dp))
                .border(1.dp, BORDER, RoundedCornerShape(16.dp))
                .padding(28.dp, 22.dp),
        ) {
            // 标题 + 当前来源面包屑 + 播放中状态
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = stringResource(R.string.music_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = PRIMARY,
                )
                val crumb = when {
                    !isFn -> stringResource(R.string.music_breadcrumb_local)
                    stepIsGroups -> stringResource(R.string.music_breadcrumb_fn_groups)
                    else -> stringResource(R.string.music_breadcrumb_fn_tracks, catalog.currentGroupTitle)
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(PRIMARY.copy(alpha = 0.14f))
                        .padding(horizontal = 10.dp, vertical = 3.dp),
                ) {
                    Text(text = crumb, style = MaterialTheme.typography.labelSmall, color = PRIMARY)
                }
                if (playing) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(PRIMARY.copy(alpha = 0.18f))
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.music_playing),
                            style = MaterialTheme.typography.labelMedium,
                            color = PRIMARY,
                        )
                    }
                }
            }

            Spacer(Modifier.height(6.dp))
            // 歌单步勾的是歌单（fnpl:），曲目步勾的是曲目（file: / fn:），
            // 两种都用 catalog.selected 这一个集合，计数按当前这一屏的项来算。
            val visibleCount = if (stepIsGroups) {
                catalog.groups.count { it.id in catalog.selected }
            } else {
                catalog.tracks.count { it.id in catalog.selected }
            }
            Text(
                text = if (stepIsGroups) {
                    stringResource(R.string.music_groups_hint, visibleCount)
                } else {
                    buildString {
                        append(stringResource(R.string.music_hint))
                        append("　·　")
                        append(stringResource(R.string.music_selected, visibleCount))
                    }
                },
                style = MaterialTheme.typography.labelMedium,
                color = MUTED,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (failed) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.music_play_failed),
                    style = MaterialTheme.typography.labelMedium,
                    color = ERROR,
                )
            }
            if (catalog.error != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = catalog.error,
                    style = MaterialTheme.typography.labelMedium,
                    color = ERROR,
                )
            }
            Spacer(Modifier.height(14.dp))

            // 本地源未授权：不画列表，引导去授权。飞牛源不需要本地权限。
            if (!isFn && !catalog.hasPermission) {
                PermissionBlock(onRequest = actions.onRequestPermission)
            } else if (panelOpen) {
                SourcePanel(
                    fnLoggedIn = catalog.fnLoggedIn,
                    fnAccount = catalog.fnAccount,
                    cursor = panelCursor,
                    onClose = { panelOpen = false },
                )
            } else {
                val listState = rememberLazyListState()
                LaunchedEffect(inList, listRow, listItems) {
                    if (!inList || listItems == 0) return@LaunchedEffect
                    val target = listRow.coerceAtMost(listItems - 1)
                    val info = listState.layoutInfo
                    val row = info.visibleItemsInfo.firstOrNull { it.index == target }
                    val fullyVisible = row != null &&
                        row.offset >= info.viewportStartOffset &&
                        row.offset + row.size <= info.viewportEndOffset
                    if (!fullyVisible) listState.animateScrollToItem(target)
                }

                val allPicked = if (stepIsGroups) {
                    catalog.groups.isNotEmpty() && catalog.groups.all { it.id in catalog.selected }
                } else {
                    catalog.tracks.isNotEmpty() && catalog.tracks.all { it.id in catalog.selected }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ToolCard(
                        label = stringResource(R.string.music_order),
                        value = stringResource(
                            if (catalog.musicShuffled) R.string.music_order_random else R.string.music_order_seq,
                        ),
                        highlighted = !inList && toolCol == 0,
                        modifier = Modifier.weight(1f),
                    )
                    ToolCard(
                        label = stringResource(R.string.music_lyrics),
                        value = stringResource(
                            if (catalog.lyricsEnabled) R.string.settings_on else R.string.settings_off,
                        ),
                        highlighted = !inList && toolCol == 1,
                        modifier = Modifier.weight(1f),
                    )
                    ToolCard(
                        label = stringResource(R.string.music_select_all),
                        value = stringResource(
                            if (allPicked) R.string.music_select_all_off else R.string.music_select_all,
                        ),
                        highlighted = !inList && toolCol == 2 && selectAllEnabled,
                        dimmed = !selectAllEnabled,
                        modifier = Modifier.weight(1f),
                    )
                    ToolCard(
                        label = if (isFn) stringResource(R.string.music_refresh_fn) else stringResource(R.string.music_refresh_short),
                        value = stringResource(if (catalog.scanning || catalog.loading) R.string.music_scanning else R.string.music_refresh_value),
                        highlighted = !inList && toolCol == 3,
                        modifier = Modifier.weight(1f),
                    )
                    ToolCard(
                        label = stringResource(R.string.music_source),
                        value = sourceLabel,
                        highlighted = !inList && toolCol == 4,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(10.dp))
                // 矮屏兜底：列表吃满剩余高度并自己滚动。外层 Column 有固定的
                // fillMaxHeight(0.85f)，头部+工具条占完后剩多少就用多少，不会把内容挤出弹窗。
                // 不给外层 Column 加 verticalScroll —— 它和 LazyColumn 嵌套滚动会互相抢
                // 无限高度约束，比不兜底更容易在矮屏上崩布局。原来的 520.dp 硬上限也去掉：
                // 高屏上它反而把列表钉死，矮屏上又帮不上忙。
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    when {
                        catalog.loading -> item {
                            Text(
                                text = stringResource(R.string.music_loading),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MUTED,
                                modifier = Modifier.padding(vertical = 16.dp),
                            )
                        }
                        stepIsGroups && catalog.groups.isEmpty() -> item {
                            Text(
                                text = stringResource(R.string.music_no_groups),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MUTED,
                                modifier = Modifier.padding(vertical = 16.dp),
                            )
                        }
                        !stepIsGroups && catalog.tracks.isEmpty() -> item {
                            Text(
                                text = stringResource(R.string.music_empty).replace("\\n", "\n"),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MUTED,
                                modifier = Modifier.padding(vertical = 16.dp),
                            )
                        }
                        stepIsGroups -> itemsIndexed(catalog.groups, key = { _, g -> g.id }) { index, group ->
                            GroupRow(
                                group = group,
                                ticked = group.id in catalog.selected,
                                highlighted = inList && listRow == index,
                            )
                        }
                        else -> itemsIndexed(catalog.tracks, key = { _, e -> e.id }) { index, entry ->
                            TrackRow(
                                entry = entry,
                                ticked = entry.id in catalog.selected,
                                highlighted = inList && listRow == index,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 来源二级面板：就地替换列表区。两行（本地音乐 / 飞牛音乐），不另起 Dialog。
 * 飞牛未登录时该行置灰且不可选，提示先到设置页登录。
 */
@Composable
private fun SourcePanel(
    fnLoggedIn: Boolean,
    fnAccount: String,
    cursor: Int,
    onClose: () -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SourceRow(
            title = stringResource(R.string.music_source_local),
            subtitle = stringResource(R.string.music_source_local_sub),
            selected = cursor == 0,
            enabled = true,
            shape = shape,
        )
        SourceRow(
            title = stringResource(R.string.music_source_fn),
            subtitle = if (fnLoggedIn) {
                stringResource(R.string.music_source_fn_logged, fnAccount)
            } else {
                stringResource(R.string.music_source_fn_not_logged)
            },
            selected = cursor == 1,
            enabled = fnLoggedIn,
            shape = shape,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.music_source_panel_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MUTED,
        )
    }
}

@Composable
private fun SourceRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    enabled: Boolean,
    shape: RoundedCornerShape,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) SURFACE_VAR else Color(0xFF202020))
            .border(1.dp, if (selected) PRIMARY else BORDER, shape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier
                .width(3.dp)
                .height(20.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(if (selected && enabled) PRIMARY else Color.Transparent),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = if (enabled) (if (selected) PRIMARY else Color.White) else Color(0xFF6E6E6E),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = if (enabled) MUTED else Color(0xFF5E5E5E),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 顶部工具条的一格：上面一行小标签（这一格管什么），下面一行当前值或动作名。
 *
 * 五格等宽横排，用左右方向键横向移动光标。高亮态走「整块填色 + 亮边框」。
 * [dimmed] 表示当前来源下此格无意义（如歌单步的「全选」），仍占位但不可选、颜色压暗。
 */
@Composable
private fun ToolCard(
    label: String,
    value: String,
    highlighted: Boolean,
    modifier: Modifier = Modifier,
    dimmed: Boolean = false,
) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(if (highlighted) SURFACE_VAR else Color(0xFF202020))
            .border(1.dp, if (highlighted) PRIMARY else BORDER, shape)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (highlighted) PRIMARY else if (dimmed) Color(0xFF5E5E5E) else MUTED,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            color = if (highlighted) PRIMARY else if (dimmed) Color(0xFF5E5E5E) else Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun GroupRow(
    group: MusicGroup,
    ticked: Boolean,
    highlighted: Boolean,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (highlighted) SURFACE_VAR else Color.Transparent)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier
                .width(3.dp)
                .height(20.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(if (highlighted) PRIMARY else Color.Transparent),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = group.title,
                style = MaterialTheme.typography.titleSmall,
                color = if (highlighted) PRIMARY else Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = group.subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MUTED,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // 与 TrackRow 完全一致的勾选标记：勾了才出 ✓，没勾留白。
        if (ticked) {
            Text(
                text = "✓",
                style = MaterialTheme.typography.titleMedium,
                color = PRIMARY,
            )
        }
    }
}

@Composable
private fun TrackRow(
    entry: MusicEntry,
    ticked: Boolean,
    highlighted: Boolean,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(8.dp)
    Row(
            modifier = modifier
                .fillMaxWidth()
                .clip(shape)
                .background(if (highlighted) SURFACE_VAR else Color.Transparent)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
        Box(
            Modifier
                .width(3.dp)
                .height(20.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(if (highlighted) PRIMARY else Color.Transparent),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.title,
                style = MaterialTheme.typography.titleSmall,
                color = if (highlighted) PRIMARY else Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!entry.playable) {
                Text(
                    text = stringResource(R.string.music_unsupported),
                    style = MaterialTheme.typography.labelSmall,
                    color = ERROR,
                )
            } else if (entry.durationMs > 0) {
                Text(
                    text = formatMusicDuration(entry.durationMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MUTED,
                )
            }
        }
        if (ticked) {
            Text(
                text = "✓",
                style = MaterialTheme.typography.titleMedium,
                color = PRIMARY,
            )
        }
    }
}

@Composable
private fun PermissionBlock(onRequest: () -> Unit) {
    Column(Modifier.padding(vertical = 12.dp)) {
        Text(
            text = stringResource(R.string.music_permission),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
        )
        Spacer(Modifier.height(12.dp))
        val shape = RoundedCornerShape(10.dp)
        Row(
            modifier = Modifier
                .clip(shape)
                .background(PRIMARY.copy(alpha = 0.16f))
                .border(1.dp, PRIMARY, shape)
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.music_permission_grant),
                style = MaterialTheme.typography.titleSmall,
                color = PRIMARY,
            )
        }
    }
}

/** `3:45`，超过一小时则 `1:02:03`。 */
private fun formatMusicDuration(millis: Long): String {
    val totalSeconds = millis / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

// 弹窗底色是独立浮层，仍写死暗色不随主题漂移；但「聚焦 / 选中 / 次要字 / 错误」
// 这几支笔必须跟着主题走：设置页换焦点色后，相册墙会变色，弹窗不能还停在近白。
// 用 @Composable getter 而不是普通常量，所有调用点仍写在原来的位置，不必层层传色值。
private val SURFACE = Color(0xFF1A1A1A)
private val SURFACE_VAR = Color(0xFF2A2A2A)
private val BORDER = Color(0xFF3D3D3D)
private val PRIMARY: Color
    @Composable get() = MaterialTheme.colorScheme.primary
private val MUTED: Color
    @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
private val ERROR: Color
    @Composable get() = MaterialTheme.colorScheme.error

/** 顶部工具条格数：顺序 / 歌词 / 全选 / 扫描（或刷新歌单）/ 来源。格子顺序即 cursor 的 0..4。 */
private const val TOOL_COUNT = 5
