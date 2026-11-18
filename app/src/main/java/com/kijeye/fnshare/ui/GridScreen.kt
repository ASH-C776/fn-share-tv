package com.kijeye.fnshare.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kijeye.fnshare.R
import com.kijeye.fnshare.data.fn.SharedAlbum
import com.kijeye.fnshare.data.fn.SharedPhoto
import com.kijeye.fnshare.engine.api.ImageRequestFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 距离网格末尾还剩几行就去取下一页。约两屏的余量——翻到底时下一页已经在路上了。 */
private const val LOAD_AHEAD_ROWS = 12

/** 落焦点前等一下：目标格要先进入组合，FocusRequester 才认得它。80ms 足够一帧多。 */
private const val FOCUS_SETTLE_MS = 80L

/**
 * 一个相册的照片网格。
 *
 * 照片和视频都排进来：视频缩略图上带一个 ▶ 角标，点开直接播。
 *
 * [gridState] 由上层持有。退回相册墙再进来、或者从全屏退回网格时，这个组件会被重建，
 * 状态留在里面的话滚动位置就没了——从第 300 张退回来看第 1 张，是这类应用最烦人的事。
 *
 * [focusIndex] 是进场后该落到第几张上：新进相册是 0，从全屏退回时是刚才看的那张。
 */
