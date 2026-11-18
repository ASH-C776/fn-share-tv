package com.kijeye.fnshare

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.LazyGridState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import com.kijeye.fnshare.presentation.event.AppEvent
import com.kijeye.fnshare.presentation.event.AppEventSink
import com.kijeye.fnshare.ui.TvBringIntoViewRoot
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kijeye.fnshare.ui.AppState
import com.kijeye.fnshare.ui.AppViewModel
import com.kijeye.fnshare.ui.GridScreen
import com.kijeye.fnshare.ui.LoginScreen
import com.kijeye.fnshare.presentation.state.Screen
import com.kijeye.fnshare.ui.SettingsScreen
import com.kijeye.fnshare.ui.ViewerActions
import com.kijeye.fnshare.ui.ViewerContent
import com.kijeye.fnshare.ui.ViewerScreen
import com.kijeye.fnshare.ui.WallScreen
import com.kijeye.fnshare.ui.UpdateDialog
import com.kijeye.fnshare.domain.model.UpdateInfo
import com.kijeye.fnshare.ui.parseRingColor
import com.kijeye.fnshare.ui.theme.FnShareTheme
import kotlinx.coroutines.delay

/** 闲置检查的轮询间隔。15 秒一次足够——闲置档位本身是以分钟计的，精度要求不高。 */
private const val IDLE_TICK_MS = 15_000L

/** 错误提示自动消失的时间。够读完两行字，又不至于一直挂在画面上。 */
private const val ERROR_TOAST_MS = 6_000L

/**
 * 启动页的最短停留时间。
 *
 * 自动登录在后台跑，快的话几百毫秒就完事——不加下限的话启动页一闪而过，
 * 看起来像闪了个 bug。1.2 秒：够看清图标，又不至于让人等。
 * 真正的耗时（登录 NAS、拉相册）发生在这 1.2 秒里，过完直接落相册墙，无感。
 */
private const val SPLASH_MIN_MS = 1_200L

/**
 * 启动页上图标的边长。
 *
 * 尺寸对齐相册墙上的封面（1:1、六列，1080p 下约 180dp）：启动页是「这个应用长什么样」的
 * 第一眼，比相册封面还大一圈就喧宾夺主了——之前按系统自适应图标渲染尺寸取 288dp，
 * 在电视上整整占了四成屏高，像一张海报而不是图标。
 */
private const val SPLASH_ICON_DP = 180

class MainActivity : ComponentActivity() {

