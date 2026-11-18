package com.kijeye.fnshare.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kijeye.fnshare.data.fn.SharedAlbum
import com.kijeye.fnshare.R
import com.kijeye.fnshare.engine.api.ImageRequestFactory
import kotlinx.coroutines.delay



/**
 * 相册墙：按「谁共享的」分组。
 *
 * 分组不是为了好看，是为了回答一个一定会被问到的问题——「这些照片是谁给的」。
 * 平铺成一片的话，家里两个人各共享三个相册，剩下的只能靠记。
 *
 * 每组从新一行开始（组标题占满整行），这样上一个人只剩半行时不会把下一个人挤在旁边。
 */
@Composable
fun WallScreen(
    state: AppState,
    requestFactory: ImageRequestFactory,
    onOpenAlbum: (SharedAlbum) -> Unit,
    onOpenSettings: () -> Unit,
) {
    if (state.owners.isEmpty()) {
        EmptyWall(loading = state.albumsLoading, onOpenSettings = onOpenSettings)
        return
    }

    val gridState = rememberLazyGridState()
    // 第一张卡片拿初始焦点；用 id 认，不用下标——分组一变下标就不对了。
    val firstAlbumId = state.owners.firstOrNull()?.albums?.firstOrNull()?.id

    // 每张卡片一个 requester + 记住「最后停在哪张」。
    // 右上角设置按钮在网格之外，按 ↓ 时 Compose 的二维焦点搜索回不到懒加载的格子里，
    // 焦点会卡在按钮上——所以那里按 ↓ 由我们自己把焦点交还给这张记住的卡。
    //
    // 0.28.2 修的坑：以前第一张卡用的是一个单独的 requester，其余卡走 requesterOf(id)。
    // 交还焦点时统一走 requesterOf(id)，取到的是一个**从没挂到任何卡片上**的空 requester；
    // 对空 requester 调 requestFocus() 既不移动焦点、也不抛异常，兜底那支行就永远不执行，
    // 表现就是「按上聚焦设置后，按下回不去」。现在所有卡片都只认 requesterOf(id) 这一套。
    val cardRequesters = remember { mutableMapOf<Long, FocusRequester>() }
    fun requesterOf(id: Long) = cardRequesters.getOrPut(id) { FocusRequester() }
    var lastFocusedId by remember { mutableStateOf<Long?>(null) }

    // 整页是否正在滚动（纵向跨行时 LazyVerticalGrid 平滑滚动）。这段时间非焦点卡的放大动画
    // 冻结（见 FocusFrame 的 [scrolling]）；横向移动不滚动，这个一直是 false。
    var scrolling by remember { mutableStateOf(false) }
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.isScrollInProgress }.collect { scrolling = it }
    }

    // 焦点背景：停在谁的相册上，谁的海报就在 300ms 后淡入成整屏模糊背景。
    // 设置里关掉就不组合 FocusBackdrop，省下整屏模糊的开销。
    var focusUrl by remember { mutableStateOf<String?>(null) }
    var menuFocused by remember { mutableStateOf(false) }

    // 同 GridScreen：描边颜色整页只解析一次，不再让每张相册卡在每次重组里重复解析。
    val ringColor = remember(state.focusRingColorHex) { parseRingColor(state.focusRingColorHex) }

    fun backToGrid() {
        // 记住的那张可能已经不在列表里（相册被取消共享、列表刷新过），
        // 这时退回第一张——空 requester 不报错，只能自己先校验一遍 id 还在不在。
        val alive = state.owners.flatMap { group -> group.albums.map { it.id } }.toSet()
        val targetId = lastFocusedId?.takeIf { it in alive } ?: firstAlbumId ?: return
        runCatching { requesterOf(targetId).requestFocus() }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // 截 ↑↓ 在根上做，比挂在按钮自己的 onKeyEvent 靠谱：
            // 预览事件从根往下传，父节点一定先拿到；按钮那层是 clickable 生成的焦点节点，
            // 在它上面加的 onKeyEvent 实测收不到方向键。
            .onPreviewKeyEvent { event ->
                if (menuFocused &&
                    event.type == KeyEventType.KeyDown &&
                    event.key == Key.DirectionDown
                ) {
                    backToGrid()
                    true
                } else {
                    false
                }
            },
    ) {
        if (state.backdropWall) {
            FocusBackdrop(focusUrl, Modifier.fillMaxSize(), requestFactory)
        }

        LazyVerticalGrid(
            // 固定 6 列，封面宽度由网格按剩余宽度算（左右各 48dp、列间 16dp）。
            // 换分辨率时列数不变、封面自己伸缩，形状仍是方的。
            columns = GridCells.Fixed(6),
            state = gridState,
            // 顶部留 84dp 给右上角的菜单入口：封面从它下面开始，不重叠。
            contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 84.dp, bottom = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            state.owners.forEach { group ->
                item(span = { GridItemSpan(maxLineSpan) }, key = "owner-${group.name}") {
                    OwnerHeader(group.name)
                }
                items(group.albums, key = { "album-${it.id}" }) { album ->
                    AlbumCard(
                        album = album,
                        requestFactory = requestFactory,
                        onOpen = { onOpenAlbum(album) },
                        onFocused = { focusUrl = album.posterUrl },
                        innerZoom = state.focusZoom,
                        ringColor = ringColor,
                        scrolling = scrolling,
                        modifier = Modifier
                            .focusRequester(requesterOf(album.id))
                            .onFocusChanged { if (it.isFocused) lastFocusedId = album.id },
                    )
                }
            }
        }

        // 遥控器一进来就得有焦点，否则第一次按方向键什么都不发生。
        // 必须等一帧再要焦点：首帧 requester 还没挂到组合上，会抛 "not initialized" 被
        // runCatching 静默吞掉——那样第一次按键就会落到右上角设置按钮上，人就卡在那儿了。
        LaunchedEffect(firstAlbumId) {
            if (firstAlbumId == null) return@LaunchedEffect
            delay(FOCUS_SETTLE_MS)
            runCatching { requesterOf(firstAlbumId).requestFocus() }
        }

        MenuEntry(
            onClick = onOpenSettings,
            onFocusChanged = { menuFocused = it },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 40.dp, top = 22.dp),
        )
    }
}

