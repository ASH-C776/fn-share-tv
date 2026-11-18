package com.kijeye.fnshare.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.datasource.DataSource
import coil3.request.ImageRequest
import com.kijeye.fnshare.R
import com.kijeye.fnshare.data.FnSettings
import com.kijeye.fnshare.data.fn.SharedPhoto
import com.kijeye.fnshare.data.music.MusicEntry
import com.kijeye.fnshare.data.lyrics.LyricsDoc
import com.kijeye.fnshare.domain.model.MusicGroup
import com.kijeye.fnshare.domain.model.MusicSourceKind
import com.kijeye.fnshare.domain.model.hasFnSelection
import com.kijeye.fnshare.domain.model.isFnSelectionId
import com.kijeye.fnshare.domain.model.selectedFnGroupTitle
import com.kijeye.fnshare.engine.api.ImageRequestFactory
import com.kijeye.fnshare.engine.api.VideoEngine
import com.kijeye.fnshare.presentation.state.MusicCatalogActions
import com.kijeye.fnshare.presentation.state.MusicCatalogUi
import com.kijeye.fnshare.presentation.state.MusicMenuLabel
import com.kijeye.fnshare.presentation.state.buildMusicCatalogUi
import com.kijeye.fnshare.presentation.state.buildMusicMenuLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.util.Locale

/**
 * 全屏看一张照片或一段视频。键位按《浏览界面规范化方案 V2》执行。
 *
 * 照片：OK = 缩放三档（默认/1.7倍/2.5倍），默认档 ← → 翻页、↑ 呼出菜单，
 * 放大档四个方向键全部是平移画面；底部菜单（幻灯片/原图）↑ 呼出、2.5 秒自动收。
 * 视频：0.24.12 起仍是首帧门控开播——加载期「黑底 + 转圈」全程无声，
 * 00:00 首帧画上来才撤层并开播；超时只静音推进、不撤层，坏片源给错误提示。
 * 0.25.0 起按**来源**分两条路：网格直接点开的视频完全沿用上面这条；看图页内
 * ←/→ 翻到的视频先显示静止封面（不创建播放器、无声、无控制栏），按 OK 才挂播放器，
 * 封面一直盖到首帧真到才整块撤掉——全程至少有一层满不透明内容，不黑帧。
 * 加载 / 缓冲算 LOADING（不亮状态牌和进度栏），OK 暂停/继续，长按 450ms 四倍速，
 * 左右键播放中 ±5 秒，控制栏 2.5 秒自动收（暂停/播完常驻）。
 *
 * 左上角信息框（拍摄日期 / 拍摄地点 / 第几张）走遥控器的 MENU 键开关，进图默认收起——
 * 照片本身就是内容，常驻一块黑牌子只是抢地方。键位在 Activity 那一层收，见 [MainActivity]。
 * 画面正下方固定显示「为什么画面是这样」的一行字（缩放档位 / 视频状态），不再给顶栏让位。
 *
 * 返回键先收浮层，收干净了两段确认退出。
 */
/**
 * 看图页一次组合所需的**内容快照**。和 [ViewerActions] 一起把 40 多个散参数收拢成两个：
 * MainActivity 那侧不再一行一个地传，改一个功能不用同时动三处签名。
 */
@Immutable
data class ViewerContent(
    val photos: List<SharedPhoto>,
    val index: Int,
    val slideshow: Boolean,
    val slideshowShuffled: Boolean,
    val slideSeconds: Int,
    val slideFade: Boolean,
    val slideZoom: Boolean,
    /** 幻灯片取景：true = 满屏（Crop 裁边），false = 原比例（Fit 完整显示）。 */
    val slideFill: Boolean,
    val loopSlideshow: Boolean,
    val hasMore: Boolean,
    val previewOriginal: Boolean,
    /** 随机模式预挑好的下一张（ViewModel 已放进 state），预取把它的解码提前做掉。 */
    val shuffleNext: Int?,
    val infoVisible: Boolean,
    /**
     * 全屏图的请求工厂（结构化重构第 6 批）。
     *
     * 原来这里传的是 `ImageLoader`，UI 自己拼 `ImageRequest`；现在 UI 只调工厂的
     * `photoUrl()` / `photoRequest()`，拿不回加载器，也不再决定解码尺寸和精度。
     * 工厂由 `MainActivity.App()` 按当前屏幕像素从 `ImageEngine` 产出，viewport
     * 变了才重新产一个——`ImageRequest` 的 model 相等性必须保住，否则会重新触发
     * 0.17.1 修过的「换请求 → 叠化失效」。
     */
    val requestFactory: ImageRequestFactory,
    /** 把预取请求投进引擎队列。原 `imageLoader.enqueue(it)`。 */
    val enqueueImage: (ImageRequest) -> Unit,
    val dataSourceFactory: DataSource.Factory,
    /**
     * 0.24.12 M-6：视频引擎工厂。由 `di/AppContainer` 经 ViewModel 注入，UI 不再
     * `remember { ExoVideoEngine(context) }`——那样引擎换不了假实现，播放时序只能靠真机验。
     */
    val videoEngineFactory: () -> VideoEngine,
    /**
     * 0.24.12 M-5：ViewModel 侧「离开看图页」的信号（退出 / 退回相册墙 / 闲置到点）。
     * 旧屏淡出期间 AnimatedContent 不会用新参数重组，只有组合内自己订阅的 Flow 还能收到，
     * 所以走 Flow 而不是 ViewerContent 的普通字段。
     */
    val viewerLeaveSignal: StateFlow<Long>,
    // 背景音乐
    val musicEntries: List<MusicEntry>,
    val musicSelected: Set<String>,
    /** 背景乐顺序：true = 随机，false = 顺序。音乐弹窗的「播放顺序」行据此显示。 */
    val musicShuffled: Boolean,
    val musicScanning: Boolean,
    val musicHasPermission: Boolean,
    val musicPlaying: Boolean,
    /** 这一轮里有曲子解不开（格式不支持），播放器已跳过。 */
    val musicFailed: Boolean,
    val musicPermissionName: String,
    // 歌词
    /** 背景乐歌词开关：开 = 有歌词文件的曲目在右侧显示两列竖排歌词。 */
    val lyricsEnabled: Boolean,
    /** 当前曲目的歌词文档；没词或还没解析出来时 null（浮层整体不挂载）。 */
    val lyricsDoc: LyricsDoc?,
    /** 当前唱到第几句；前奏里或没词时 -1。 */
    val lyricIndex: Int,
    // --- 双源曲库（P4） ---
    /** 当前曲库来源。 */
    val musicSourceKind: MusicSourceKind,
    /** 飞牛子账户是否已登录。 */
    val fnMusicLoggedIn: Boolean,
    /** 飞牛账号名（设置页显示用）。 */
    val fnMusicAccount: String,
    /** 飞牛第一级：歌单列表。 */
    val fnMusicGroups: List<MusicGroup>,
    /** 飞牛第二级：当前歌单内曲目。 */
    val fnMusicTracks: List<MusicEntry>,
    /** 非空 = 飞牛曲目步。 */
    val fnMusicCurrentGroupId: String?,
    val fnMusicCurrentGroupTitle: String,
    val fnMusicLoading: Boolean,
    val fnMusicError: String?,
)

/** 看图页的**动作回调**集合。全部是一次性转发到 ViewModel 的函数引用。 */
@Immutable
data class ViewerActions(
    val onStep: (Int) -> Unit,
    /** 幻灯片自动前进。和手动的 [onStep] 分开：自动要跳过视频，手动必须能翻到视频。 */
    val onAutoStep: () -> Unit,
    /**
     * 幻灯片播放中按 ←/→ 切歌。返回 false = 没有可切的曲子，按键退回翻页。
     * 切歌**不影响**幻灯片：不翻照片、不重置计时。
     */
    val onSkipMusic: (Int) -> Boolean,
    val onSetSlideshow: (Boolean) -> Unit,
    /** 幻灯片「暂停/继续」：同步背景乐一起停/一起放（见 [com.kijeye.fnshare.ui.AppViewModel.setSlideshowPaused]）。 */
    val onSlideshowPaused: (Boolean) -> Unit,
    val onSlideSeconds: (Int) -> Unit,
    val onSlideFade: (Boolean) -> Unit,
    val onSlideZoom: (Boolean) -> Unit,
    val onSlideFill: (Boolean) -> Unit,
    val onShuffle: (Boolean) -> Unit,
    val onLoop: (Boolean) -> Unit,
    val onPreviewOriginal: (Boolean) -> Unit,
    val onToggleInfo: () -> Unit,
    val onExit: () -> Unit,
    /** 切换曲库来源（二级菜单 / 看图页菜单 ←/→）。未登录时由 ViewModel 拒绝。 */
    val onMusicSourceChanged: (MusicSourceKind) -> Unit,
    /** 歌单行按 OK：勾选 / 取消勾选这张歌单。 */
    val onMusicSourceToggleGroup: (String) -> Unit,
    /** 歌单行按 →：进曲目步。 */
    val onMusicSourceOpenGroup: (String) -> Unit,
    /** 曲目步按 ←/Back：退回歌单列表。 */
    val onMusicSourceBackToGroups: () -> Unit,
    /** 飞牛加载/拉取失败提示的关闭。 */
    val onMusicSourceDismissError: () -> Unit,
    val onToggleMusic: (String) -> Unit,
    /** 一键全选 / 取消全选（只对当前曲库里存在的曲目动手）。 */
    val onToggleMusicAll: () -> Unit,
    /** 切换背景乐「顺序 / 随机」。 */
    val onSetMusicShuffle: (Boolean) -> Unit,
    /** 切换背景乐歌词显示开关。 */
    val onSetLyrics: (Boolean) -> Unit,
    val onRefreshMusic: () -> Unit,
    val onMusicPermissionResult: (Boolean) -> Unit,
    val onVideoPlayingChanged: (Boolean) -> Unit,
    val onVideoEnded: () -> Unit,
)

/**
 * 由看图页快照 [ViewerContent] 构造音乐弹窗 UI 态。折叠逻辑与 [AppState.toMusicCatalogUi]
 * 共用 [buildMusicCatalogUi]（避免两处各自写一遍后漂移）；[ViewerContent] 是 [AppState]
 * 的扁平快照，已含构成 `MusicCatalogUi` 所需的全部字段，故不经由 [AppState]。
 */