    private val model: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // 系统启动层只铺背景色、不画图标（图标改由下面 Compose 自绘的启动页负责）。
        // 必须赶在 super.onCreate 之前装，晚一步系统就把默认那层画出来了。
        //
        // 0.29.1 曾把启动页整层交给系统、应用不再自绘——但创维电视的 ROM 不画系统那层的
        // 图标，结果按 OK 之后是一段黑屏、然后直接进相册，看不到启动页（0.29.4 改回）。
        // 现在两层分工：系统层只给同色背景（交接时看不出切换），图标只在 Compose 启动页
        // 画一次，所以既能在电视上看见，也不会像 0.29.0 之前那样「圆形 → 方块」跳一下。
        installSplashScreen()
        super.onCreate(savedInstanceState)
        // 这是个挂在墙上的相框：画面自己黑掉就失去意义了。
        // 只作用于本应用窗口，退出应用后不影响系统休眠。
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // 必须在 setContent 之前：图片加载器一旦在组合里才注册，第一批 AsyncImage 已经用默认
        // 加载器（没有网络组件）发过请求了，结果就是一片灰块——而且不会有任何报错。
        // 第 5 批：注册动作下沉到 engine/api/ImageEngine，Activity 只经 ViewModel 转发，
        // 不再认识 Coil 的 SingletonImageLoader；时序与原来完全一致。
        model.installImageEngine()
        setContent {
            // 焦点框颜色设成全 App 的 primary：焦点框、设置左竖条、光标、选中点统一同色。
            val state by model.state.collectAsStateWithLifecycle()
            FnShareTheme(primaryColor = parseRingColor(state.focusRingColorHex)) {
                // 真机纵向滚动优化：把焦点跨行的整页滚动压到 150ms 干脆曲线（见 TvScroll.kt）。
                TvBringIntoViewRoot {
                    App(model)
                }
            }
        }
    }

    /**
     * 遥控器的「菜单」键进设置。
     *
     * 放在 Activity 这一层是因为 Compose 的按键链收不到它——实测在相册墙上按菜单键，
     * 组合里的 onPreviewKeyEvent 一次都不触发（背光、方向键都正常）。这一层收得到。
     *
     * 相册墙和相册网格都响应（0.28.0 起网格也能直接进设置，返回时回到原相册）；
     * 设置页里再按一次不该把设置页重新推一遍，所以设置页不响应。
     * 大图界面借用同一个键开左上信息框（见下）。
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val menuKey = keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_SETTINGS
        val screen = model.state.value.screen
        if (menuKey && (screen is Screen.Wall || screen is Screen.Grid)) {
            model.onEvent(AppEvent.OpenSettings)
            return true
        }
        // 大图界面里 MENU 键改作「左上信息框」开关。同样只能在这一层收——
        // Compose 的按键链收不到菜单键（见上一条注释）。
        if (menuKey && screen is Screen.Viewer) {
            model.onEvent(AppEvent.ToggleViewerInfo)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}

@Composable
private fun App(model: AppViewModel) {
    // 写侧只认识 AppEventSink：下面所有回调改成发事件，ViewModel 的 onEvent() 负责分派。
    // 读侧（state / 5 个 StateFlow / 引擎产出的只读句柄）仍走 model，抽成聚合接口留到第 7 批。
    val sink: AppEventSink = model
    val state by model.state.collectAsStateWithLifecycle()

    // 图片请求工厂按屏幕像素产出：算式与 `ViewerScreen` 里算 screenWpx/screenHpx 的
    // 那两行完全相同（同一个 composition 的 LocalConfiguration + LocalDensity），
    // 所以工厂内部的解码框、缓存 key 与原来逐字一致。转成 Int 是因为工厂契约收 Int；
    // 电视/MuMu 上 toPx() 都是整数像素，截断不改变结果。
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val screenWpx = with(density) { configuration.screenWidthDp.dp.toPx() }.toInt()
    val screenHpx = with(density) { configuration.screenHeightDp.dp.toPx() }.toInt()
    val requestFactory = remember(screenWpx, screenHpx) {
        model.imageRequestFactory(screenWpx, screenHpx)
    }
    // 0.24.12 M-6：视频引擎工厂由组装层注入，引用要稳定（ViewerContent 每次组合都会重建）。
    val videoEngineFactory = remember { model::videoEngine }
    // 背景乐「播放中」取播放器的真实状态：勾选项全是找不到的幽灵 id 时，
    // `slideshow && selected.isNotEmpty()` 会谎报「播放中」。
    val musicPlaying by model.musicPlaying.collectAsStateWithLifecycle()
    // 有坏曲子时音乐弹窗要说一句，否则「勾了歌没声音」在用户那边就是一个死结。
    val musicFailed by model.musicFailed.collectAsStateWithLifecycle()
    // 歌词：文档只在换曲时变，句下标只在换行时变——两者都是低频流，正常 collect 即可。
    val lyricsDoc by model.lyricsDoc.collectAsStateWithLifecycle()
    val lyricIndex by model.lyricIndex.collectAsStateWithLifecycle()
    // 新版本：弹窗信息与安装信号（0.31.0）。
    val updateInfo by model.updateInfo.collectAsStateWithLifecycle()
    val installPath by model.installRequest.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current

    // 下载完成 → 拉起系统安装界面。用 FileProvider 把私有目录的 APK 授权给系统安装器；
    // targetSdk 36 不能再用 file://，否则系统直接报「解析包错误」。
    LaunchedEffect(installPath) {
        val path = installPath ?: return@LaunchedEffect
        runCatching {
            val apk = java.io.File(path)
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apk,
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
        model.consumeInstallRequest()
    }

    // 每个相册一份滚动位置。换相册要回到顶部，退回网格却必须停在原处。
    val gridStates = remember { mutableMapOf<Long, LazyGridState>() }

    // 闲置保护：每隔 15 秒看一眼「多久没操作了」。幻灯片在放时 tickIdle 自己会忽略。
    LaunchedEffect(Unit) {
        while (true) {
            delay(IDLE_TICK_MS)
            sink.onEvent(AppEvent.TickIdle)
        }
    }

    // 启动页：自动登录在后台跑（ViewModel init 里），这里只保证它至少亮 1.2 秒。
    // 需要鉴权（没存过账号 / 登录失败 / 要访问码）时落登录页；存过账号且登录成功
    // 则直接落相册墙——从图标到照片墙，中间不出现登录页。
    //
    // 系统那层（installSplashScreen）此时已经撤掉，只留同色背景；图标由这里的
    // SplashScreen 画一次，电视上也能看见，且不会有「系统圆 → 应用方」的跳变。
    var splashMinElapsed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(SPLASH_MIN_MS)
        splashMinElapsed = true
    }
    if (state.starting || !splashMinElapsed) {
        SplashScreen()
        return
    }

    Box(
        Modifier
            .fillMaxSize()
            // 预览阶段就收所有按键：不论子组件有没有把键消费掉，只要有人按遥控器就把闲置计时归零。
            // 返回 false 表示不拦截，按键照常往下传给当前屏处理。
            .onPreviewKeyEvent { event ->
                // 只记「有操作」，不做任何拦截。遥控器的菜单键在 Activity.onKeyDown
                // 收（Compose 的按键链收不到它，见那里的注释），别在这再摆一条收不到的路径。
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                sink.onEvent(AppEvent.NoteInteraction)
                false
            },
    ) {
        // 屏与屏之间过渡：新屏**不淡入**，旧屏淡出。
        //
        // 原来是 fadeIn(tween(200)) togetherWith fadeOut(tween(160))：新屏从 alpha=0 起步，
        // 过渡中段两层都半透明，底下露出的是 Activity 窗口的深色底 → 肉眼就是「黑一下」。
        // 尤其从相册墙进网格 / 进大图那一下，新屏本来也是黑底 + 异步加载的封面，等于黑上加黑。
        // 现在按 LQIP 那条「任何时刻恰有一层满不透明」的规矩做原子交接：新屏立刻全不透明，
        // 旧屏 z=0 盖在 z=-1 的新屏上淡出，全程窗口底色不会外露。
        AnimatedContent(
            targetState = state.screen,
            // 只按「屏的类型」判断要不要淡，不按屏里带的参数判断。
            //
            // 相册里翻一张照片，Screen.Viewer 的 index 就变一次，targetState 跟着变——
            // 不设 contentKey 的话，AnimatedContent 会把「换一张照片」当成「换了一个界面」，
            // 整屏淡出再淡入一遍：两张图各淡各的，透明度之和掉到七成，屏幕上就是黑一下。
            // 加上 contentKey 后同类型屏原地复用，ViewerScreen 的状态（双缓冲、缩放、Ken Burns）
            // 也不再被重建。
            contentKey = { it::class },
            transitionSpec = {
                ContentTransform(
                    // 任何时刻至少有一层满不透明：新屏不做 alpha 淡入，直接接住画面。
                    targetContentEnter = EnterTransition.None,
                    initialContentExit = fadeOut(tween(160)),
                    // 旧屏压在新屏之上，160ms 淡出才有得看；z 反过来的话新屏会直接盖住旧屏，
                    // 变成硬切。这里不是「取消过渡」，只是把淡入那半边拿掉。
                    targetContentZIndex = -1f,
                )
            },
            label = "screen",
        ) { screen ->
            when (screen) {
                is Screen.Login -> LoginScreen(
                    state = state,
                    onSignIn = { host, user, password ->
                        sink.onEvent(AppEvent.SignIn(host, user, password))
                    },
                    onSubmitAccessCode = { sink.onEvent(AppEvent.SubmitAccessCode(it)) },
                    onDismissAccessCode = { sink.onEvent(AppEvent.DismissAccessCode) },
                )

                is Screen.Wall -> {
                    // 加载中不额外铺黑底：WallScreen 自己有「正在读取共享相册…」的空态。
                    // 铺一层纯黑再叠上去，只会让那句提示晚几百毫秒才出现。
                    WallScreen(
                        state = state,
                        requestFactory = requestFactory,
                        onOpenAlbum = { sink.onEvent(AppEvent.OpenAlbum(it)) },
                        onOpenSettings = { sink.onEvent(AppEvent.OpenSettings) },
                    )
                }

                is Screen.Settings -> {
                    BackHandler { sink.onEvent(AppEvent.CloseSettings) }
                    SettingsScreen(
                        state = state,
                        onIdle = { sink.onEvent(AppEvent.IdleMinutes(it)) },
                        onBackdropWall = { sink.onEvent(AppEvent.BackdropWall(it)) },
                        onBackdropGrid = { sink.onEvent(AppEvent.BackdropGrid(it)) },
                        onFocusZoom = { sink.onEvent(AppEvent.FocusZoom(it)) },
                        onFocusRingColor = { sink.onEvent(AppEvent.FocusRingColor(it)) },
                        onRefresh = { sink.onEvent(AppEvent.RefreshAlbums) },
                        onClearCache = { sink.onEvent(AppEvent.ClearCache) },
                        // 「更多」页排版下一步做，入口先就位；当前点 OK 不跳转。
                        onMore = {},
                        onSignOut = { sink.onEvent(AppEvent.SignOut) },
                        onFnMusicSubmit = { user, pass, code ->
                            sink.onEvent(AppEvent.FnMusicSignIn(user, pass, code))
                        },
                        onFnMusicSignOut = { sink.onEvent(AppEvent.FnMusicSignOut) },
                        onFnMusicDismissError = { sink.onEvent(AppEvent.FnMusicDismissLoginError) },
                        onBack = { sink.onEvent(AppEvent.CloseSettings) },
                    )
                }

                is Screen.Grid -> {
                    BackHandler { sink.onEvent(AppEvent.BackToWall) }
                    val gridState = gridStates.getOrPut(screen.album.id) { LazyGridState() }
                    GridScreen(
                        state = state,
                        requestFactory = requestFactory,
                        album = screen.album,
                        gridState = gridState,
                        focusIndex = state.lastViewerIndex,
                        onOpenPhoto = { sink.onEvent(AppEvent.OpenViewer(it)) },
                        onLoadMore = { sink.onEvent(AppEvent.LoadMorePhotos) },
                        onRetry = { sink.onEvent(AppEvent.RetryPhotos) },
                        onBack = { sink.onEvent(AppEvent.BackToWall) },
                    )
                }

                is Screen.Viewer -> {
                    // 两段退出在 ViewerScreen 内部处理（返回先收浮层），这里不挂 BackHandler
                    // ——挂了会出现第二条退出路径，绕过确认。
                    ViewerScreen(
                        content = ViewerContent(
                            photos = state.photos,
                            index = screen.index,
                            slideshow = state.slideshow,
                            slideshowShuffled = state.slideshowShuffled,
                            slideSeconds = state.slideSeconds,
                            slideFade = state.slideFade,
                            slideZoom = state.slideZoom,
                            slideFill = state.slideFill,
                            loopSlideshow = state.loopSlideshow,
                            hasMore = state.hasMorePhotos,
                            previewOriginal = state.previewOriginal,
                            shuffleNext = state.shuffleNext,
                            infoVisible = state.viewerInfo,
                            requestFactory = requestFactory,
                            enqueueImage = model::enqueueImage,
                            dataSourceFactory = model.videoDataSourceFactory,
                            videoEngineFactory = videoEngineFactory,
                            viewerLeaveSignal = model.viewerLeaveSignal,
                            musicEntries = state.musicEntries,
                            musicSelected = state.musicSelected,
                            musicShuffled = state.musicShuffled,
                            musicScanning = state.musicScanning,
                            musicHasPermission = state.musicPermission,
                            musicPlaying = musicPlaying,
                            musicFailed = musicFailed,
                            musicPermissionName = model.musicRequiredPermission,
                            lyricsEnabled = state.lyricsEnabled,
                            lyricsDoc = lyricsDoc,
                            lyricIndex = lyricIndex,
                            musicSourceKind = state.musicSourceKind,
                            fnMusicLoggedIn = state.fnMusicLoggedIn,
                            fnMusicAccount = state.fnMusicAccount,
                            fnMusicGroups = state.fnMusicGroups,
                            fnMusicTracks = state.fnMusicTracks,
                            fnMusicCurrentGroupId = state.fnMusicCurrentGroupId,
                            fnMusicCurrentGroupTitle = state.fnMusicCurrentGroupTitle,
                            fnMusicLoading = state.fnMusicLoading,
                            fnMusicError = state.fnMusicError,
                        ),
                        actions = ViewerActions(
                            onStep = { sink.onEvent(AppEvent.Step(it)) },
                            onAutoStep = { sink.onEvent(AppEvent.AutoStep) },
                            // 唯一保留的直接调用：要拿 Boolean 决定「没歌可切就退回翻页」。
                            onSkipMusic = model::skipMusicTrack,
                            onSetSlideshow = { sink.onEvent(AppEvent.Slideshow(it)) },
                            onSlideshowPaused = { sink.onEvent(AppEvent.SlideshowPaused(it)) },
                            onSlideSeconds = { sink.onEvent(AppEvent.SlideSeconds(it)) },
                            onSlideFade = { sink.onEvent(AppEvent.SlideFade(it)) },
                            onSlideZoom = { sink.onEvent(AppEvent.SlideZoom(it)) },
                            onSlideFill = { sink.onEvent(AppEvent.SlideFill(it)) },
                            onShuffle = { sink.onEvent(AppEvent.SlideshowShuffled(it)) },
                            onLoop = { sink.onEvent(AppEvent.LoopSlideshow(it)) },
                            onPreviewOriginal = { sink.onEvent(AppEvent.PreviewOriginal(it)) },
                            onToggleInfo = { sink.onEvent(AppEvent.ToggleViewerInfo) },
                            onExit = { sink.onEvent(AppEvent.CloseViewer) },
                            onMusicSourceChanged = { sink.onEvent(AppEvent.MusicSourceChanged(it)) },
                            onMusicSourceToggleGroup = { sink.onEvent(AppEvent.FnMusicToggleGroup(it)) },
                            onMusicSourceOpenGroup = { sink.onEvent(AppEvent.FnMusicOpenGroup(it)) },
                            onMusicSourceBackToGroups = { sink.onEvent(AppEvent.FnMusicBackToGroups) },
                            onMusicSourceDismissError = { sink.onEvent(AppEvent.FnMusicDismissError) },
                            onToggleMusic = { sink.onEvent(AppEvent.ToggleMusic(it)) },
                            onToggleMusicAll = { sink.onEvent(AppEvent.ToggleMusicAll) },
                            onSetMusicShuffle = { sink.onEvent(AppEvent.MusicShuffled(it)) },
                            onSetLyrics = { sink.onEvent(AppEvent.LyricsEnabled(it)) },
                            onRefreshMusic = { sink.onEvent(AppEvent.RefreshMusic) },
                            onMusicPermissionResult = { sink.onEvent(AppEvent.MusicPermissionResult(it)) },
                            onVideoPlayingChanged = { sink.onEvent(AppEvent.VideoPlayingChanged(it)) },
                            onVideoEnded = { sink.onEvent(AppEvent.VideoEnded) },
                        ),
                    )
                }
            }
        }

        // 读照片失败时如果什么都不说，用户看到的就是「点了没反应」。
        // 登录页自己有错误位（会一直留着给人看），所以这里只管其余几屏。
        // 不抢焦点：电视上弹一个抢走焦点的提示条，比错误本身更烦人。
        ErrorToast(
            message = state.error.takeIf { state.screen !is Screen.Login },
            onTimeout = { sink.onEvent(AppEvent.DismissError) },
        )

        // 新版本弹窗只在相册墙显示：用户在相册里看图时不打断，回到墙再弹。
        if (state.updateDialog && state.screen is Screen.Wall && updateInfo != null) {
            UpdateDialog(
                info = updateInfo!!,
                currentVersion = BuildConfig.VERSION_NAME,
                progress = state.updateProgress,
                failed = state.updateFailed,
                onDownload = { sink.onEvent(AppEvent.DownloadUpdate) },
                onDismiss = { sink.onEvent(AppEvent.DismissUpdate) },
            )
        }
    }
}

/**
 * 启动页：桌面上那个应用图标，放大放在正中间。
 *
 * 图标本身直接复用 launcher 的前景（ic_launcher_foreground，飞牛蓝底的白色相框）+
 * 同一个底色，换应用图标时只需改那一个资源，这里自动跟着变，不会出现两套图形。
 *
 * 尺寸和相册封面一样大（见 [SPLASH_ICON_DP]），圆角取边长的四分之一。圆角保留——
 * 深色背景上那个圆角方块就是启动时的第一眼，切掉就成了一张方形贴纸。
 *
 * 系统启动层只铺同色背景、不画图标（见 values/themes.xml），所以这里画的图标
 * 是冷启动全程唯一的一个，不会像以前那样「系统圆形 → 应用方块」跳一下。
 */
@Composable
private fun SplashScreen() {
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(SPLASH_ICON_DP.dp)
                .clip(RoundedCornerShape(SPLASH_ICON_DP.dp / 4))
                .background(colorResource(R.color.ic_launcher_background)),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun ErrorToast(message: String?, onTimeout: () -> Unit) {
    LaunchedEffect(message) {
        if (message != null) {
            delay(ERROR_TOAST_MS)
            onTimeout()
        }
    }
    AnimatedVisibility(
        visible = message != null,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Row(
                modifier = Modifier
                    .padding(28.dp)
                    .background(Color(0xE6202020), androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                    .padding(horizontal = 24.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.Start,
            ) {
                Text(
                    text = message ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