@Composable
fun GridScreen(
    state: AppState,
    requestFactory: ImageRequestFactory,
    album: SharedAlbum,
    gridState: LazyGridState,
    focusIndex: Int,
    onOpenPhoto: (Int) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val cellFocus = remember { FocusRequester() }
    var didFocus by remember { mutableStateOf(false) }

    // 焦点停在哪一张、它是不是「这一行的最后一张」。时间轴的右键溢出认这两个。
    var focusedIndex by remember { mutableIntStateOf(focusIndex) }
    var focusedAtRowEnd by remember { mutableStateOf(false) }

    /** 时间轴浮层是否显示。显示期间焦点在浮层里，网格收不到按键。 */
    var timelineVisible by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    // 按拍摄日把照片分组：每天前面插一行日期头。宝宝相册把日期头换成年龄（X岁X月X天）。
    val todayLabel = stringResource(R.string.day_today)
    val yesterdayLabel = stringResource(R.string.day_yesterday)
    val ageFormat = stringResource(R.string.baby_age_format)
    val rows = remember(
        // key 只认「列表形状」：续页只往尾上加，size + 末位 id 一变才重建行。
        // 拿整个 photos 当 key 的话每次续页都要逐项比较、全量重算一遍行分组。
        state.photos.size,
        state.photos.lastOrNull()?.id,
        album,
        todayLabel,
        yesterdayLabel,
        ageFormat,
    ) {
        buildGridRows(state.photos, album, todayLabel, yesterdayLabel, ageFormat)
    }
    val rowCount by rememberUpdatedState(rows.size)

    // 焦点描边颜色整页只解析一次。原先挂在 PhotoCell 调用处，每个可见卡片每次重组
    // 都跑一次 AndroidColor.parseColor + runCatching；滚动/焦点搬家时是几十次重复解析。
    val ringColor = remember(state.focusRingColorHex) { parseRingColor(state.focusRingColorHex) }

    // 时间轴：只在「列表形状」变化时重算，和 rows 用同一组 key。
    // 宝宝相册和普通相册的分组口径不同（岁/月龄 vs 年/月），album 也要进 key。
    val timeline = remember(state.photos.size, state.photos.lastOrNull()?.id, album) {
        buildTimeline(state.photos, album)
    }
    val anchorPhoto = state.photos.getOrNull(focusedIndex)

    // 时间轴的光标落点：普通相册是「年 / 月」，宝宝相册是「岁 / 月龄」。
    // 照片还没加载完时 anchorPhoto 为 null，这里给 null，面板落到最上面（最新）的一组。
    val anchorAge = if (album.isBabyAlbum) {
        anchorPhoto?.takenAt?.let { album.ageMonthsOn(it.toLocalDate()) }
    } else {
        null
    }

    // 整页是否正在滚动：纵向跨行时 LazyVerticalGrid 会平滑滚动，这段时间里非焦点卡的
    // 放大动画要冻结（见 FocusFrame 的 [scrolling]）。横向移动不滚动，这个一直是 false。
    var scrolling by remember { mutableStateOf(false) }
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.isScrollInProgress }.collect { scrolling = it }
    }

    // 剩不到两行就去取下一页，翻到底时不用等。
    //
    // 这里必须用 snapshotFlow 而不是 derivedStateOf：`remember { derivedStateOf { … size … } }`
    // 会把第一次组合时的 state 闭包整个记住，之后 photos.size 永远是 0，条件恒成立，
    // 于是一路把整个相册拉完。
    LaunchedEffect(Unit) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .collect { last ->
                if (last >= 0 && last >= rowCount - LOAD_AHEAD_ROWS) onLoadMore()
            }
    }

    // 焦点直接落到某一张缩略图上。只给容器加焦点是不行的——容器拿到焦点时画面上
    // 什么都没有变化，人要先盲按一下方向键才知道自己在哪。
    // 照片还没到时先等（相册刚打开、第一页还在路上），到了再放焦点。
    LaunchedEffect(focusIndex, state.photos.size) {
        if (didFocus || state.photos.isEmpty()) return@LaunchedEffect
        runCatching {
            // 照片在 rows 里的位置 +1（第 0 项是顶部相册标题条）就是它在网格里的条目下标。
            // 日期头也占条目，所以不能再简单用 focusIndex + 1。
            val rowIndex = rows.indexOfFirst { it is GridRowPic && it.photoIndex == focusIndex }
            val target = (if (rowIndex < 0) 0 else rowIndex + 1)
                .coerceAtMost(rows.size)
            val visible = gridState.layoutInfo.visibleItemsInfo.map { it.index }
            if (target !in visible) gridState.scrollToItem(target)
            focusedIndex = focusIndex
            focusedAtRowEnd = (rows.getOrNull(rowIndex) as? GridRowPic)?.isRowEnd ?: false
            // 目标项要先进组合，FocusRequester 才认得它。
            delay(FOCUS_SETTLE_MS)
            cellFocus.requestFocus()
            didFocus = true
        }.onFailure {
            // 目标格没进组合（极端情况）时退一步：容器自己拿焦点，方向键仍然能用。
            runCatching { focusRequester.requestFocus() }
        }
    }

    // 焦点背景：当前停着的那张缩略图 300ms 防抖后淡入成整屏模糊背景。
    // 记「缩略图 URL」而不是原图——缩略图本来就在网格里加载过，Coil 内存缓存直接命中。
    var focusUrl by remember { mutableStateOf<String?>(null) }

    /**
     * 把焦点要回来。目标格可能还没进组合（刚跳转完、或者面板刚收），
     * [FocusRequester.requestFocus] 这时候会抛异常，重试几次等它进场。
     */
    suspend fun restoreCellFocus() {
        // 次数给足：面板刚关的那一帧网格还是 canFocus = false（等重组），
        // 前两次请求必然失败，4 次（240ms）在慢一点的机器上会来不及。
        repeat(10) {
            delay(60)
            if (runCatching { cellFocus.requestFocus() }.isSuccess) return
        }
    }

    /**
     * 瞬间跳到时间轴选中的那一段。
     *
     * 位置优先按**照片 id**找，而不是按下标：照片按拍摄时间倒序排，NAS 上新加一张
     * 会插到最前面，所有下标全体位移一位——按下标跳会跳错人。按 id 找才稳。
     * id 找不到（那张照片被删了）再退回段首下标，最差跳错一张，而不是跳空。
     *
     * `scrollToItem` 没有动画——这就是需求里要的「硬切」：跨几百张的平滑滚动在电视上
     * 会走十几秒，中间全是它本来就加载过的图，纯浪费。
     *
     * 跳完立刻把 `focusedIndex` 挪过去，让 `cellFocus` 挂到新格子上，再补一次焦点。
     */
    fun jumpToBucket(bucket: TimelineBucket) {
        timelineVisible = false
        val byId = state.photos.indexOfFirst { it.id == bucket.firstPhotoId }
        val photoIndex = (if (byId >= 0) byId else bucket.firstIndex)
            .coerceIn(0, (state.photos.size - 1).coerceAtLeast(0))
        val rowIndex = rows.indexOfFirst { it is GridRowPic && it.photoIndex == photoIndex }
        if (rowIndex < 0) {
            scope.launch { restoreCellFocus() }
            return
        }
        scope.launch {
            gridState.scrollToItem((rowIndex + 1).coerceAtMost(rows.size))
            focusedIndex = photoIndex
            focusedAtRowEnd = (rows.getOrNull(rowIndex) as? GridRowPic)?.isRowEnd ?: false
            restoreCellFocus()
        }
    }

    /** 收起面板，焦点退回原来那张卡。 */
    fun closeTimeline() {
        timelineVisible = false
        scope.launch { restoreCellFocus() }
    }

    /**
     * 焦点是不是落在「第一排照片」上——它上方只剩日期头和相册名，两者都不参与焦点。
     *
     * 这一判断是「往回翻能不能看见相册名」的关键：焦点驱动的 bringIntoView 只做最小滚动，
     * 把目标行的**顶边**顶到视口上沿就停，上面那两行（日期头、相册名）就被推出屏幕外了。
     * 而焦点又上不去（它们不可获焦），所以不额外处理的话，相册名只有刚进相册那一次能看见。
     *
     * 注意不能拿 item 下标直接比：`rows` 里每个 `GridRow.Pic` 只代表**一张照片**
     * （网格是 6 列一格），第一排是 rows 里连续的最多 6 个 Pic，不是「第 0 个 Pic」。
     * 所以先往上退到本视觉行的行首（`column == 0` 那张），再看它前面还有没有照片。
     */
    fun atTopPhotoRow(): Boolean {
        val focusedItem = rows.indexOfFirst { it is GridRowPic && it.photoIndex == focusedIndex }
        if (focusedItem < 0) return false
        var rowStart = focusedItem
        while (rowStart > 0) {
            val row = rows[rowStart]
            if (row !is GridRowPic || row.column == 0) break
            rowStart--
        }
        return (0 until rowStart).none { rows[it] is GridRowPic }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // 设置里关掉就不组合 FocusBackdrop，省下整屏模糊的开销。
        if (state.backdropGrid) {
            FocusBackdrop(focusUrl, Modifier.fillMaxSize(), requestFactory)
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(GRID_COLUMNS),
            state = gridState,
            // 列数、页边距、间距都和相册墙一致：两边格子一样大，切换时不觉得换了版式。
            // 顶部 84dp 也和相册墙对齐——之前这里是 32dp，相册名几乎贴着屏幕上沿，
            // 1080p 上只隔 27dp，而屏幕外框还有一圈黑边，看着像被裁掉了半个字。
            contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 84.dp, bottom = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(focusRequester)
                // 时间轴开着的时候，网格整棵子树都不许拿焦点。
                // 光靠「面板去抢」不够：抢的瞬间有空窗，焦点可能先落在某个卡片上，
                // 那次按键就照着网格走了。这里直接把门关死。
                .focusProperties { canFocus = !timelineVisible }
                .focusable()
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when {
                        // 时间轴浮层开着的时候，网格一个键都不接。
                        //
                        // 面板自己会抢焦点（见 TimelineRail），正常情况下按键根本到不了这里。
                        // 但抢焦点是异步的：浮层刚出现的头几十毫秒里，焦点可能还在网格上，
                        // 这时按 OK 会直接把照片打开、按方向键会让网格滚走——浮层明明盖在上面，
                        // 底下却在动。这里做最后一道闸：浮层开着就全吞。
                        timelineVisible -> true
                        event.key == Key.Back -> {
                            onBack()
                            true
                        }
                        // 第一排照片再往上：焦点已经无处可去（相册名与日期头都不可获焦），
                        // 但页面还停在「这一排顶边贴着屏幕上沿」的位置，相册名在屏幕外。
                        // 这里把整页收回 offset 0，让它重新露出来——相册名是第 0 项，
                        // 只有 offset 0 时才会被顶部 84dp 的 contentPadding 顶到可见区。
                        // 必须自己处理：bringIntoView 只保证「目标行可见」，不会帮你把列表滚到头。
                        event.key == Key.DirectionUp && atTopPhotoRow() -> {
                            scope.launch { gridState.animateScrollToItem(0) }
                            true
                        }
                        // 一行排到头（满 6 列，或这一天就这几张）再往右 = 溢出，呼出时间轴。
                        // 行中间的右移不拦，照常交给焦点搜索。
                        // 不能只认 column == 5：相册里大量「一天一张」的日子，那张照片在
                        // 第 0 列，按右键会飞到右上角的「播放」上去，时间轴就永远够不着了。
                        event.key == Key.DirectionRight &&
                            focusedAtRowEnd &&
                            !timeline.isEmpty -> {
                            timelineVisible = true
                            true
                        }
                        else -> false
                    }
                },
        ) {
        item(span = { GridItemSpan(maxLineSpan) }, key = "header") {
            GridHeader(
                album = album,
                modifier = Modifier.padding(bottom = 14.dp),
            )
        }
            // 切进网格的那一帧清单还没读到：先摆两排灰格，别让人对着一片空白。
            // 0.24.5 起「按下 OK」和「等清单」彻底解耦，这一小段空窗才有必要画。
            if (state.photosLoading && state.photos.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "skeleton") {
                    GridSkeleton()
                }
            }
            items(
                items = rows,
                key = { row -> row.key },
                span = { row ->
                    if (row is GridRowDay) GridItemSpan(maxLineSpan) else GridItemSpan(1)
                },
            ) { row ->
                when (row) {
                    is GridRowDay -> DayHeaderCell(row.label)
                    is GridRowPic -> PhotoCell(
                        photo = row.photo,
                        requestFactory = requestFactory,
                        onOpen = { onOpenPhoto(row.photoIndex) },
                        onFocused = {
                            focusedIndex = row.photoIndex
                            focusedAtRowEnd = row.isRowEnd
                            focusUrl = row.photo.thumbUrl
                        },
                        innerZoom = state.focusZoom,
                        ringColor = ringColor,
                        scrolling = scrolling,
                        modifier = if (row.photoIndex == focusedIndex) {
                            Modifier.focusRequester(cellFocus)
                        } else {
                            Modifier
                        },
                    )
                }
            }
            if (state.photosLoading && state.photos.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "footer") {
                    Text(
                        text = stringResource(R.string.grid_loading),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
                    )
                }
            }
        }

        // 相册里全是视频（或本来就空）时，网格是一片空白，看起来像加载失败。
        // 真失败了就给一个「重试」——不然人只能退出去再进来碰运气。
        if (!state.photosLoading && state.photos.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(
                            if (state.photosFailed) R.string.grid_failed else R.string.grid_empty,
                        ),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        text = stringResource(
                            if (state.photosFailed) R.string.grid_failed_hint else R.string.grid_empty_hint,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    if (state.photosFailed) {
                        RetryButton(onRetry, Modifier.padding(top = 24.dp))
                    }
                }
            }
        }

        // 时间轴浮层压在最上面。它显示时焦点整个在它里面（单一焦点节点），
        // 网格收不到任何按键，所以不需要额外的「穿透拦截」。
        TimelineRail(
            timeline = timeline,
            anchorMajor = if (album.isBabyAlbum) anchorAge?.first else anchorPhoto?.takenAt?.year,
            anchorMinor = if (album.isBabyAlbum) anchorAge?.second else anchorPhoto?.takenAt?.monthValue,
            visible = timelineVisible,
            onJump = ::jumpToBucket,
            onClose = ::closeTimeline,
        )
    }
}