fun ViewerContent.toMusicCatalogUi(): MusicCatalogUi = buildMusicCatalogUi(
    sourceKind = musicSourceKind,
    fnLoggedIn = fnMusicLoggedIn,
    fnAccount = fnMusicAccount,
    fnMusicGroups = fnMusicGroups,
    fnMusicTracks = fnMusicTracks,
    fnMusicCurrentGroupId = fnMusicCurrentGroupId,
    fnMusicCurrentGroupTitle = fnMusicCurrentGroupTitle,
    fnMusicLoading = fnMusicLoading,
    fnMusicError = fnMusicError,
    localEntries = musicEntries,
    selected = musicSelected,
    musicShuffled = musicShuffled,
    lyricsEnabled = lyricsEnabled,
    scanning = musicScanning,
    hasPermission = musicHasPermission,
)

@Composable
fun ViewerScreen(
    content: ViewerContent,
    actions: ViewerActions,
) {
    // 参数对象摊平成本地量：函数体继续按原来的名字写，不用整篇改写引用点。
    val photos = content.photos
    val index = content.index
    val slideshow = content.slideshow
    val slideshowShuffled = content.slideshowShuffled
    val slideSeconds = content.slideSeconds
    val slideFade = content.slideFade
    val slideZoom = content.slideZoom
    val slideFill = content.slideFill
    val loopSlideshow = content.loopSlideshow
    val hasMore = content.hasMore
    val previewOriginal = content.previewOriginal
    val shuffleNext = content.shuffleNext
    val infoVisible = content.infoVisible
    val requestFactory = content.requestFactory
    val enqueueImage = content.enqueueImage
    val dataSourceFactory = content.dataSourceFactory
    val videoEngineFactory = content.videoEngineFactory
    val viewerLeaveSignal = content.viewerLeaveSignal
    val musicEntries = content.musicEntries
    val musicSelected = content.musicSelected
    val musicShuffled = content.musicShuffled
    val musicScanning = content.musicScanning
    val musicHasPermission = content.musicHasPermission
    val musicPlaying = content.musicPlaying
    val musicFailed = content.musicFailed
    val musicPermissionName = content.musicPermissionName
    val lyricsEnabled = content.lyricsEnabled
    val lyricsDoc = content.lyricsDoc
    val lyricIndex = content.lyricIndex
    val musicSourceKind = content.musicSourceKind
    val fnMusicLoggedIn = content.fnMusicLoggedIn
    val fnMusicGroups = content.fnMusicGroups
    val fnMusicCurrentGroupId = content.fnMusicCurrentGroupId
    val fnMusicCurrentGroupTitle = content.fnMusicCurrentGroupTitle
    val fnMusicLoading = content.fnMusicLoading
    val fnMusicError = content.fnMusicError

    val onStep = actions.onStep
    val onAutoStep = actions.onAutoStep
    val onSkipMusic = actions.onSkipMusic
    val onSetSlideshow = actions.onSetSlideshow
    val onSlideshowPaused = actions.onSlideshowPaused
    val onSlideSeconds = actions.onSlideSeconds
    val onSlideFade = actions.onSlideFade
    val onSlideZoom = actions.onSlideZoom
    val onSlideFill = actions.onSlideFill
    val onShuffle = actions.onShuffle
    val onLoop = actions.onLoop
    val onPreviewOriginal = actions.onPreviewOriginal
    val onToggleInfo = actions.onToggleInfo
    val onExit = actions.onExit
    val onMusicSourceChanged = actions.onMusicSourceChanged
    val onMusicSourceToggleGroup = actions.onMusicSourceToggleGroup
    val onMusicSourceOpenGroup = actions.onMusicSourceOpenGroup
    val onMusicSourceBackToGroups = actions.onMusicSourceBackToGroups
    val onMusicSourceDismissError = actions.onMusicSourceDismissError
    val onToggleMusic = actions.onToggleMusic
    val onToggleMusicAll = actions.onToggleMusicAll
    val onSetMusicShuffle = actions.onSetMusicShuffle
    val onSetLyrics = actions.onSetLyrics
    val onRefreshMusic = actions.onRefreshMusic
    val onMusicPermissionResult = actions.onMusicPermissionResult
    val onVideoPlayingChanged = actions.onVideoPlayingChanged
    val onVideoEnded = actions.onVideoEnded

    val photo = photos.getOrNull(index)
    val video = photo?.takeIf { it.isVideo && !it.videoUrl.isNullOrBlank() }

    // ── 0.25.0：视频按「进屏来源」分两条路 ─────────────────────────────────
    // AnimatedContent 的 contentKey = 屏类型：网格 → 看图会重建本组合，页内翻页原地复用。
    // 所以「进屏那一刻的 id」是稳定的；之后只要 id 变过一次，就永远算「页内翻到的」。
    // 判定必须在组合期同步完成——放 LaunchedEffect 会晚一帧，那一帧舞台被拆掉就是黑一下。
    // （写同一个 true 不会触发重组，navigated 是一次性 latch。）
    val entryPhotoId = remember { photo?.id }
    val navigated = remember { mutableStateOf(false) }
    if (photo?.id != entryPhotoId) navigated.value = true

    /** 网格直接点开的视频没有封面，走原来的黑底转圈；页内翻到的视频显示静止封面。 */
    val coverOrigin = video != null && navigated.value

    /**
     * 0.25.1：起播授权属于「本次停留」，不是「这一条 id」。
     * remember 的 key 是 photo.id：翻走再翻回同一条视频会重建 state 回到未授权，
     * 否则播过一次以后每次翻回来都会自动续播（0.25.0 在 MuMu 上实测到的越界行为）。
     * 初值只有网格直接点视频时才给，页内翻到的一律静止；失效在组合期同步完成，
     * 不用 LaunchedEffect 事后归零——晚一帧就会把舞台拆掉，露出黑一下。
     */
    val armedState = remember(photo?.id) {
        mutableStateOf(videoEntryArmed(video != null, navigated.value))
    }
    val armed = videoIsArmed(armedState.value, video != null)

    // 音乐弹窗的读权限申请：结果回写 ViewModel（拿到就立刻扫一次曲库）。
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> onMusicPermissionResult(granted) }
    // 背景音乐弹窗开关：从二级菜单的「音乐」项打开，返回键关掉。
    var musicDialogOpen by remember { mutableStateOf(false) }
    // 打开音乐弹窗且还没权限时，自动弹一次授权；用户拒绝后可在弹窗里再点「去授权」。
    LaunchedEffect(musicDialogOpen) {
        if (musicDialogOpen && !musicHasPermission) {
            permissionLauncher.launch(musicPermissionName)
        }
    }
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    // 屏幕尺寸在组合期算好：事件回调里不能调 composable API。平移步长不在这里定，
    // 它按当前放大档的可用行程现算（见 [zoomPanStep]），不同分辨率的电视手感才一致。
    val screenWpx = with(density) { configuration.screenWidthDp.dp.toPx() }
    val screenHpx = with(density) { configuration.screenHeightDp.dp.toPx() }
    val zoomLabels = ZOOM_LABEL_RES.map { stringResource(it) }

    // 幻灯片参数：跟着「时长」档位走，再按两个开关剪一次。3/5/8 三档各自的放大比例
    // 与动画总长见 [SlideShowConfig]；关掉叠化时 animDuration 会一并缩回展示时长。
    val slideCfg = slideShowConfig(slideSeconds).withSwitches(fade = slideFade, zoom = slideZoom)

    // 上一张显示过的是什么。必须在组合期定下来，不能照着 `photos[index - 1]` 猜：
    // 幻灯片会**跳过视频**，数组里的前一个经常是视频，可实际上一张画的是更早的那张照片。
    // 靠数组邻居判断会把正常的两张照片当成「视频 → 照片」，白白清场变成硬切。
    val prevPhotoHolder = remember { mutableStateOf<SharedPhoto?>(null) }
    val previousPhoto = remember(photo?.id) {
        prevPhotoHolder.value?.takeIf { it.id != photo?.id }
    }
    SideEffect { prevPhotoHolder.value = photo }

    /**
     * 全屏图的请求（结构化重构第 6 批）。显示和预加载都必须走这一个函数。
     *
     * 该代码是从本文件原 278-333 行提取的：函数体已**逐字**搬进
     * `engine/impl/CoilImageRequestFactory`——`.size()` 解码框乘 `KB_DECODE_SLACK`
     * 与 `ORIGINAL_DECODE_SCALE`、`Precision.INEXACT`、`crossfade(false)`、把真实磁盘
     * 字节数写进 `ImageSizes.map` 的 listener，一个参数都没改。这里只留同名同签名的
     * 委托；**默认参数 `original = previewOriginal` 必须保留**，`PhotoStage` 的
     * `request` / `previewRequest` 两个 lambda 靠它区分主层与恒用预览档的垫底层。
     *
     * 缓存 key 不变：工厂按 `MainActivity.App()` 用同一套 `LocalConfiguration` +
     * `LocalDensity` 算出的屏幕像素构造，显示和预取仍共用这一个函数。
     *
     * 对应的生命周期清理应该在原文件的哪个位置调用：
     * 【无需清理】。工厂与 `ImageRequest` 不持有协程、监听器注册或文件句柄；
     * ImageLoader 归 `ImageEngine`（进程级单例，按第 5 批决定不 `close()`），
     * `MainActivity.onDestroy()` / `AppViewModel.onCleared()` 都不为此新增调用。
     */
    /** 当前该显示哪张 URL：受「原图」开关控制——关时 mUrl(1080p 预览)，开时 originalUrl。 */
    fun photoUrl(target: SharedPhoto?, original: Boolean = previewOriginal): String? =
        requestFactory.photoUrl(target, original)

    fun photoRequest(target: SharedPhoto?, original: Boolean = previewOriginal): ImageRequest? =
        requestFactory.photoRequest(target, original)

    // ── 浮层状态 ──────────────────────────────────────────────────────────
    // 左上信息框的开关在 AppState 里（MENU 键只有 Activity 那层收得到），这里只读 infoVisible。
    var menuLevel by remember { mutableStateOf(0) }            // 照片菜单：0 关 1 按钮行 2 二级设置
    var menuCursor by remember { mutableStateOf(0) }           // 一级按钮游标
    var panelCursor by remember { mutableStateOf(0) }          // 二级菜单游标
    // 菜单自动收的计时起点。任何菜单内按键都推一下；幻灯片自动翻页不算操作、不重置。
    var menuActivity by remember { mutableIntStateOf(0) }
    // 当前该画哪一层，只在「开到某一级」时更新。退场淡出的 200ms 里 menuLevel 已经归零，
    // 若按 menuLevel 分支画，0 会落到「不是一级」那一支 → 收起一级时闪一下二级面板。
    var menuShown by remember { mutableIntStateOf(1) }

    // ── 照片缩放 ─────────────────────────────────────────────────────────
    var zoomIndex by remember { mutableStateOf(0) }            // 0 默认 1 一倍七 2 二倍五
    var pan by remember { mutableStateOf(Offset.Zero) }
    var zoomHint by remember { mutableStateOf<String?>(null) }
    // 幻灯片播放中的「暂停/继续」：纯本地状态，只拦自动翻页、不动全局 slideshow 开关。
    // 这样菜单里的「幻灯片」仍显示开启、←/→ 手动翻页在暂停时也能用，恢复后从当前张继续。
    var slideshowPaused by remember { mutableStateOf(false) }

    /**
     * 0.26.2：记下「人手按 ←/→ 要翻到哪一格」，组合期据此判断这一格是不是手动翻来的。
     *
     * 幻灯片自己的自动前进只改 index、不写这里，下一格自然回到「不是人手翻的」。
     * 这样才分得开两件事：幻灯片自己落在视频上（ViewModel 的 autoStep 本来会跳过，
     * 这里只留兜底）和**人手动翻到一条视频**——后者这一格归人，静止封面等着、
     * 按 OK 能完整播，播放 / 暂停 / 播完都不许把人带走。
     *
     * 只在按键回调里写，组合期只读；不进视频封面 / 舞台的判定链，晚一帧也不会黑屏。
     */
    val manualStepTarget = remember { mutableStateOf<Int?>(null) }
    val manualArrival = manualStepTarget.value == index

    // ── 视频状态 ─────────────────────────────────────────────────────────
    // 0.24.12 m-3：播放中每 250ms 都会写一次状态。顶层若用 mutableStateOf 接它，
    // 一千五百行的 ViewerScreen 每秒要重组 4 次（菜单、缩放、歌词全跟着跑）。
    // 现在完整状态放进 Flow，只有 VideoControlBar 内部 collect 进度；顶层只订阅
    // 三个布尔标志且 distinctUntilChanged，位置变化不再惊动整个看图页。
    val videoStateFlow = remember { MutableStateFlow(VideoUiState()) }
    val videoFlagsFlow = remember(videoStateFlow) {
        videoStateFlow
            .map { VideoFlags(it.isPlaying, it.ended, it.buffering) }
            .distinctUntilChanged()
    }
    val videoFlags by videoFlagsFlow.collectAsState(initial = VideoFlags())
    var fast by remember { mutableStateOf(false) }             // 长按快进中
    var controlsVisible by remember { mutableStateOf(true) }   // 底部控制栏
    val videoController = remember { mutableStateOf<VideoController?>(null) }
    var holdJob by remember { mutableStateOf<Job?>(null) }
    /** 0.24.12 M-2：非空 = 这段视频取流 / 解码失败，画面交给错误提示。 */
    var videoError by remember { mutableStateOf<String?>(null) }

    // 0.24.12 M-4：门闩按「播放器实例」记，不按 URL 记。
    // key 里同时放 photo?.id：视频 A → 照片 → 视频 A 时 URL 没变，但中间 photoId
    // 变过，remember 一定失效 → beginSession() 同步推进 token → 重新画转圈。
    // 复位与播放器重挂在同一处同步完成，绝不用 SideEffect 事后比对（0.23.3 教训）。
    val videoLoadingState = remember { VideoLoadingState() }
    val videoKeyNow = video?.videoUrl
    val sessionToken = remember(videoKeyNow, photo?.id) { videoLoadingState.beginSession() }
    val videoLoading = videoKeyNow != null && videoLoadingState.loadingVisible(sessionToken)

    // 0.25.0：封面只在「页内翻到的视频」上出现，且一直盖到首帧真到（报错也不撤）。
    // 网格直接点开的视频 showCover 恒 false，播放链路与 0.24.15 逐字一致。
    val showCover = videoCoverVisible(
        coverOrigin = coverOrigin,
        armed = armed,
        firstFrameReady = !videoLoading,
        error = videoError != null,
    )
    // 舞台必须占住同一个组合位置：照片 ↔ 视频封面换的是槽里的图，不是拆掉重建一层
    // AsyncImage——后者哪怕内存缓存命中也要等一个异步回调，那一帧就是黑的。
    // 这也意味着邻居预取早已覆盖视频封面（photoRequest 不区分视频），切过去直接命中。
    val stageVisible = video == null || showCover

    // 0.24.12 M-3：超时只静音强制推进播放逼出首帧，**不撤层**；首帧到了才恢复音量 + 撤层。
    // 再等 5 秒仍没首帧就判定片源失败，撤层交给错误提示，转圈不再无限挂。
    // 0.25.0：静止态根本没挂播放器，连计时都不起跑，否则 8 秒后会误报 TIMEOUT。
    val latestVideoError by rememberUpdatedState(videoError)
    LaunchedEffect(sessionToken, armed) {
        if (videoKeyNow == null || !videoTimeoutEnabled(armed)) return@LaunchedEffect
        delay(VIDEO_LOADING_TIMEOUT_MS)
        if (videoLoadingState.onForceStart(sessionToken)) {
            videoController.value?.forceStart()
        }
        delay(VIDEO_FORCE_GIVE_UP_MS)
        if (videoLoadingState.loadingVisible(sessionToken)) {
            videoLoadingState.onGiveUp(sessionToken)
            if (latestVideoError == null) videoError = "TIMEOUT"
        }
    }

    // ── 两段退出 ─────────────────────────────────────────────────────────
    var exitArmedAt by remember { mutableStateOf(0L) }
    var exitHintVisible by remember { mutableStateOf(false) }

    // 确认键连发抑制。
    var lastConfirmDownMs by remember { mutableStateOf(0L) }
    var confirmSwallowed by remember { mutableStateOf(false) }

    // 0.24.12 M-7：补 LOADING。加载期（首帧没到）和缓冲期都算，不再被默认判成
    // PAUSED 而强制亮控制栏；缓冲卡死时也不会被乐观上报骗成「播放中」让闲置保护永不退出。
    // 0.25.0：未获起播授权 = STILL，先于 LOADING 判定——静止态没有播放器，
    // 既不亮控制栏、不占闲置保护，也不会被「还没首帧」误判成正在加载。
    val phase = when {
        video == null -> null
        !armed -> VideoPhase.STILL
        videoLoading || videoFlags.buffering -> VideoPhase.LOADING
        videoFlags.ended -> VideoPhase.ENDED
        fast -> VideoPhase.FAST
        videoFlags.isPlaying -> VideoPhase.PLAYING
        else -> VideoPhase.PAUSED
    }

    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }

    // 换一张/一段：缩放和视频态都归位。菜单保持原样——呼出菜单不暂停走片；
    // 但轮到视频时菜单必须收（视频没有菜单，留着只会无声地吃掉按键）。
    // 视频互切也要清播放器状态：不清的话上个视频的 ended=true 会让新视频直接被判成播完。
    LaunchedEffect(photo?.id) {
        zoomIndex = 0
        pan = Offset.Zero
        zoomHint = null
        videoStateFlow.value = VideoUiState()
        videoError = null
        fast = false
        controlsVisible = true
        if (video == null) {
            videoController.value = null
            onVideoPlayingChanged(false)
        } else {
            menuLevel = 0
        }
    }

    // 「播放中」要不要通知闲置保护。加载/暂停/播完不算忙——人没在看就走闲置逻辑。
    LaunchedEffect(phase) {
        onVideoPlayingChanged(phase == VideoPhase.PLAYING || phase == VideoPhase.FAST)
        // 暂停、播完时强制亮出控制栏：它就是「画面为什么不动」的唯一解释。
        // LOADING 不在这儿管，控制栏 / 状态牌的显示条件里都排除了它。
        if (phase == VideoPhase.PAUSED || phase == VideoPhase.ENDED) {
            controlsVisible = true
        }
    }

    // 播完：幻灯片里续下一张，手动看的停在播完态等操作（onVideoEnded 自己判断）。
    // 0.26.2：人手翻到的视频说好了「这一格归人」，播完也不交给幻灯片带走，
    // 停在「播完」牌上等用户自己翻走；网格直接点开的视频保持原行为。
    LaunchedEffect(phase, manualArrival) {
        if (phase == VideoPhase.ENDED && videoEndedAdvancesSlideshow(manualArrival)) {
            onVideoEnded()
        }
    }

    // Ken Burns 和叠化都搬进了 [PhotoStage]：那边两个图层槽常驻，换图只动看不见的那个槽，
    // 才做得到「旧图一帧都不空」。

    // 幻灯片：只放照片，视频整段跳过；但「人手翻到的视频」这一格归人。
    //
    // 原来这里是 delay(slideSeconds) 然后 onStep(1)。改两处：
    //   1. 时长取 displayDuration（独占期），到点由 onAutoStep 找下一张**图片**；
    //   2. 自动前进撞到视频就跳过——ViewModel 的 autoStep 本来就只落在照片上，
    //      这里留的是兜底（SKIP_VIDEO）。
    // 0.26.2 补第三条：人手按 ←/→ 翻到的视频，整段停住（HOLD）。以前那一支也会
    // 立刻 onAutoStep()，于是静止封面一闪就没、按 OK 刚起播就被带走。
    // 全册都没有图片时由 ViewModel 关掉幻灯片，这里不会陷进死循环。
    // 幻灯片被关掉（菜单里关、或退出看图）时清掉暂停标记，避免下次再开时卡在「暂停」。
    LaunchedEffect(slideshow) {
        if (!slideshow) slideshowPaused = false
    }

    LaunchedEffect(slideshow, slideshowPaused, index, photo?.id, slideCfg, manualArrival) {
        when (
            slideshowAdvanceMode(
                slideshow = slideshow,
                paused = slideshowPaused,
                isVideo = video != null,
                manualArrival = manualArrival,
            )
        ) {
            // 人手翻到的视频：静止、起播、播放、暂停、播完都停在这一格。
            // phase 不在本 effect 的 key 里，播放器状态变化不会把计时重新点着。
            SlideshowAdvanceMode.HOLD -> Unit
            // 兜底：不是人手翻来的视频（理论上 autoStep 不会落到这儿），照旧立刻跳走。
            SlideshowAdvanceMode.SKIP_VIDEO -> onAutoStep()
            SlideshowAdvanceMode.DELAY_THEN_ADVANCE -> {
                // 计时挪到 Default 线程。原来在主线程 delay：主线程一旦被解码回调、重组或者
                // GPU 上传占住，协程恢复就得排队——展示时长被拖长几十到几百毫秒，而且每张拖得
                // 不一样长，看就是「动画时长不稳定」。Default 线程上到点即发，state 是线程安全的
                // StateFlow，主线程下一帧自然就拿到了。
                withContext(Dispatchers.Default) { delay(slideCfg.displayDuration.toLong()) }
                onAutoStep()
            }
        }
    }

    // 幻灯片「暂停/继续」：把本地 paused 标记同步给 ViewModel，让它一起 pause/resume 背景乐——
    // 否则画面停了声音还走（之前的 bug）。只在幻灯片开着时通知：关掉幻灯片时 paused 会被归零，
    // 但那时音乐也已停掉，无需再通知。
    LaunchedEffect(slideshowPaused) {
        if (slideshow) onSlideshowPaused(slideshowPaused)
    }

    // 照片一级菜单 2.5 秒自动收（二级面板打开时不收——人正在设置）。
    // 幻灯片自动翻页会变 index，但不碰 menuActivity。
    LaunchedEffect(menuLevel, menuActivity) {
        if (menuLevel == 1) {
            delay(AUTO_HIDE_MS)
            menuLevel = 0
        }
    }

    // 视频控制栏：只有播放中才自动收。快进中按着键，不收。
    LaunchedEffect(controlsVisible, phase) {
        if (controlsVisible && phase == VideoPhase.PLAYING) {
            delay(AUTO_HIDE_MS)
            controlsVisible = false
        }
    }

    // 缩放档位提示 1 秒消失。
    LaunchedEffect(zoomHint) {
        if (zoomHint != null) {
            delay(ZOOM_HINT_MS)
            zoomHint = null
        }
    }

    // 两段退出提示 1.5 秒后自己消失。
    LaunchedEffect(exitHintVisible) {
        if (exitHintVisible) {
            delay(EXIT_WINDOW_MS)
            exitHintVisible = false
        }
    }

    // 预加载：看第 N 张时把 N+1、N-1 先解码进内存，翻页时直接命中；随机模式再加预挑好的
    // 那一张（随机跳页的目标图不在邻居范围里，不预取就得干等解码）。
    // 先 N+1（人多数往后翻），再 N-1。窗口只留一张开外——1080p 一张约 8MB，
    // 预取太多会被 Coil 的内存缓存按 LRU 挤掉，等于白解码。
    // 请求走 [photoRequest]，和显示端共用同一条 key，否则缓存永远不命中。
    LaunchedEffect(photo?.id, index, previewOriginal, screenWpx, screenHpx, shuffleNext, slideshow) {
        if (photo == null) return@LaunchedEffect
        // 先让开当前这一轮叠化：转场那 0.8~1 秒里主线程要跑动画、GPU 要合成两层 1080p，
        // 这时候再并发解下一张，掉的就是**这一轮**的帧——正是肉眼看到的「跳帧」。
        // 电视那颗 CPU 上，解码一张 1080p 要几十到上百毫秒，和渲染是同一份算力。
        withContext(Dispatchers.Default) { delay(PREFETCH_DELAY_MS) }
        // 幻灯片只预取「下一张」。原来连 N-1 一起取，多一次解码换来的只是「往回翻一张」
        // ——幻灯片永远往前走，用不到。手动看图才需要左右各一张。
        val targets = if (slideshow) {
            // 0.26.0：手动翻页固定按相册顺序 ±1，不再吃随机预挑的结果，
            // 所以随机表下一张和 index+1 都要提前解码；否则人手翻一张反而干等解码。
            listOfNotNull(shuffleNext, index + 1).distinct()
        } else {
            listOfNotNull(index + 1, index - 1)
        }
        targets
            .mapNotNull { idx -> photos.getOrNull(idx) }
            .distinctBy { it.id }
            .forEach { neighbour -> photoRequest(neighbour)?.let { enqueueImage(it) } }
    }

    fun shortPressOk() {
        if (video != null) {
            // 0.25.0：静止封面按 OK = 授予起播授权，这一刻才创建播放器。
            // 封面不撤，无背景转圈叠在封面上；首帧真到了才整块撤掉，不黑帧、不先出声。
            if (phase == VideoPhase.STILL) {
                armedState.value = videoArmAfterConfirm(armedState.value, isVideo = true)
                controlsVisible = true
                return
            }
            // 加载期 / 缓冲期按 OK 不动作：这时候开播会绕过首帧门控，画面没出来声音先到。
            if (phase == VideoPhase.LOADING) return
            val c = videoController.value ?: return
            when (phase) {
                VideoPhase.ENDED -> c.play()
                else -> c.playPause()
            }
            controlsVisible = true
        } else if (menuLevel > 0) {
            menuActivity++
            when (menuLevel) {
                1 -> when (menuCursor) {
                    0 -> { menuLevel = 2; menuShown = 2; panelCursor = 0 }
                    1 -> onPreviewOriginal(!previewOriginal)
                }
                2 -> when (panelCursor) {
                    0 -> { onSetSlideshow(!slideshow); menuLevel = 0 }
                    1 -> onSlideSeconds(nextSlideChoice(slideSeconds, 1))
                    2 -> onShuffle(!slideshowShuffled)
                    // 循环 / 过渡 / 动画 / 满屏：OK 和右键同义（切到下一个值）。
                    // 以前只有左右键能改、OK 按下去没反应，遥控器用户习惯按 OK，白按一次
                    // 还以为坏了；现在统一成「左右=前/后一档，OK=往后一档」。
                    3 -> onLoop(!loopSlideshow)
                    4 -> onSlideFade(!slideFade)
                    5 -> onSlideZoom(!slideZoom)
                    6 -> onSlideFill(!slideFill)
                    // 音乐由第 7 项（menu_music）打开弹窗。
                    // 列表还空着就先扫一遍：电视上的媒体库常年不更新，等用户自己按刷新
                    // 才知道歌在哪，不如打开就给结果。已经有列表就不重复扫（省掉解码时长）。
                    // 音乐弹窗压在二级菜单上面开，**不把菜单收掉**：关掉弹窗要落回
                    // 「刚才那一屏」，从音乐直接掉回光秃秃的照片、再让用户重新按 ↑ 找回菜单，
                    // 等于替人把上下文擦了。返回键的层级因此是：弹窗 → 二级 → 一级 → 退出。
                    7 -> {
                        musicDialogOpen = true
                        menuActivity++
                        if (musicHasPermission && musicEntries.isEmpty()) onRefreshMusic()
                    }
                }
            }
        } else {
            // 幻灯片播放中：OK = 暂停/继续自动播放（看久了某张就停一下，再按继续）。
            // 只切本地 paused 标记，不动全局 slideshow 开关（见上方自动翻页的拦门逻辑）。
            if (slideshow) {
                slideshowPaused = !slideshowPaused
            } else {
                // 普通看图：OK 缩放三档循环。回到默认档把平移一并归零。
                zoomIndex = (zoomIndex + 1) % ZOOM_SCALES.size
                if (zoomIndex == 0) pan = Offset.Zero
                zoomHint = zoomLabels[zoomIndex]
            }
        }
    }

    /**
     * 0.24.12 M-5：所有「离开当前视频」的路径收敛到这一个入口。
     * 原来只有退出确认和 slide() 两处各自 stop()，闲置保护 / ViewModel 侧退回相册墙
     * 会绕过它，只剩淡出 160ms 后的异步 release() —— 正是尾音窗口。
     */
    fun stopVideo() {
        videoController.value?.stop()
        videoController.value = null
        // m-2：长按快进的 Job 在 450ms 内切走会残留 fast=true，一并收干净。
        holdJob?.cancel()
        holdJob = null
        fast = false
        onVideoPlayingChanged(false)
    }

    // 0.24.12 M-5：ViewModel 侧换屏（closeViewer / backToWall / 闲置到点）时发信号。
    // 旧屏淡出期间 AnimatedContent 不会用新参数重组，但组合内自己订阅的 Flow 仍活跃，
    // 所以能在同一刻断声画，而不是等 160ms 后的 onDispose。
    val leaveSignal by viewerLeaveSignal.collectAsState()
    val initialLeaveSignal = remember { leaveSignal }
    LaunchedEffect(leaveSignal) {
        if (leaveSignal != initialLeaveSignal) stopVideo()
    }

    fun twoStageExit() {
        val now = System.currentTimeMillis()
        if (exitArmedAt != 0L && now - exitArmedAt <= EXIT_WINDOW_MS) {
            stopVideo()
            onExit()
        } else {
            exitArmedAt = now
            exitHintVisible = true
        }
    }

    fun handleBack() {
        // 背景音乐弹窗优先收掉：它自己吃返回键，但 BackHandler 兜底也会到这，
        // 两种情况都只关弹窗、不动看图状态。
        if (musicDialogOpen) { musicDialogOpen = false; return }
        when {
            menuLevel == 2 -> { menuLevel = 1; menuShown = 1; menuActivity++ }
            menuLevel == 1 -> { menuLevel = 0; menuActivity++ }
            // 播放中因操作弹出的控制栏算浮层：返回先收它。暂停/播完的常驻栏不算，
            // 加载期压根没有栏，直接走退出确认。
            video != null && controlsVisible &&
                (phase == VideoPhase.PLAYING || phase == VideoPhase.FAST) -> controlsVisible = false
            // 信息框也是浮层：先收它再谈退出，不然想退还得为一块牌子多按一次。
            infoVisible -> onToggleInfo()
            // 放大是「我改了这张图的看法」，返回键的最优先含义是**回到原样**，不是离开。
            // 一放大就想退出去的人，按两下就行；但按一下直接退走，等于把人刚放大的照片
            // 连自己也一起关掉了——那才是真的意外。
            zoomIndex > 0 -> {
                zoomIndex = 0
                pan = Offset.Zero
                zoomHint = zoomLabels[0]
            }
            else -> twoStageExit()
        }
    }

    fun handleDirection(key: Key): Boolean {
        // 动了方向键说明不是想退：撤掉武装。返回键自己不清零（见规范 §7 铁律）。
        exitArmedAt = 0
        exitHintVisible = false
        val scale = ZOOM_SCALES[zoomIndex]
        val maxPanX = zoomPanLimit(scale, screenWpx)
        val maxPanY = zoomPanLimit(scale, screenHpx)
        // 每按一次走「单侧行程 / ZOOM_PAN_STEPS」：1.7x 和 2.5x、1080p 和 4K 上
        // 从正中走到边都是同样次数，不再用固定 48dp（放大档越高越走不到边）。
        val stepX = zoomPanStep(maxPanX)
        val stepY = zoomPanStep(maxPanY)
        // 方向键移动的是**取景框（镜头）**：按 ← 看照片左边，照片就得往右挪；
        // 按 ↑ 看上边，照片往下挪。0.23.4 起按这个方向，翻页（slide）不受影响。

        fun slide(delta: Int) {
            // 0.26.2：手动翻页记下目标格子，供自动前进拦门区分「人手翻到的视频」。
            manualStepTarget.value = index + delta
            // 0.24.12：统一走 stopVideo()，顺带收掉残留的快进 Job / fast 标记。
            if (video != null) stopVideo()
            onStep(delta)
        }

        /**
         * 幻灯片播放中，←/→ 先归音乐：真的切到歌了才吃掉按键（返回 true）——
         * 不翻照片、不重启幻灯片的计时，画面该怎么走还怎么走。
         * 没在播放（暂停 / 幻灯片关着 / 一首歌都没勾）时返回 false，按键照旧翻页。
         */
        fun trySwitchSong(delta: Int): Boolean =
            slideshow && !slideshowPaused && onSkipMusic(delta)

        /**
         * 菜单音乐行 ←/→ 快速切源：本地 ↔ 飞牛（飞牛未登录时不切，避免静默失败无反馈）。
         * 真正的拒绝判定在 ViewModel：未登录切飞牛会写 fnMusicError，由音乐弹窗展示。
         */
        fun switchMusicSource() {
            val next = if (musicSourceKind == MusicSourceKind.FN) MusicSourceKind.LOCAL else MusicSourceKind.FN
            if (next == MusicSourceKind.FN && !fnMusicLoggedIn) return
            onMusicSourceChanged(next)
        }

        /** [cameraDx] / [cameraDy] 是镜头方向：-1 = 左 / 上，+1 = 右 / 下。 */
        fun panBy(cameraDx: Int, cameraDy: Int) {
            if (zoomIndex == 0) return
            pan = applyZoomPan(
                pan = pan,
                delta = zoomPanDelta(cameraDx, cameraDy, stepX, stepY),
                maxX = maxPanX,
                maxY = maxPanY,
            )
        }

        when {
            menuLevel == 2 -> {
                menuActivity++
                when (key) {
                    Key.DirectionUp -> panelCursor = (panelCursor - 1 + PANEL_ITEMS.size) % PANEL_ITEMS.size
                    Key.DirectionDown -> panelCursor = (panelCursor + 1) % PANEL_ITEMS.size
                    Key.DirectionLeft -> when (panelCursor) {
                        1 -> onSlideSeconds(nextSlideChoice(slideSeconds, -1))
                        2 -> onShuffle(!slideshowShuffled)
                        3 -> onLoop(!loopSlideshow)
                        4 -> onSlideFade(!slideFade)
                        5 -> onSlideZoom(!slideZoom)
                        6 -> onSlideFill(!slideFill)
                        7 -> switchMusicSource()
                    }
                    Key.DirectionRight -> when (panelCursor) {
                        1 -> onSlideSeconds(nextSlideChoice(slideSeconds, 1))
                        2 -> onShuffle(!slideshowShuffled)
                        3 -> onLoop(!loopSlideshow)
                        4 -> onSlideFade(!slideFade)
                        5 -> onSlideZoom(!slideZoom)
                        6 -> onSlideFill(!slideFill)
                        7 -> switchMusicSource()
                    }
                    else -> return false
                }
            }
            menuLevel == 1 -> {
                menuActivity++
                when (key) {
                    Key.DirectionLeft -> menuCursor = (menuCursor - 1 + MENU_BUTTONS) % MENU_BUTTONS
                    Key.DirectionRight -> menuCursor = (menuCursor + 1) % MENU_BUTTONS
                    Key.DirectionUp -> menuCursor = 0
                    Key.DirectionDown -> menuLevel = 0
                    else -> return false
                }
            }
            video != null -> when (key) {
                // 0.25.0：STILL 也归翻页——静止态没有播放器，落到 seekBy 等于按了没反应。
                Key.DirectionLeft -> when {
                    videoDirectionSteps(
                        still = phase == VideoPhase.STILL,
                        loading = phase == VideoPhase.LOADING,
                        ended = phase == VideoPhase.ENDED,
                        controlsVisible = controlsVisible,
                    ) -> slide(-1)
                    else -> {
                        videoController.value?.seekBy(-SEEK_SECONDS)
                        controlsVisible = true
                    }
                }
                Key.DirectionRight -> when {
                    videoDirectionSteps(
                        still = phase == VideoPhase.STILL,
                        loading = phase == VideoPhase.LOADING,
                        ended = phase == VideoPhase.ENDED,
                        controlsVisible = controlsVisible,
                    ) -> slide(1)
                    else -> {
                        videoController.value?.seekBy(SEEK_SECONDS)
                        controlsVisible = true
                    }
                }
                Key.DirectionUp -> controlsVisible = true
                // 暂停/播完的常驻栏不收：↓ 只收「因操作弹出」的播放中栏。
                Key.DirectionDown -> if (phase == VideoPhase.PLAYING || phase == VideoPhase.FAST) {
                    controlsVisible = false
                }
                else -> return false
            }
            else -> when (key) {
                // 放大档：方向键 = 移动取景框。按 ← 看照片左侧（照片往右移），
                // 按 ↑ 看照片上方（照片往下移），与遥控器直觉一致。
                Key.DirectionLeft -> when {
                    zoomIndex > 0 -> panBy(-1, 0)
                    // 幻灯片播放中 ←/→ 是切歌键（见 trySwitchSong）：切到了就到此为止。
                    trySwitchSong(-1) -> Unit
                    else -> slide(-1)
                }
                Key.DirectionRight -> when {
                    zoomIndex > 0 -> panBy(1, 0)
                    trySwitchSong(1) -> Unit
                    else -> slide(1)
                }
                Key.DirectionUp -> if (zoomIndex > 0) panBy(0, -1) else {
                    menuLevel = 1
                    menuShown = 1
                    menuCursor = 0
                    menuActivity++
                }
                Key.DirectionDown -> if (zoomIndex > 0) panBy(0, 1) else Unit
                else -> return false
            }
        }
        return true
    }

    fun handleConfirmDown(event: androidx.compose.ui.input.key.KeyEvent): Boolean {
        // 连发抑制只认时间窗：确认键按住会以极短间隔连发 KeyDown（旧项目实机结论），
        // 120ms 内的第二次起全部吞掉。方向键的连发是想要的，不在这条路上。
        val now = System.currentTimeMillis()
        if (lastConfirmDownMs != 0L && now - lastConfirmDownMs < CONFIRM_REPEAT_MS) {
            confirmSwallowed = true
            return true
        }
        lastConfirmDownMs = now
        confirmSwallowed = false
        // 长按快进只在播放中有意义：按住 450ms 进四倍速，松手恢复。
        if (video != null && phase == VideoPhase.PLAYING) {
            holdJob = scope.launch {
                delay(OK_HOLD_MS)
                fast = true
                videoController.value?.setSpeed(FAST_SPEED)
                controlsVisible = true
            }
        }
        return true
    }

    fun handleConfirmUp(): Boolean {
        holdJob?.cancel()
        holdJob = null
        if (confirmSwallowed) {
            confirmSwallowed = false
            return true
        }
        if (fast) {
            // 松手回 1 倍速。快进前一定是播放中（暂停/加载期不启动长按），直接恢复播放。
            fast = false
            videoController.value?.setSpeed(1f)
            controlsVisible = true
        } else {
            shortPressOk()
        }
        return true
    }

    // 返回键走两条路：焦点在这个 Box 上时由 onKeyEvent 的 Key.Back 收；
    // 焦点被打断过（播放器抢焦点、界面切换那一帧）时按键会漏到 Activity，BackHandler 兜住。
    // 先到的那条把事件消费掉，不会执行两次。
    BackHandler { handleBack() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { event ->
                // 音乐弹窗打开时，看图页一律不处理按键：按键必须由弹窗自己吃掉，
                // 否则焦点万一还在这层，OK 会被当成缩放键（点「刷新」背景图却放大就是这原因）。
                if (musicDialogOpen) return@onKeyEvent false
                when (event.type) {
                    KeyEventType.KeyDown -> when (event.key) {
                        in CONFIRM_KEYS -> handleConfirmDown(event)
                        Key.Back -> { handleBack(); true }
                        Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown ->
                            handleDirection(event.key)
                        else -> false
                    }
                    KeyEventType.KeyUp -> when (event.key) {
                        in CONFIRM_KEYS -> handleConfirmUp()
                        else -> false
                    }
                    else -> false
                }
            },
    ) {
        // ── 画面层（顺序固定：播放器在下、舞台在上、提示在最上）─────────────
        // 0.25.0：三个 if 各自占住固定的组合位置，条件翻转不会把舞台挪走。
        // ① 播放器只在 [armed] 时存在。静止态连引擎都不构造：没有取流、没有声音、
        //    没有解码器占用，也就不存在「封面底下偷偷播起来」的可能。
        if (video != null && armed) {
            // 每个视频一个独立的播放器实例：用 videoUrl 当 key，切到别的视频时整块重挂，
            // 旧的播放器在 onDispose 里释放掉，不会漏掉解码器或音频焦点。
            val videoKey = video.videoUrl!!
            key(videoKey) {
                VideoPlayer(
                    url = videoKey,
                    dataSourceFactory = dataSourceFactory,
                    engineFactory = videoEngineFactory,
                    controller = videoController,
                    onState = { videoStateFlow.value = it },
                    // 0.24.12 M-4：首帧事实绑「播放器实例 token」而不是 URL：
                    // A → 照片 → A 时新实例必须重新画转圈，旧 token 的回调也不会误撤。
                    onFirstFrame = { videoLoadingState.onFirstFrame(sessionToken) },
                    onError = { code ->
                        videoError = code
                        videoLoadingState.onGiveUp(sessionToken)
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // ② 舞台：照片和「页内翻到的视频封面」共用同一个 [PhotoStage] 实例。
        //    换的是槽里的图，不是拆掉重建一层 AsyncImage；邻居预取早已覆盖视频封面，
        //    所以 ←/→ 切过去直接命中内存缓存，和切下一张照片一样无缝。
        if (stageVisible) {
            // 缩放层：照片舞台整体缩放平移。默认档不加变换，零开销。
            val zoomScale by animateFloatAsState(
                targetValue = ZOOM_SCALES[zoomIndex],
                animationSpec = tween(ZOOM_ANIM_MS),
                label = "photoZoom",
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        // 视频封面恒为静止取景：既不推镜也不带着上一张照片的放大档，
                        // 首帧撤层时和播放器画面才对得上。
                        scaleX = if (video != null) 1f else zoomScale
                        scaleY = if (video != null) 1f else zoomScale
                        translationX = if (video != null) 0f else pan.x
                        translationY = if (video != null) 0f else pan.y
                    },
            ) {
                // 舞台内部是两层常驻的图（缩略图垫底 + 两个槽），Ken Burns 和叠化都在
                // [PhotoStage] 里。这里只管手动缩放/平移——两者相乘而不是互相覆盖。
                PhotoStage(
                    photo = photo,
                    photos = photos,
                    previousPhoto = previousPhoto,
                    // 视频封面绝不推镜；[PhotoStage] 内部也按 isVideo 排除了 Ken Burns，
                    // 两处一起保证封面是静止的。
                    slideshow = if (video != null) false else slideshow,
                    slideCfg = slideCfg,
                    fill = slideFill,
                    // 写成 lambda 而不是 ::photoRequest：函数引用不会替我们套用默认参数，
                    // 而 photoRequest 现在是 (照片, 是否原图) 两个入参。
                    // 视频封面恒用 m 档（original=false）：和邻居预取是同一条缓存 key，
                    // 也是 0.24.9 之前那套封面链唯一需要保留的部分。
                    request = { p ->
                        if (video != null) photoRequest(p, original = false) else photoRequest(p)
                    },
                    // 垫底层恒用预览档：切到原图后是它顶着画面，不能跟着一起变原图。
                    previewRequest = { p -> photoRequest(p, original = false) },
                    // 请求里还含解码尺寸和「原图」开关：它们变了要重新取图，
                    // 没变就绝不动 model（换了 model Coil 会清掉 painter 重新加载）。
                    requestKey = RequestKey(previewOriginal, screenWpx, screenHpx),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // ③ 覆盖层。封面在的时候绝不整屏铺黑：静止态给中央播放图标，按 OK 后
        //    给一圈无背景转圈，坏片源给叠在封面上的半透明牌子。
        if (video != null) {
            if (armed && videoLoading) {
                if (showCover) {
                    VideoStillSpinner(modifier = Modifier.fillMaxSize())
                } else {
                    // 网格直接点开视频：原样的黑底转圈，0.24.15 行为逐字不变。
                    VideoLoadingOverlay(modifier = Modifier.fillMaxSize())
                }
            }
            if (phase == VideoPhase.STILL) {
                VideoStillPlayBadge(modifier = Modifier.fillMaxSize())
            }
            if (videoError != null) {
                // 0.24.12 M-2：坏片源不再黑屏无提示。封面还在就叠牌子，不露黑；
                // 网格直进的错误仍走原来的整屏黑底。
                VideoErrorNotice(
                    code = videoError.orEmpty(),
                    opaque = !showCover,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // 歌词贴屏幕右侧、垂直居中：两列竖排（当前句 / 下一句）从左到右等距排开。
        // 右边距 40dp 比列距 28dp 大 12dp，是给电视 overscan 留的裁切余量——宁可离边远
        // 一点，也不能让最右那列的字被屏幕边缘切掉半拉。
        // 作为 PhotoStage 的兄弟节点挂在根 Box 上，对 PhotoStage 内部的图层/绘制
        // 逻辑完全不可见——歌词的任何重组都不可能波及幻灯片的换图时序。
        if (lyricsEnabled) {
            LyricsOverlay(
                doc = lyricsDoc,
                index = lyricIndex,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 40.dp),
            )
        }

        // 左上角信息框：日期 / 地点 / 第几张。MENU 键开关，进图默认收起。
        // 不在 2.5 秒自动收之列——它是「人主动叫出来的」，自己再消失等于让人白按一次。
        //
        // 每行都只写值、不写「日期」「地点」这种标签：一块压在照片上的牌子，
        // 每个字都在挡主体，而日子长什么样、地名长什么样，人一眼就分得出来。
        // 视觉层级改由字号和明度承担：日期最亮最大，地点次之，第几张最轻。
        AnimatedVisibility(
            visible = infoVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopStart),
        ) {
            Column(
                modifier = Modifier
                    // 浮层要离开屏幕边缘，否则圆角被屏幕切掉，看着像缺了一角。
                    .padding(start = 32.dp, top = 24.dp)
                    .clip(RoundedCornerShape(CardCorner))
                    .background(Color(0x99000000))
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                // 少了标签，行距就成了唯一的节奏：4dp 是「三行属于同一块」的极限，
                // 再大（8dp 以上）三行会散成三段互不相干的信息。
                verticalArrangement = Arrangement.spacedBy(INFO_LINE_GAP),
            ) {
                // 第一行：文件名 + 真实大小。大小来自 ImageSizes（Coil 磁盘缓存里那份源的真实
                // 字节数），原图/缩略图各显各的：原图开关关时是 1080p 预览(~200KB)，开时是真原图(十几 MB)。
                // 文件名取自 fnOS 接口；部分相册不返回时只显示大小。
                photo?.let { p ->
                    // 视频的「已加载大小」是那张 JPEG 封面（约 200KB），拿它当视频大小会误导，
                    // 所以视频优先用服务端给的真实文件大小；照片仍按当前加载的那一档显示。
                    val loadedSize = ImageSizes.map[photoUrl(p)] ?: 0L
                    val sizeText = formatBytes(if (p.isVideo && p.byteSize > 0) p.byteSize else loadedSize)
                    val firstLine = buildString {
                        p.name?.let { append(it) }
                        if (sizeText.isNotEmpty()) {
                            if (isNotEmpty()) append(" ")
                            append(sizeText)
                        }
                    }
                    if (firstLine.isNotEmpty()) {
                        Text(
                            text = firstLine,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFFEDEDED),
                        )
                    }
                    // 0.27.0：视频给时长 + 像素，照片只给像素；两项都拿不到就整行不出现。
                    mediaFactsText(p.width, p.height, p.durationSeconds)?.let { facts ->
                        Text(
                            text = facts,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFD5D5D5),
                        )
                    }
                }
                photo?.takenAt?.let { taken ->
                    Text(
                        text = formatShotTime(taken),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFEDEDED),
                    )
                }
                photo?.location?.let { place ->
                    Text(
                        text = place,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFD5D5D5),
                    )
                }
                Text(
                    text = if (hasMore) {
                        stringResource(R.string.viewer_counter_more, index + 1, photos.size)
                    } else {
                        stringResource(R.string.viewer_counter, index + 1, photos.size)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // 提示位在画面正下方（与「再按一次退出」同一处）：电视三米外右上角的小字看不清，
        // 缩放档位和视频状态牌都挪到这里，人只记一处。
        // 0.25.0：静止态（STILL）也不挂状态牌——静止封面只留中央播放图标。
        // 0.25.3：播放中不挂状态牌（画面在动，无需文字解释）；只在暂停/快进/播完
        // 这类「画面为什么不动/为什么变快」的态给文案，暂停牌明确写出按 OK 继续。
        val chromeVisible = videoChromeVisible(
            still = phase == VideoPhase.STILL,
            loading = phase == VideoPhase.LOADING,
            error = videoError != null,
            controlsVisible = controlsVisible,
        )
        val phaseLabel = when (phase) {
            VideoPhase.PLAYING -> null          // 播放中不显示「播放」
            VideoPhase.FAST -> stringResource(R.string.viewer_status_fast)
            VideoPhase.PAUSED -> stringResource(R.string.viewer_status_paused)
            VideoPhase.ENDED -> stringResource(R.string.viewer_status_ended)
            VideoPhase.LOADING -> null          // 加载 / 缓冲只留转圈，不挂状态牌
            VideoPhase.STILL -> null            // 静止封面：只有中央播放图标，不写字
            null -> null
        }
        // 状态牌跟着控制栏出没；播放/加载/静止/错误态都不挂。暂停时控制栏强制常驻，
        // 所以这块牌子会一直亮着，用来解释「画面停住不是卡死」。
        // 音乐弹窗开着时一并藏掉：弹窗是半透明遮罩，底下隐约透一块「暂停」牌只会添乱。
        val hintText = if (musicDialogOpen) {
            null
        } else if (video != null) {
            if (chromeVisible) phaseLabel else null
        } else if (slideshow && slideshowPaused) {
            // 0.26.4：视频暂停与幻灯片暂停本来就是同一句话，合并到一条资源。
            stringResource(R.string.viewer_status_paused)
        } else {
            zoomHint
        }
        // 位置改到画面正下方，与「再按一次退出」提示同处，电视三米外也看得清。
        AnimatedVisibility(
            visible = hintText != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 120.dp),
        ) {
            Text(
                text = hintText.orEmpty(),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                modifier = Modifier
                    .background(Color(0x99000000), RoundedCornerShape(CardCorner))
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }

        // 两段退出提示。比状态牌高一档（状态牌 bottom=120dp）：视频停在「暂停/播完」时
        // 状态牌跟着常驻控制栏亮着，两块牌子同一高度会叠在一起糊成一行。
        AnimatedVisibility(
            visible = exitHintVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 190.dp),
        ) {
            Text(
                text = stringResource(R.string.viewer_exit_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                modifier = Modifier
                    .background(Color(0xB3202020), RoundedCornerShape(CardCorner))
                    .padding(horizontal = 22.dp, vertical = 12.dp),
            )
        }

        // 视频控制栏：左已播时间 / 进度条 / 右总时长。状态牌不在这儿——它去了右上角
        // 和缩放档位同处（见上面的提示位），所以这一条只有时间轴，整行在框里居中。
        if (video != null) {
            AnimatedVisibility(
                // 加载期 / 静止期只有转圈或播放图标，别再叠一条还没开始走的进度栏；
                // 错误态交给提示牌。
                visible = chromeVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                VideoControlBar(
                    stateFlow = videoStateFlow,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp, vertical = 28.dp)
                        .clip(RoundedCornerShape(CardCorner))
                        .background(Color(0xB3000000))
                        .padding(horizontal = 28.dp, vertical = 16.dp),
                )
            }
        } else {
            // 照片菜单。一级按钮行 / 二级幻灯片设置，都从底部升起。
            AnimatedVisibility(
                visible = menuLevel > 0,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                // 画哪一层看 menuShown，不看 menuLevel：退场淡出时 menuLevel 已归零。
                if (menuShown == 1) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 32.dp, vertical = 28.dp)
                            .clip(RoundedCornerShape(CardCorner))
                            .background(Color(0xB3000000))
                            .padding(horizontal = 28.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.Start,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MenuPill(
                            text = stringResource(R.string.menu_slideshow),
                            selected = menuCursor == 0,
                        )
                        Spacer(Modifier.width(16.dp))
                        MenuPill(
                            text = stringResource(R.string.menu_original),
                            selected = menuCursor == 1,
                            highlighted = previewOriginal,
                        )
                    }
                } else {
                    // 幻灯片二级菜单「音乐」行：只报「选了什么」，账号一律不显示。
                    // 形态判定收在纯函数里（可单测），这里只负责套字符串资源。
                    val menuLabel = buildMusicMenuLabel(
                        sourceKind = musicSourceKind,
                        // 曲库还没扫过（entries 为空）时按存下的勾选数报：否则刚开机
                        // 还没进过音乐弹窗，菜单里会显示「未选择」而实际上有歌在等着放。
                        // 落盘的选择集里可能混着飞牛歌单 id，数本地首数时要排除掉。
                        localSelectedCount = if (musicEntries.isEmpty()) {
                            musicSelected.count { !isFnSelectionId(it) }
                        } else {
                            musicEntries.count { it.id in musicSelected }
                        },
                        fnSelected = hasFnSelection(musicSelected),
                        fnGroupTitle = selectedFnGroupTitle(musicSelected, fnMusicGroups),
                    )
                    val musicValue = when (menuLabel) {
                        is MusicMenuLabel.Local ->
                            stringResource(R.string.menu_music_local_count, menuLabel.count)
                        // 冷启歌单列表还没拉回来、或勾的是旧版 fn: 曲目 id 时没有名字，
                        // 只写来源，不编歌单名，也不误报成「未选择」。
                        is MusicMenuLabel.Fn -> menuLabel.groupTitle?.let {
                            "${stringResource(R.string.music_source_fn)}/$it"
                        } ?: stringResource(R.string.music_source_fn)
                        MusicMenuLabel.None -> stringResource(R.string.menu_music_none)
                    }
                    SlidePanel(
                        cursor = panelCursor,
                        slideshow = slideshow,
                        slideSeconds = slideSeconds,
                        shuffled = slideshowShuffled,
                        loop = loopSlideshow,
                        fade = slideFade,
                        zoom = slideZoom,
                        fill = slideFill,
                        musicValue = musicValue,
                    )
                }
            }
        }

        if (musicDialogOpen) {
            MusicDialog(
                catalog = remember(content) {
                    content.toMusicCatalogUi()
                },
                playing = musicPlaying,
                failed = musicFailed,
                actions = MusicCatalogActions(
                    onSourceChanged = onMusicSourceChanged,
                    onToggleOrder = { onSetMusicShuffle(!musicShuffled) },
                    onToggleLyrics = { onSetLyrics(!lyricsEnabled) },
                    onToggleAll = onToggleMusicAll,
                    onRefresh = onRefreshMusic,
                    onToggle = onToggleMusic,
                    onToggleGroup = onMusicSourceToggleGroup,
                    onOpenGroup = onMusicSourceOpenGroup,
                    onBackToGroups = onMusicSourceBackToGroups,
                    onRequestPermission = { permissionLauncher.launch(musicPermissionName) },
                    onDismissError = onMusicSourceDismissError,
                ),
                onClose = { musicDialogOpen = false },
            )
        }
    }
}

/**
 * 视频状态。0.24.12 补 LOADING（首帧没到 / 缓冲中不再默认落到 PAUSED）；
 * 0.25.0 补 STILL（看图页内 ←/→ 翻到的视频只显示静止封面，不创建播放器）。
 */
private enum class VideoPhase { STILL, LOADING, PLAYING, FAST, PAUSED, ENDED }

// ── 0.25.0：静止封面的纯规则 ────────────────────────────────────────────
//
// 这组函数不碰 Compose、不碰播放器，专门把「哪条视频可以直接播、哪条要先静止」
// 的状态机钉死在 JVM 单测里。UI 侧只负责把结果翻译成组合期的挂载条件。
//
// 关键约束（0.24.9~0.24.12 反复踩过的坑）：
//  - 静止态绝不创建 VideoPlayer：没播放器就没有取流、没有声音、没有解码器占用；
//  - 授权按「本次停留」记（remember(photo.id)），翻走再翻回同一条也不复活，
//    全程组合期同步完成，用不着 LaunchedEffect 事后归零
//    （异步归零会晚一帧，那一帧舞台被拆掉就是肉眼看到的「黑一下」）；
//  - 封面只在「页内翻到的视频」上出现，网格直接点开的视频保持原来的黑底转圈。

/** 进屏 / 每次换图后的授权初值：只有「网格直接点视频」自动起播，页内翻到的一律静止。 */
internal fun videoEntryArmed(isVideo: Boolean, navigated: Boolean): Boolean =
    isVideo && !navigated

/** 当前这条视频是否已获起播授权。授权 state 换图即重建，翻回同一条也不会复活。 */
internal fun videoIsArmed(armed: Boolean, isVideo: Boolean): Boolean = isVideo && armed

/** 按 OK：静止态把授权授予本次停留；已经起播的不再重复授予。 */
internal fun videoArmAfterConfirm(armed: Boolean, isVideo: Boolean): Boolean = armed || isVideo

/**
 * 封面层该不该盖着。
 *
 * [coverOrigin] = 这条视频是看图页内翻到的（网格直接点进来的没有封面，走原黑底转圈）。
 * 首帧真到之前一直盖着；报错时也别撤，否则错误提示底下露出的是没画首帧的播放器。
 * 撤层与首帧由同一个 snapshot state 驱动，无 alpha、无淡出。
 */
internal fun videoCoverVisible(
    coverOrigin: Boolean,
    armed: Boolean,
    firstFrameReady: Boolean,
    error: Boolean,
): Boolean = coverOrigin && (!armed || !firstFrameReady || error)

/** STILL 态按左右键一律翻页：没有播放器，绝不能落到 ±5 秒 seek。 */
internal fun videoDirectionSteps(
    still: Boolean,
    loading: Boolean,
    ended: Boolean,
    controlsVisible: Boolean,
): Boolean = still || loading || ended || !controlsVisible

/** 状态牌 / 进度栏：静止、加载、报错三种态一律不亮。 */
internal fun videoChromeVisible(
    still: Boolean,
    loading: Boolean,
    error: Boolean,
    controlsVisible: Boolean,
): Boolean = controlsVisible && !still && !loading && !error

// ── 0.26.2：幻灯片自动前进的拦门 ──────────────────────────────────────
//
// 纯规则，不碰 Compose、不碰播放器。要钉死的是「这一格归谁」：
//  - 幻灯片自己往前跑：只落在照片上，视频整段跳过（SKIP_VIDEO 只是兜底）；
//  - 人手翻到的视频：这一格归人，静止封面等着、按 OK 能完整播，幻灯片不接管；
//  - 人手翻回照片：不用额外复位，下一格自然回到 DELAY_THEN_ADVANCE。
//
// 播放中 / 暂停 / 播完不会被自动带走，靠的是 UI 侧两处：自动前进的 effect
// 不把 phase 放进 key，播完的 onVideoEnded 也要过 [videoEndedAdvancesSlideshow]。

internal enum class SlideshowAdvanceMode {
    /** 幻灯片没开 / 暂停 / 人手翻到的视频：不计时也不前进。 */
    HOLD,

    /** 幻灯片自己落在视频上（兜底）：立刻跳到下一张图片。 */
    SKIP_VIDEO,

    /** 正常照片：展示时长到了再前进。 */
    DELAY_THEN_ADVANCE,
}

internal fun slideshowAdvanceMode(
    slideshow: Boolean,
    paused: Boolean,
    isVideo: Boolean,
    manualArrival: Boolean,
): SlideshowAdvanceMode = when {
    !slideshow || paused -> SlideshowAdvanceMode.HOLD
    isVideo && manualArrival -> SlideshowAdvanceMode.HOLD
    isVideo -> SlideshowAdvanceMode.SKIP_VIDEO
    else -> SlideshowAdvanceMode.DELAY_THEN_ADVANCE
}

/** 视频播完要不要交给幻灯片续下一张：人手翻到的那一格说好了停住，播完也不带走。 */
internal fun videoEndedAdvancesSlideshow(manualArrival: Boolean): Boolean = !manualArrival

/** 3 秒超时 / 8 秒放弃的计时，只在播放器真的挂上之后才起跑。 */
internal fun videoTimeoutEnabled(armed: Boolean): Boolean = armed

/**
 * 0.24.12 m-3：顶层只需要这三个布尔量推导 phase / 闲置保护，
 * 进度位置留在 Flow 里由 [VideoControlBar] 自己订阅，避免每 250ms 重组整个看图页。
 */
private data class VideoFlags(
    val isPlaying: Boolean = false,
    val ended: Boolean = false,
    val buffering: Boolean = false,
)

/**
 * 正在淡出的上一张。
 *
 * [scale] 是它离开屏幕那一刻的 Ken Burns 比例，不是 1.0——旧图已经在 1.05 附近了，
 * 按 1.0 画会在淡出的第一帧「缩回去」，肉眼能看见跳一下。
 * [duration] 在生成时就定住，因为淡出途中用户可能改时长档位，那不该影响正在跑的这一次。
 * [token] 只用来当动画的 key：同一张图连续淡出两次时，靠 id 认不出这是新的一轮。
 */


/**
 * 二级菜单是一张两列表：标签列定宽 + 值列定宽，整块盒子按内容定宽后居中。
 *
 * 值的左边缘固定在同一条竖线上（以前用 SpaceBetween，值被推到行末，标签与值相距近
 * 900px，眼睛容易对错行；只把值右对齐也没用，短值如「3秒」依旧孤零零贴在屏幕右边）。
 * 但整块盒子按比例铺满屏宽同样不对：电视上全宽约 1216dp，八行两字标签加短值却被
 * 拉到两端，中间一片空。这里是「内容有多长，盒子就有多长」。
 *
 * 标签全是两个字（播放/时长/顺序/循环/转场/动画/画幅/音乐），88dp 足够；
 * 值最长的现实情况是「飞牛音乐/相册专用」9 个字，20sp 约 170dp，216dp 留有富余，
 * 再长才走省略号——不再靠拉宽整行来容纳。
 */
private val SLIDE_MENU_LABEL_WIDTH = 88.dp
private val SLIDE_MENU_VALUE_WIDTH = 216.dp

/** 二级幻灯片设置的项，与 panelCursor 对应。标签走资源，值在 [SlidePanel] 里现算。 */
private val PANEL_ITEMS = listOf(
    R.string.menu_play,
    R.string.menu_duration,
    R.string.menu_order,
    R.string.menu_loop,
    R.string.menu_transition,
    R.string.menu_animation,
    R.string.menu_aspect,
    R.string.menu_music,
)
private const val MENU_BUTTONS = 2

private val CONFIRM_KEYS = setOf(
    Key.DirectionCenter,
    Key.Enter,
    Key.Spacebar,
    Key.MediaPlayPause,
)

/**
 * 两个解码参数 `KB_DECODE_SLACK=1.12f` / `ORIGINAL_DECODE_SCALE=2.0f` 及其取值理由
 * （Ken Burns 末端放大、原图开关为什么必须跟着放大解码框、48MB 位图的内存账）
 * 已随 `photoRequest` 一起逐字搬进 `engine/impl/CoilImageRequestFactory` 的
 * companion object。此处刻意**不留第二份常量**：留在 UI 里会让人以为改它还能生效。
 */

/**
 * 换图之后隔多久才去解下一张。让开叠化那一段，别和转场抢帧——见预取那段注释。
 * 500ms 后开始，3 秒档还剩 2.5 秒给下载 + 解码，局域网里绰绰有余。
 */
private const val PREFETCH_DELAY_MS = 500L

/** 全屏请求的构成要素。值相等就复用同一个 model，Coil 不会重新加载。 */
private data class RequestKey(val original: Boolean, val widthPx: Float, val heightPx: Float)

private val ZOOM_SCALES = listOf(1f, 1.7f, 2.5f)
private val ZOOM_LABEL_RES = listOf(
    R.string.viewer_zoom_default,
    R.string.viewer_zoom_17,
    R.string.viewer_zoom_25,
)

/**
 * 从画面正中走到边缘要按多少次方向键。步长 = 单侧可平移距离 / 这个数。
 *
 * 固定 dp（旧版 48dp）在高倍档要连按二十几下才到边，在矮屏上又一步跨太远；
 * 按行程比例算，1.7x / 2.5x、1080p / 4K 都是同一个手感。10 次约 0.6~0.8 秒长按。
 */
internal const val ZOOM_PAN_STEPS = 10

/** 放大 [scale] 倍后，单侧最多能平移多少屏幕像素；再往外就会露出黑底。 */
internal fun zoomPanLimit(scale: Float, screenPx: Float): Float =
    ((scale - 1f).coerceAtLeast(0f) / 2f * screenPx.coerceAtLeast(0f))

/** 一次方向键的位移：从正中到边缘固定 [ZOOM_PAN_STEPS] 次。 */
internal fun zoomPanStep(limitPx: Float): Float = limitPx / ZOOM_PAN_STEPS

/**
 * 镜头位移 → 照片层位移，两者方向相反。
 * [cameraDx] / [cameraDy]：-1 = 左 / 上，+1 = 右 / 下；照片往反方向挪，
 * 于是「按 ← 看到照片左边」而不是把照片本身往左拖。
 */
internal fun zoomPanDelta(cameraDx: Int, cameraDy: Int, stepX: Float, stepY: Float): Offset =
    Offset(-cameraDx * stepX, -cameraDy * stepY)

/** 累加一次平移并夹在 ±[maxX] / ±[maxY] 内，保证画面始终盖满屏幕。 */
internal fun applyZoomPan(pan: Offset, delta: Offset, maxX: Float, maxY: Float): Offset =
    Offset(
        (pan.x + delta.x).coerceIn(-maxX, maxX),
        (pan.y + delta.y).coerceIn(-maxY, maxY),
    )

/** 二级菜单里的时长档位。与设置页共用 [FnSettings.SLIDE_STEPS]，这里循环取值。 */
private val SLIDE_CHOICES_PANEL = FnSettings.SLIDE_STEPS

/** 时长在 3/5/8 里循环。[delta] 为 1 是下一个档，-1 是上一个；当前值不在档里就从 3 起步。 */
private fun nextSlideChoice(current: Int, delta: Int): Int {
    val i = SLIDE_CHOICES_PANEL.indexOf(current)
    return SLIDE_CHOICES_PANEL[(i + delta).mod(SLIDE_CHOICES_PANEL.size)]
}

/**
 * 「2026年10月26日 星期一 19:46」。
 *
 * 星期和「年月日」写死中文：电视的系统语言常常是英文，跟着 Locale 走会变成
 * 「Oct 26, 2026 Mon」混在中文界面里。日期和时间不做时区换算——这是照片拍摄地的本地时间。
 */
private fun formatShotTime(t: LocalDateTime): String =
    "${t.year}年${t.monthValue}月${t.dayOfMonth}日 星期${WEEKDAY_CN[t.dayOfWeek.value - 1]} " +
        "%02d:%02d".format(t.hour, t.minute)

private val WEEKDAY_CN = listOf("一", "二", "三", "四", "五", "六", "日")

/**
 * 底部一粒菜单按钮。selected = 光标在这（实心亮底），highlighted = 功能开着（右边挂一个小点），
 * 两件事分开表达：光标会走，开关状态得一直在。
 *
 * 小点走 Row 排在文字后面，不用绝对定位——绝对定位贴在右上角会压到字上
 * （12dp 的文字内边距 + 8dp 的点，纵向正好重叠）。
 */
@Composable
private fun MenuPill(text: String, selected: Boolean, highlighted: Boolean = false) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(
                when {
                    selected -> MaterialTheme.colorScheme.primary
                    else -> Color(0x33FFFFFF)
                },
            )
            .padding(horizontal = 26.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = when {
                selected -> MaterialTheme.colorScheme.onPrimary
                else -> Color.White
            },
        )
        if (highlighted) {
            // 开关状态的小点：3 米外能认出「原图开着」，又不和焦点混淆。
            // 选中时底色是亮的，点换成 onPrimary 才看得见。
            Spacer(Modifier.width(10.dp))
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (selected) MaterialTheme.colorScheme.onPrimary else Color.White),
            )
        }
    }
}