/**
 * 右上角的菜单入口。
 *
 * 平时完全透明（电视相框上不该常驻一个按钮），焦点走上来才显形——用遥控器按一次「上」
 * 就到，或者用遥控器的菜单键直接进设置（见 MainActivity）。两种都能走，是为了不至于
 * 在一台没有菜单键的遥控器上把设置彻底锁死。
 */
@Composable
private fun MenuEntry(
    onClick: () -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val alpha by animateFloatAsState(if (focused) 1f else 0f, label = "menuAlpha")

    Box(
        modifier = modifier
            // 焦点状态往上抛：↓ 的拦截在 WallScreen 根节点上做（见那里的注释）。
            .onFocusChanged { onFocusChanged(it.isFocused) }
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha))
            .border(
                2.dp,
                MaterialTheme.colorScheme.primary.copy(alpha = alpha),
                RoundedCornerShape(10.dp),
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 24.dp, vertical = 12.dp),
    ) {
        Text(
            text = stringResource(R.string.wall_settings),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
        )
    }
}

/** 分享人。只留名字——总数对看电视的人没用，反而把每组标题变成一行数字。 */
@Composable
private fun OwnerHeader(owner: String) {
    Column(modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)) {
        Text(
            text = owner,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

/**
 * 落焦点前等的时间。
 *
 * 首帧就 requestFocus 会抛「FocusRequester is not initialized」——那时卡片还没挂进组合。
 * 被 runCatching 吞掉后焦点就悬空，第一次按方向键会落到右上角设置按钮上。
 */
private const val FOCUS_SETTLE_MS = 80L

/**
 * 一本相册。
 *
 * 只有照片：不留白边、不描边框，文字直接压在图上——整面墙看起来是一片照片，
 * 而不是一格格卡片。张数（纯数字）在右上、名字在底部居中。
 *
 * 焦点放大走 [FocusFrame]：外框 1.05/220ms、封面图进 1.15·320ms 出 220ms（设置里可关）、内侧白边。文字和暗角放在
 * overlay 里，不跟封面一起放大——名字跟着放大会显得字在抖。
 */
@Composable
private fun AlbumCard(
    album: SharedAlbum,
    requestFactory: ImageRequestFactory,
    onOpen: () -> Unit,
    onFocused: () -> Unit,
    innerZoom: Boolean,
    ringColor: Color,
    scrolling: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    // 焦点落上来时上报给背景：背景组件自己有 300ms 防抖，这里只管如实报告。
    LaunchedEffect(focused) {
        if (focused) onFocused()
    }

    // 白字压在任意照片上都得看得清：加一层很薄的投影，比给文字加底色干净。
    val textShadow = Shadow(Color(0xCC000000), Offset(0f, 1f), 6f)

    FocusFrame(
        focused = focused,
        ringColor = ringColor,
        innerZoom = innerZoom,
        scrolling = scrolling,
        modifier = modifier
            .aspectRatio(1f)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onOpen,
            ),
        image = {
            CardImage(url = album.posterUrl, factory = requestFactory)
        },
        overlay = {
            // 顶部同理：张数常压在很亮的照片上（白帽子、白墙），纯白字会糊掉。
            // 一条 44dp 的淡暗角刚好够，不形成色块、也不是胶囊。
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(44.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0x8C000000), Color.Transparent),
                        ),
                    ),
            )

            // 名字那一条压一层很淡的暗角，只在最底部 56dp 内 gradient 到底，不形成色块。
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(56.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color(0xAA000000)),
                        ),
                    ),
            )

            Text(
                text = album.name,
                style = MaterialTheme.typography.titleSmall.copy(shadow = textShadow),
                color = Color.White,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
            )

            // 右上角只放照片张数。左上角不再挂「宝宝」角标——用户明确说不需要区分。
            // 只剩一段文字，也就不再需要按基线对齐的一行了。
            Text(
                // 只留数字，不带「张」——封面上少一个字就少一分噪。
                text = album.photoCount.toString(),
                style = MaterialTheme.typography.bodySmall.copy(shadow = textShadow),
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 12.dp, top = 10.dp),
            )
        },
    )
}

@Composable
private fun EmptyWall(loading: Boolean, onOpenSettings: () -> Unit) {
    // 空态也给一个可聚焦的「设置」入口：共享列表为空时右上角那个 MenuEntry 不存在，
    // 而有的遥控器没有菜单键——没有这一下，设置页就彻底进不去了。
    val settingsFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(FOCUS_SETTLE_MS)
        runCatching { settingsFocus.requestFocus() }
    }
    Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = if (loading) {
                    stringResource(R.string.wall_loading)
                } else {
                    stringResource(R.string.home_empty)
                },
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = stringResource(R.string.home_empty_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
            Spacer(Modifier.height(28.dp))
            MenuEntry(
                onClick = onOpenSettings,
                onFocusChanged = { },
                modifier = Modifier.focusRequester(settingsFocus),
            )
        }
    }
}