@Composable
private fun RetryButton(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val focusRequester = remember { FocusRequester() }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.05f else 1f, label = "retry")

    // 失败后焦点直接落在「重试」上，遥控器按一下就好，不用再找。
    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }

    Box(modifier = modifier.width(220.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .scale(scale)
                .clip(RoundedCornerShape(8.dp))
                .background(
                    if (focused) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
                )
                .border(
                    if (focused) 2.dp else 1.dp,
                    if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    RoundedCornerShape(8.dp),
                )
                .focusRequester(focusRequester)
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onRetry,
                )
                .padding(vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.grid_retry),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * 一张缩略图。
 *
 * 焦点必须是「亮边 + 放大」而不是换背景色：网格是一整片同色方块，光靠底色深浅，
 * 3 米外根本找不到自己停在哪一张上。
 *
 * 放大交给 [FocusFrame]：外框 1.05/220ms、内图进 1.15·320ms 出 220ms（设置里可关）、卡片内侧一圈白边，
 * 全部走绘制层，不动布局尺寸。视频角标和失败记号放在 overlay 里，不跟着内图放大。
 */
/**
 * 刚切进网格、清单第一条还没读到时摆的骨架灰格（0.24.5）。
 *
 * 只画两排、不进 `rows`、不可获焦、不可点：它的唯一职责是让「按下 OK」立刻看见格子
 * 形状，等首批真实照片一到就整块退场。做成整宽 item 而不是覆盖层，相册名不会被盖住，
 * 对齐也直接复用网格自己的 contentPadding。
 */
@Composable
private fun GridSkeleton() {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        repeat(2) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                repeat(GRID_COLUMNS) {
                    Box(
                        Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    )
                }
            }
        }
    }
}