/** 二级幻灯片设置面板：八行，上下走项、OK 确认、左右改值。 */
@Composable
private fun SlidePanel(
    cursor: Int,
    slideshow: Boolean,
    slideSeconds: Int,
    shuffled: Boolean,
    loop: Boolean,
    fade: Boolean,
    zoom: Boolean,
    fill: Boolean,
    /** 「音乐」行右侧的值：勾选摘要（本地 N首 / 飞牛音乐/歌单名 / 未选择），由调用方算好。 */
    musicValue: String,
) {
    Column(
        modifier = Modifier
            // 不再 fillMaxWidth：盒子宽度 = 标签列 + 值列 + 内边距，由外层
            // BottomCenter 摆到屏幕水平正中。左右各留 32dp 是 overscan 安全区。
            .width(
                SLIDE_MENU_LABEL_WIDTH + SLIDE_MENU_VALUE_WIDTH +
                    32.dp + // 行内左右 padding
                    56.dp + // 面板左右 padding
                    64.dp, // overscan 安全区
            )
            .padding(horizontal = 32.dp, vertical = 28.dp)
            .clip(RoundedCornerShape(CardCorner))
            .background(Color(0xE6141414))
            .padding(horizontal = 28.dp, vertical = 18.dp),
    ) {
        val values = listOf(
            "", // 播放：动作项，没有值
            stringResource(R.string.menu_seconds, slideSeconds),
            stringResource(if (shuffled) R.string.menu_order_random else R.string.menu_order_seq),
            stringResource(if (loop) R.string.settings_on else R.string.settings_off),
            stringResource(if (fade) R.string.menu_fade else R.string.menu_off),
            stringResource(if (zoom) R.string.menu_zoom else R.string.menu_off),
            stringResource(if (fill) R.string.menu_fill else R.string.menu_fit),
            // 音乐：入口项，值是调用方算好的勾选摘要。没勾时写「未选择」，
            // 空着看不出是「没选」还是「这项没有值」；账号与来源名都不写在这一行。
            musicValue,
        )
        PANEL_ITEMS.forEachIndexed { i, labelRes ->
            val selected = cursor == i
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.33f)
                        else Color.Transparent,
                    )
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 标签列定宽：值的左边缘落在同一条竖线，不随内容长短左右漂移。
                Text(
                    text = stringResource(labelRes),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (selected) Color.White else Color(0xFFCFCFCF),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(SLIDE_MENU_LABEL_WIDTH),
                )
                if (values[i].isNotEmpty()) {
                    Text(
                        text = values[i],
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Start,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/**
 * 视频播放失败的整屏提示（0.24.12 M-2）。
 *
 * 0.24.13 起附带错误码：创维电视取不到 logcat，屏上给出 code 才能定位片源 / 解码失败原因。
 * 加载层由 onError 撤掉，这里接管画面；左右键 / 返回键照常能离开。
 */
internal fun videoErrorCodeLabel(code: String): String = when (code) {
    "TIMEOUT" -> "加载超时"
    else -> code
}

@Composable
private fun VideoErrorNotice(
    code: String,
    modifier: Modifier = Modifier,
    /** false = 叠在静止封面上：只给牌子本身衬底，绝不整屏铺黑（0.25.0）。 */
    opaque: Boolean = true,
) {
    Box(
        modifier = if (opaque) modifier.background(Color.Black) else modifier,
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = if (opaque) {
                Modifier
            } else {
                Modifier
                    .clip(RoundedCornerShape(CardCorner))
                    .background(Color(0xCC0F0F0F))
                    .padding(horizontal = 32.dp, vertical = 24.dp)
            },
        ) {
            Text(
                text = stringResource(R.string.viewer_video_error),
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.viewer_video_error_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (code.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.viewer_video_error_code, videoErrorCodeLabel(code)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun VideoControlBar(stateFlow: StateFlow<VideoUiState>, modifier: Modifier = Modifier) {
    // 只有这个组件订阅完整状态：进度每 250ms 变一次，重组范围就只有这两行文字 + 进度条。
    val state by stateFlow.collectAsState()
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = formatDuration(state.positionMs),
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFEDEDED),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 18.dp)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color(0x33FFFFFF)),
        ) {
            val fraction = if (state.durationMs > 0) {
                (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
            } else {
                0f
            }
            if (fraction > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .height(6.dp)
                        .background(Color.White),
                )
            }
        }
        Text(
            text = formatDuration(state.durationMs),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 毫秒转 mm:ss（过一小时带小时位）。 */
private fun formatDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) {
        String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    } else {
        String.format(Locale.US, "%02d:%02d", m, s)
    }
}

private const val AUTO_HIDE_MS = 2_500L
/** 左上信息框三行之间的间距。18sp/16sp/16sp 的字号本身已带行高，这 4dp 只补视觉呼吸。 */
private val INFO_LINE_GAP = 4.dp
private const val ZOOM_HINT_MS = 1_000L
private const val ZOOM_ANIM_MS = 300
private const val EXIT_WINDOW_MS = 1_500L
private const val OK_HOLD_MS = 450L
private const val CONFIRM_REPEAT_MS = 120L
private const val SEEK_SECONDS = 5
private const val FAST_SPEED = 4f