@Composable
private fun PhotoCell(
    photo: SharedPhoto,
    requestFactory: ImageRequestFactory,
    onOpen: () -> Unit,
    onFocused: () -> Unit,
    innerZoom: Boolean,
    ringColor: Color,
    scrolling: Boolean = false,
    modifier: Modifier = Modifier,
) {
    // 焦点从 clickable 自己的交互源里读，而不是外面再挂一个 onFocusChanged：
    // 拿到焦点的正是 clickable 这个节点，从它的交互源取，两者不可能对不上。
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    // 焦点落上来时上报给背景：背景组件自己有 300ms 防抖，这里只管如实报告。
    LaunchedEffect(focused) {
        if (focused) onFocused()
    }

    // 加载失败时格子里留一个记号。不画的话就是一格纯灰，和「还没加载出来」长得一模一样，
    // 用户会一直等它出现，或者以为应用坏了。这里不写文字——3 米外看不清，一个折角就够。
    var failed by remember(photo.id) { mutableStateOf(false) }

    FocusFrame(
        focused = focused,
        innerZoom = innerZoom,
        ringColor = ringColor,
        scrolling = scrolling,
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onOpen,
            ),
        image = {
            CardImage(
                url = photo.thumbUrl,
                factory = requestFactory,
                onError = { failed = true },
                onSuccess = { failed = false },
            )
        },
        overlay = {
            if (photo.isVideo) {
                // 视频缩略图中心叠一个半透明圆 + 播放三角，3 米外也能一眼认出哪张是视频。
                Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(Color(0x99000000)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "▶",
                            color = Color.White,
                            fontSize = 22.sp,
                        )
                    }
                }
            }
            if (failed) {
                val brokenColor = MaterialTheme.colorScheme.outline
                Canvas(Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    val inset = w * 0.28f
                    drawLine(
                        color = brokenColor,
                        start = Offset(inset, inset),
                        end = Offset(w - inset, h - inset),
                        strokeWidth = w * 0.02f,
                    )
                    drawLine(
                        color = brokenColor,
                        start = Offset(w - inset, inset),
                        end = Offset(inset, h - inset),
                        strokeWidth = w * 0.02f,
                    )
                }
            }
        },
    )
}

@Composable
private fun GridHeader(
    album: SharedAlbum,
    modifier: Modifier = Modifier,
) {
    // 只有相册名。右上角原来挂着一个「播放」按钮（从第一张开始放幻灯片），已经去掉：
    // 幻灯片该从大图里的底部菜单进——那里本来就有「幻灯片」这一项，进去的人已经在
    // 看某一张了，从这一张接着放才是他要的；网格页那个按钮只能固定从第一张开始。
    Text(
        text = album.name,
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = modifier.fillMaxWidth(),
    )
}
