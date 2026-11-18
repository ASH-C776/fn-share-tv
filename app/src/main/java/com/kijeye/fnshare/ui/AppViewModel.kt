package com.kijeye.fnshare.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.datasource.DataSource
import coil3.request.ImageRequest
import com.kijeye.fnshare.BuildConfig
import com.kijeye.fnshare.data.lyrics.LyricsDoc
import com.kijeye.fnshare.data.music.MusicEntry
import com.kijeye.fnshare.data.fn.FnAccessCodeRejectedException
import com.kijeye.fnshare.data.fn.FnAccessCodeRequiredException
import com.kijeye.fnshare.data.fn.FnAddress
import com.kijeye.fnshare.data.fn.FnTwoFactorRequiredException
import com.kijeye.fnshare.data.fn.SharedAlbum
import com.kijeye.fnshare.data.fn.SharedPhoto
import com.kijeye.fnshare.di.AppContainer
import com.kijeye.fnshare.domain.model.MusicSourceKind
import com.kijeye.fnshare.domain.model.fnPlaylistGuidOrNull
import com.kijeye.fnshare.domain.model.fnTrackGuidOrNull
import com.kijeye.fnshare.domain.model.hasFnSelection
import com.kijeye.fnshare.domain.port.AlbumCacheSource
import com.kijeye.fnshare.domain.port.FnMusicAuthPort
import com.kijeye.fnshare.domain.port.MusicCatalogPort
import com.kijeye.fnshare.domain.port.MusicPreferencesPort
import com.kijeye.fnshare.domain.port.MusicSource
import com.kijeye.fnshare.domain.port.PhotoSource
import com.kijeye.fnshare.domain.port.SettingsSource
import com.kijeye.fnshare.domain.port.UpdateSource
import com.kijeye.fnshare.domain.usecase.FnGroupTracks
import com.kijeye.fnshare.domain.usecase.FnPlayQueue
import com.kijeye.fnshare.domain.usecase.DeckExhaustion
import com.kijeye.fnshare.domain.usecase.SlideshowDeck
import com.kijeye.fnshare.domain.usecase.decideDeckExhaustion
import com.kijeye.fnshare.domain.usecase.shouldPrefetchDeck
import com.kijeye.fnshare.domain.usecase.slideshowDeckIds
import com.kijeye.fnshare.domain.usecase.buildFnPlayQueue
import com.kijeye.fnshare.domain.usecase.fnQueueRetryDelayMs
import com.kijeye.fnshare.domain.usecase.shouldRetryFnQueue
import com.kijeye.fnshare.engine.api.ImageEngine
import com.kijeye.fnshare.engine.api.ImageRequestFactory
import com.kijeye.fnshare.engine.api.LyricsTicker
import com.kijeye.fnshare.engine.api.MusicPlaybackEngine
import com.kijeye.fnshare.engine.api.VideoEngine
import com.kijeye.fnshare.presentation.event.AppEvent
import com.kijeye.fnshare.presentation.event.AppEventSink
import com.kijeye.fnshare.presentation.state.Screen
import com.kijeye.fnshare.presentation.state.photoPageBelongsTo
import com.kijeye.fnshare.presentation.state.selectableIds
import com.kijeye.fnshare.presentation.state.settingsReturnTarget
import com.kijeye.fnshare.presentation.state.visibleMusicEntries
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 一个 ViewModel 管四屏。这个应用的状态很小——几组相册、一页照片、当前看第几张——
 * 拆成四个 ViewModel 只会让「从全屏退回网格时照片还在不在」这种问题变复杂。
 */
class AppViewModel(app: Application) : AndroidViewModel(app), AppEventSink {

    /**
     * 组装层：进程级单例，具体类只在 `di/AppContainer` 里 new。
     * 本 ViewModel 从这一批起只认识 `domain/port` 与 `engine/api` 的接口，
     * 不再 import `data.FnSettings` / `FnRepo` / `AlbumCache` / `MusicScanner` /
     * `LyricsRepo` / `SlideshowMusicPlayer`，也不再自己构造 Coil 的 ImageLoader。
     *
     * 字段名全部沿用旧名（settings / repo / albumCache / musicScanner / musicPlayer / …），
     * 是为了让下面一千多行的业务代码一行都不用改：只换类型与来源，不换调用点。
     */
    private val container = AppContainer.get(app)

    /**
     * 按接口持有，不按实现持有：想验证「登录失败要不要弹访问码」这类分支时，
     * 换一个假实现就能在 JVM 上跑，不必真的连着 NAS。
     */
    private val settings: SettingsSource = container.settings
    private val repo: PhotoSource = container.photoSource

    /** 新版本检测：0.31.0。独立客户端与时间戳，见 container / DefaultUpdateSource。 */
    private val updateSource: UpdateSource = container.updateSource
    private val updatePrefs = container.updatePrefs
    /** 下载完成后把本地 APK 路径放这里，MainActivity 观察它拉起系统安装界面。 */
    private val _installRequest = MutableStateFlow<String?>(null)
    val installRequest: StateFlow<String?> = _installRequest.asStateFlow()
    /** 当前检测到的新版本信息，弹窗显示用。 */
    val updateInfo: StateFlow<com.kijeye.fnshare.domain.model.UpdateInfo?> get() = updateSource.available

    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()

    /**
     * 0.24.12 M-5：任何「离开看图页」的路径都先递增这个信号。
     *
     * ViewModel 侧换屏（[closeViewer] / [backToWall] / 闲置到点）时，AnimatedContent 的旧屏
     * 还要淡出 160ms，不会用新参数重组；只有 ViewerScreen 自己订阅的 Flow 还能收到变化，
     * 于是能在同一刻 `VideoController.stop()`，而不是等淡出后的 onDispose 才断声画。
     */
    private val _viewerLeaveSignal = MutableStateFlow(0L)
    val viewerLeaveSignal: StateFlow<Long> = _viewerLeaveSignal.asStateFlow()

    private fun signalViewerLeaving() {
        _viewerLeaveSignal.value += 1
    }

    /** 图片引擎：构造参数逐字搬去了 `engine/impl/CoilImageEngine`，本类只拿接口。 */
    private val image: ImageEngine = container.imageEngine

    /**
     * 全局注册 ImageLoader。必须在 `setContent` 之前调用（`MainActivity.onCreate`），
     * 否则第一批 AsyncImage 会用默认加载器发出灰块请求。原 `MainActivity.kt:98` 的
     * `SingletonImageLoader.setSafe { model.imageLoader }` 换成这一行，时序不变。
     *
     * 过渡兼容出口（`val imageLoader: ImageLoader`）已在第 6 批删除：`ViewerContent`
     * 现在只拿 [ImageRequestFactory] 和 [enqueueImage]，不再认识 `ImageLoader` 这个类型
     * ——那正是「接口隔离」要保住的边界，全局始终只有 [image] 内部那一个实例
     * （同目录两个会损坏 Coil 的 journal）。
     */
    fun installImageEngine() = image.install()

    /**
     * 该代码是从 `ui/ViewerScreen.kt:283-325` 的 `photoRequest` 局部函数**反向接线**的
     * （结构化重构第 6 批）：原来 UI 自己在组合期拼 `ImageRequest`，现在由引擎按 viewport
     * 产出工厂，UI 只调用 `factory.photoRequest(...)`。
     *
     * 像素值来源：`MainActivity.App()` 用与 `ViewerScreen` 完全相同的
     * `LocalConfiguration` + `LocalDensity` 算 `screenWidthDp.dp.toPx()`，再转 Int 传入；
     * 工厂内部乘 `KB_DECODE_SLACK=1.12f` 与 `ORIGINAL_DECODE_SCALE=2.0f`，
     * 算式、精度、crossfade、磁盘字节数 listener 全部逐字照搬，缓存 key 不变。
     * `previewOriginal` 传的是**读当前 state 的 lambda**而不是布尔快照：工厂跨多次重组复用，
     * 开关变了不该重建工厂（重建会换掉 ImageRequest 的 model，触发 0.17.1 修过的叠化失效）。
     *
     * 对应的生命周期清理应该在原文件的哪个位置调用：
     * 【无需清理】。工厂只持有 Context 与不可变数值；ImageLoader 归 `ImageEngine`，
     * 而它是进程级单例、按第 5 批的决定不调用 `close()`（见 `di/AppContainer` 文件头）。
     * `AppViewModel.onCleared()` 不为此新增调用。
     */
    fun imageRequestFactory(screenWpx: Int, screenHpx: Int): ImageRequestFactory =
        image.requestFactory(screenWpx, screenHpx) { _state.value.previewOriginal }

    /** 原 `ui/ViewerScreen.kt:498` 的 `imageLoader.enqueue(it)`，改由引擎代投预取队列。 */
    fun enqueueImage(request: ImageRequest) = image.enqueue(request)

    /**
     * 相册元数据缓存：整本相册的照片清单落在 `filesDir`（系统不会自动清）。
     *
     * 有了它，进相册一个请求都不发，时间轴一打开就是完整的。
     * 接口方法自带 `Dispatchers.IO`，所以下面原本包在外层的 `withContext(IO)` 去掉了，
     * 调度等价、调用顺序不变。
     */
    private val albumCache: AlbumCacheSource = container.albumCacheSource

    /** 给 ExoPlayer 用的取流工厂：复用 FnTrust 信任自签证书的 OkHttp 客户端，
     *  证书信任和 AccessToken 头都自动带上，和图片加载是同一套。
     *  客户端现在由 `di/AppContainer` 单点持有，仍是每次访问新建一个无状态 Factory。 */
    val videoDataSourceFactory: DataSource.Factory get() = container.videoDataSourceFactory()

    /** 0.24.12 M-6：每段视频一个新引擎，具体实现只在 `di/AppContainer` 里 new。 */
    fun videoEngine(): VideoEngine = container.videoEngine()

    /** 本地曲库扫描：只认电视存储里 `music` 文件夹下的音频。 */
    private val musicScanner: MusicSource = container.musicSource

    /** 读音乐需要的权限：Android 13+ 是 `READ_MEDIA_AUDIO`，之前是 `READ_EXTERNAL_STORAGE`。 */
    val musicRequiredPermission: String get() = musicScanner.requiredPermission

    /**
     * 飞牛音乐子账户的登录契约。按 port 持有：本 ViewModel 不 import 任何
     * `data.music.fn` 的异常/DTO，也不认识 token 与访问码怎么落到 header 上。
     * 凭据与相册账号完全独立（音乐是 NAS 里的子账户）。
     */
    private val fnMusicAuth: FnMusicAuthPort = container.fnMusicAuth

    /** 双源相关偏好（来源选择、音乐子账户、上次歌单）独立于 `SettingsSource`。 */
    private val musicPreferences: MusicPreferencesPort = container.musicPreferences

    /** 飞牛曲库只按 port 持有：歌单 GUID / token / HTTP 全在 `data` 层。 */
    private val fnCatalog: MusicCatalogPort = container.musicCatalog(MusicSourceKind.FN)

    /**
     * 幻灯片背景乐播放器：**稳定代理**，内部按当前来源转发到本地或飞牛播放器。
     * 换成直接替换实例会让 MainActivity 已 collect 的 playing/failed 与歌词节拍器
     * 指向旧对象；代理的 `switch()` 只停播不重建，`onCleared()` 的 release 一行未改。
     */
    private val musicPlayer: MusicPlaybackEngine = container.musicPlayback()

    /** 背景乐现在真的在出声（播放器监听器实时维护）。UI 的「播放中」徽标认这个。 */
    val musicPlaying: StateFlow<Boolean> get() = musicPlayer.playing

    /** 这一轮里有曲子解不开（格式不支持 / 文件坏了），播放器已经跳过它。弹窗据此提示一句。 */
    val musicFailed: StateFlow<Boolean> get() = musicPlayer.failed

    /**
     * 歌词节拍器：`ui/AppViewModel.kt:963-1013` 那条 100ms 轮询循环已整体搬进
     * `engine/impl/DefaultLyricsTicker`，循环体逐字未改。这里用 lazy 是为了等
     * `viewModelScope` 完全就绪；第一次被 `lyricsDoc` / `start()` 摸到时才构造。
     *
     * ⚠️ 绝不能同时留着原来那条轮询：两条循环会重复写同一对 StateFlow。
     * 原 `maybeStartLyricsLoop()` / `clearLyrics()` 在本批改成转发 `start()` / `stop()`。
     */
    private val ticker: LyricsTicker by lazy {
        container.lyricsTicker(
            scope = viewModelScope,
            audio = musicPlayer,
            shouldRun = { _state.value.slideshow && _state.value.lyricsEnabled },
        )
    }

    /**
     * 当前曲目的歌词文档；没有歌词或还没解析出来时 null。
     *
     * 和 [musicPlaying] 同一条路（独立 StateFlow、MainActivity 单独 collect），
     * 不进 [AppState]——它只在换曲时变一次，塞进 AppState 只会平白多一路快照拷贝。
     */
    val lyricsDoc: StateFlow<LyricsDoc?> get() = ticker.doc

    /**
     * 当前唱到第几句（[LyricsDoc.indexOf] 的结果）；前奏里或没词时 -1。
     *
     * 100ms 轮询只在节拍器里写值，且**句不变不写**——StateFlow 值相等本来就不再发射，
     * 双保险之下重组频率等于换行频率，与轮询频率无关。
     */
    val lyricIndex: StateFlow<Int> get() = ticker.index

    /**
     * 把勾选集合按「曲库列表顺序」映射成播放列表：界面上的列表顺序即播放顺序。
     * 这样「先勾 A 再勾 B」和「先扫到 A 再扫到 B」得到同一套顺序，不依赖 set 的迭代次序。
     *
     * 解不开的容器（ape / dsd / wma…）直接剔掉：交给播放器只会得到一次
     * `UnrecognizedInputFormatException` 和一段静音，不如从一开始就不排进队列。
     */
    private fun orderedSelection(entries: List<MusicEntry>, selected: Set<String>): List<MusicEntry> {
        val picked = entries.filter { it.id in selected && it.playable }
        // 顺序 / 随机：随机是「整张播放列表洗一遍」——每次开播重排一次，播放期间顺序稳定，
        // 不会每首之间乱跳。关的时候保持曲库列表顺序（界面上看到的顺序就是播放顺序）。
        return if (settings.musicShuffled) picked.shuffled() else picked
    }

    init {
        _state.update {
            it.copy(
                slideSeconds = settings.slideSeconds,
                slideFade = settings.slideshowFade,
                slideZoom = settings.slideshowZoom,
                slideFill = settings.slideshowFill,
                loopSlideshow = settings.loopSlideshow,
                idleMinutes = settings.idleMinutes,
                backdropWall = settings.backdropWall,
                backdropGrid = settings.backdropGrid,
                focusZoom = settings.focusZoom,
                focusRingColorHex = settings.focusRingColorHex,
                slideshowShuffled = settings.slideshowShuffled,
                previewOriginal = settings.previewOriginal,
                lastHost = settings.host,
                lastUser = settings.userName,
                lastPassword = settings.password,
                // 音乐：上次勾选的曲目先灌进来；权限状态照实反映（拿不到权限时曲库界面会引导授权）。
                musicSelected = settings.musicSelection,
                musicShuffled = settings.musicShuffled,
                musicPermission = musicScanner.hasPermission(),
                lyricsEnabled = settings.lyricsEnabled,
                // 音乐子账户只回填地址与账号名；密码不进 AppState（重验时重敲一次）。
                fnMusicLoggedIn = fnMusicAuth.loggedIn,
                fnMusicAccount = fnMusicAuth.accountName,
                fnMusicSavedUser = fnMusicAuth.savedUser,
                fnMusicBaseUrl = fnMusicAuth.baseUrl.ifBlank { fnMusicAuth.defaultBaseUrl },
                musicSourceKind = savedMusicSourceKind(),
            )
        }
        // 存过「飞牛」但音乐子账户已退出（凭据被清）时，悄悄选飞牛只会得到空列表。
        // 压回本地并写回设置，别让下次开机重复这个无效状态。
        if (musicPreferences.sourceKind == MusicSourceKind.FN && !fnMusicAuth.loggedIn) {
            musicPreferences.sourceKind = MusicSourceKind.LOCAL
            musicPlayer.switch(MusicSourceKind.LOCAL)
            _state.update { it.copy(musicSourceKind = MusicSourceKind.LOCAL) }
        } else if (_state.value.musicSourceKind == MusicSourceKind.FN) {
            // 冷启直接落在飞牛源：token 只在内存，拉歌单时会话会自动重登一次。
            loadFnMusicGroups()
        }
        // 存过账号就直接进相册墙。电视开机后没人愿意每次都过一遍登录页。
        viewModelScope.launch {
            if (!settings.hasSavedLogin) {
                _state.update { it.copy(starting = false, screen = Screen.Login) }
                return@launch
            }
            val outcome = withContext(Dispatchers.IO) { runCatching { repo.resume() } }
            outcome.onSuccess {
                // 开机只落到相册墙，不自动开演。以前这里是「相框模式」：开机固定接着上次
                // 那个相册放——可家里电视白天还要看别的，一开机就自己放照片反而碍事。
                // 现在开演一律由人按：墙上进相册 → 点开一张图 → 大图底部菜单的「幻灯片」。
                loadAlbums()
            }.onFailure { failure ->
                // 存着账号却登不进去（NAS 关机、IP 变了、密码改了）时，必须把原因写出来。
                // 悄悄退回登录页会让人以为是自己手滑点了退出。
                if (failure is FnAccessCodeRequiredException) {
                    // 表单按存档预填（含密码），只差一个访问码——直接把弹窗开出来。
                    // pending 也一并填上：弹窗确认后 submitAccessCode 要拿它去重登，
                    // 这条路径没走过 signIn()，不填就点不动「连接」。
                    pendingHost = settings.host
                    pendingUser = settings.userName
                    pendingPassword = settings.password
                    _state.update {
                        it.copy(
                            starting = false,
                            screen = Screen.Login,
                            lastPassword = settings.password,
                            needAccessCode = true,
                            error = null,
                        )
                    }
                    return@launch
                }
                _state.update {
                    it.copy(starting = false, screen = Screen.Login, error = describe(failure))
                }
            }
        }
        // 新版本检测（0.31.0）：冷启动 5 秒后后台查一次，一天最多一次。
        // 网络不通、API 变动全部静默吞掉，绝不影响登录/看图。
        startUpdateCheck()
    }

    /**
     * 冷启动延迟检测新版本。
     *
     * 延迟 5 秒是避开启动高峰：splash 1.2s + 鉴权 + 拉相册都在这段时间里，
     * 再叠一个外网请求只会拖慢首屏。5 秒后用户基本已经站在相册墙上了。
     * 一天只查一次——每次开机都连外网对电视不友好，也没必要。
     */
    private fun startUpdateCheck() {
        viewModelScope.launch {
            delay(UPDATE_CHECK_DELAY_MS)
            val now = System.currentTimeMillis()
            val last = updatePrefs.lastCheckMs
            if (last > 0 && now - last < UPDATE_CHECK_INTERVAL_MS) return@launch
            updateSource.check(BuildConfig.VERSION_CODE)
            updatePrefs.lastCheckMs = now
            // 只认比当前新的；拿到结果后等回到相册墙再弹（用户可能正在相册里）。
            val found = updateSource.available.value ?: return@launch
            if (found.versionCode == updatePrefs.dismissedCode) return@launch
            while (_state.value.screen != Screen.Wall) delay(UPDATE_SHOW_POLL_MS)
            // 被用户中途取消过就不再弹（下载完 / 退回登录等情况）。
            if (updateSource.available.value == null) return@launch
            _state.update { it.copy(updateDialog = true) }
        }
    }

    fun signIn(host: String, userName: String, password: String, accessCode: String) {
        if (host.isBlank() || userName.isBlank() || password.isBlank()) {
            _state.update { it.copy(error = "地址、账号、密码都要填") }
            return
        }
        _state.update { it.copy(albumsLoading = true, error = null, needAccessCode = false) }
        viewModelScope.launch {
            val failure = withContext(Dispatchers.IO) {
                runCatching { repo.signIn(host.trim(), userName.trim(), password, accessCode.trim()) }
                    .exceptionOrNull()
            }
            if (failure is FnAccessCodeRequiredException) {
                // 表单留在原地，把访问码弹窗开出来；等 submitAccessCode 带着码再试。
                pendingHost = host.trim()
                pendingUser = userName.trim()
                pendingPassword = password
                _state.update { it.copy(albumsLoading = false, needAccessCode = true, error = null) }
                return@launch
            }
            if (failure != null) {
                _state.update { it.copy(albumsLoading = false, error = describe(failure)) }
                return@launch
            }
            settings.host = host.trim()
            settings.userName = userName.trim()
            settings.password = password
            settings.accessCode = accessCode.trim()
            _state.update {
                it.copy(
                    lastHost = host.trim(),
                    lastUser = userName.trim(),
                    lastPassword = password,
                )
            }
            loadAlbums()
        }
    }

    /** 访问码弹窗里按了「确认」：用刚才留在表单里的信息加上码再登一次。 */
    fun submitAccessCode(code: String) {
        if (pendingHost.isEmpty() || pendingUser.isEmpty() || pendingPassword.isEmpty()) return
        if (code.isBlank()) {
            // 空码不用真跑一趟网络：弹窗留着，直接说清楚。
            _state.update { it.copy(error = EMPTY_CODE_HINT) }
            return
        }
        _state.update { it.copy(albumsLoading = true, error = null) }
        viewModelScope.launch {
            val failure = withContext(Dispatchers.IO) {
                runCatching { repo.signIn(pendingHost, pendingUser, pendingPassword, code.trim()) }
                    .exceptionOrNull()
            }
            if (failure is FnAccessCodeRejectedException || failure is FnAccessCodeRequiredException) {
                // 码不对（或服务端仍要码）：弹窗留着让人改，别把人赶回登录页重新开一遍。
                _state.update { it.copy(albumsLoading = false, error = describe(failure)) }
                return@launch
            }
            if (failure != null) {
                // 别的原因（网络断了之类）：收起弹窗，把原因放回登录页。
                _state.update {
                    it.copy(albumsLoading = false, needAccessCode = false, error = describe(failure))
                }
                return@launch
            }
            settings.host = pendingHost
            settings.userName = pendingUser
            settings.password = pendingPassword
            settings.accessCode = code.trim()
            _state.update {
                it.copy(
                    albumsLoading = false,
                    needAccessCode = false,
                    lastHost = pendingHost,
                    lastUser = pendingUser,
                    lastPassword = pendingPassword,
                )
            }
            pendingHost = ""
            pendingUser = ""
            pendingPassword = ""
            loadAlbums()
        }
    }

    /** 访问码弹窗里按了「取消」：收起弹窗，表单原地保留，人可以改地址或密码再试。 */
    fun dismissAccessCode() = _state.update { it.copy(needAccessCode = false, error = null) }

    /**
     * 取相册列表。只有「进墙」这一件事——原来还有个 `startPlaying` 参数用来支持开机自动开演，
     * 那个模式已经去掉了（开机只停相册墙），参数跟着删掉，免得留一个永远传 false 的开关。
     *
     * [goToWall] 决定要不要把画面切回相册墙：登录之后要切（人等着进相册），
     * 设置页按「刷新相册」不要切——刷新现在是后台长任务，得留在设置页看进度。
     */
    private fun loadAlbums(goToWall: Boolean = true) {
        _state.update { it.copy(albumsLoading = true, error = null, starting = false) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { repo.albums() } }
            result.onSuccess { albums ->
                // 排序：**按张数**。
                //   · 组内：相册照片多的排左边（靠前）；
                //   · 组间：这个人名下所有相册加起来照片多的排上面。
                // 为什么不用接口给的 share_mod_time 倒序：那个只反映「最近有人动过」，
                // 天天有人看的家庭大相册会被一个刚建的 1 张相册顶到最前面。
                //
                // 并列时保留接口原顺序（sortedByDescending 是稳定排序），不会再乱跳。
                _state.update {
                    it.copy(
                        albumsLoading = false,
                        owners = groupsOf(albums),
                        screen = if (goToWall) Screen.Wall else it.screen,
                        account = "${settings.userName} · ${settings.host}",
                        error = null,
                    )
                }
                // 相册列表也留一份：NAS 掉线时墙还能显示上次的结果。
                val address = cacheAddress()
                if (address != null) {
                    viewModelScope.launch(Dispatchers.IO) {
                        albumCache.writeAlbums(address.display, albums, address.baseUrl)
                    }
                }
                // 张数变了的相册：后台重建清单，让缓存跟上 NAS。
                syncStaleAlbums(albums)
            }.onFailure { failure ->
                // NAS 掉线：用缓存里的列表兜底，别把已经显示的墙清空。
                val address = cacheAddress()
                val cached = if (address != null) {
                    withContext(Dispatchers.IO) {
                        albumCache.readAlbums(address.display, address.baseUrl)
                    }
                } else null
                _state.update {
                    it.copy(
                        albumsLoading = false,
                        error = describe(failure),
                        owners = if (cached != null) groupsOf(cached) else it.owners,
                        screen = if (cached != null || (it.screen is Screen.Wall && it.owners.isNotEmpty())) {
                            // 有缓存兜底就留在原地（可能是设置页），别把人踢回登录页。
                            it.screen
                        } else {
                            Screen.Login
                        },
                    )
                }
            }
        }
    }

    /** 按分享人分组、组内按张数排。列表缓存和联网结果用的是同一套排法。 */
    private fun groupsOf(albums: List<SharedAlbum>): List<OwnerGroup> {
        val groups = albums
            .groupBy { it.ownerName.ifEmpty { "未知" } }
            .map { (owner, list) ->
                OwnerGroup(owner, list.sortedByDescending { it.photoCount })
            }
            .sortedByDescending { group -> group.albums.sumOf { it.photoCount } }
        return groups
    }

    /**
     * 「刷新相册」：拉一次列表，顺带把缓存和 NAS 对不上的相册后台重建。
     *
     * 刷新的语义是「让缓存变准」，不是「清空重来」——所以列表里张数没变的相册，
     * 缓存继续用，一个请求都不发。
     */
    fun refreshAlbums() = loadAlbums(goToWall = false)

    /**
     * 打开相册：**按下 OK 的同一帧就切进网格**，清单在后台边读边灌。
     *
     * 0.24.3 曾改成「先在 IO 上把整本清单读完，再一次更新 state 切屏 + 灌数据」，确实
     * 消掉了「切过去第一帧是空的」，但代价是把整本 JSON 解析（3099 张 ≈ 1.3MB，外加
     * 3099 次拍摄日期解析）压在「按下 → 跳转」之间；启动时 `syncStaleAlbums` 还在同一个
     * IO 线程池里重建别本清单，连小相册也跟着等。用户实测：等待全发生在相册墙，大相册约 1 秒。
     *
     * 现在拆成三步，哪一步都不阻塞跳转：
     *  ① 同步切屏：首帧是标题 + 骨架灰格（`GridScreen` 在 photos 空且 loading 时画），
     *     M-5 的跨屏过渡保证新屏满不透明，不会露出窗口黑底；
     *  ② 后台读缓存，解析到前 [OPEN_FIRST_BATCH] 条就先灌进网格，真实缩略图立刻开加载；
     *  ③ 整本读完再补齐，LazyVerticalGrid 只组合可见项，后面的天然按列表顺序往下走。
     *
     * [openAlbumGeneration] 防连按：只有最后一次点击的数据能落屏，旧结果不许盖掉新相册。
     */
    fun openAlbum(album: SharedAlbum) {
        settings.lastAlbumId = album.id
        pendingStep = 0
        // 0.26.3：上一本可能还挂着「等下一页到了再自动前进」，换相册一并作废；
        // 留着的话，新相册第一次续页回来会凭空跳一张。
        pendingAutoStep = false
        // 换相册先把上一本的索引任务掐掉，它还在飞的那一页不能落到新相册里。
        dateIndexJob?.cancel()
        val generation = ++openAlbumGeneration

        // ① 立刻切屏。这里不碰磁盘、不碰网络，也不等后台同步任务让出 IO 线程。
        _state.update {
            it.copy(
                screen = Screen.Grid(album),
                photos = emptyList(),
                photosLoading = true,
                photosFailed = false,
                hasMorePhotos = false,
                indexing = false,
                lastViewerIndex = 0,
            )
        }

        viewModelScope.launch {
            // 连点另一本相册、或中途按 MENU 去了设置页：这批数据一律作废，别把人拽回来。
            fun stillCurrent(): Boolean =
                generation == openAlbumGeneration &&
                    (_state.value.screen as? Screen.Grid)?.album?.id == album.id

            val address = cacheAddress()
            val cached = if (address != null) {
                // 适配器内部自带 IO 调度，这里不再包一层 withContext（port 的线程约定）。
                albumCache.read(
                    address.display, album.id, address.baseUrl,
                    firstBatchSize = OPEN_FIRST_BATCH,
                    onFirstBatch = { first ->
                        // ② 前几条一到位就先画出来；photosLoading 保持 true，
                        //    尾部「加载中」一直挂到整本补齐，免得人以为相册只有这几张。
                        if (stillCurrent()) _state.update { it.copy(photos = first) }
                    },
                )
            } else null
            if (!stillCurrent()) return@launch
            val hit = cached?.takeIf { it.photos.isNotEmpty() }
            if (hit != null) {
                // ③ 缓存命中：整本补齐，一个网络请求都不发，时间轴一打开就是完整的。
                _state.update {
                    it.copy(photos = hit.photos, photosLoading = false, hasMorePhotos = false)
                }
                // 张数对不上 = NAS 上有人加了或删了照片：先用旧的顶着，后台重建。
                if (hit.photoCount != album.photoCount) rebuildAlbum(album)
                return@launch
            }
            // 没有缓存 / 缓存读坏了：按原来的分页路径走第一页。
            loadPhotoPage(album, 0)
            startDateIndex(album)
        }
    }

    fun loadMorePhotos() {
        val current = _state.value
        // 0.26.1：看图页也要能续页。以前这里只认网格页，幻灯片（以及手动翻到末尾）
        // 在 Viewer 里调本函数是直接 return 的，pendingAutoStep 挂着永远兑现不了，
        // 相册后半本就再也播不到。两屏拿的是同一本相册、同一份 photos，只是入口不同。
        val album = when (val screen = current.screen) {
            is Screen.Grid -> screen.album
            is Screen.Viewer -> screen.album
            else -> null
        } ?: return
        if (current.photosLoading || !current.hasMorePhotos) return
        _state.update { it.copy(photosLoading = true) }
        loadPhotoPage(album, current.photos.size)
    }

    /** 访问码弹窗挂起时留在表单里的登录信息，输完码原样带回登录。 */
    private var pendingHost = ""
    private var pendingUser = ""
    private var pendingPassword = ""

    /** 进设置页之前停在哪一屏。关闭设置时回到这里（0.28.0 起网格页也能进设置）。 */
    private var settingsReturn: Screen? = null

    /** 幻灯片/翻页撞到已加载边界时记下的那一步，等下一页照片到了再执行。 */
    private var pendingStep = 0

    /**
     * 打开相册的请求代号。[openAlbum] 现在要先把清单从磁盘读出来才切屏，连按两本相册时
     * 两个 IO 谁先回来不一定；只放行最后一次，避免旧相册的数据落进新相册的网格。
     */
    private var openAlbumGeneration = 0

    /**
     * 幻灯片自动前进撞到边界时记下的「再找一张图片」，等下一页照片到了再兑现。
     * 和 [pendingStep] 分开：自动前进要跳过视频，补步时不能简单挪一格。
     */
    private var pendingAutoStep = false

    /**
     * 幻灯片随机次序表：一轮之内不重不漏。只由 [autoStep] 消耗，手动 [step] 不碰它。
     * ViewModel 私有；界面仍只通过既有的 shuffleNext（格子号）做预取，不认识这个对象。
     */
    private val slideshowDeck = SlideshowDeck()

    /**
     * 闲置保护：到点就退回相册墙——别让某一家的照片一直挂在客厅电视上。
     * 正在放幻灯片时视为「有人在看」，只重置计时不动作，判定细节在 [IdleGuard.tick]。
     */
    private val idleGuard = IdleGuard {
        val s = _state.value
        if (s.screen is Screen.Viewer || s.screen is Screen.Grid) backToWall()
    }

    private fun loadPhotoPage(album: SharedAlbum, offset: Int) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { repo.photos(album.id, offset, PAGE_SIZE) }
            }
            result.onSuccess { page ->
                // 0.26.3：网络回来时人可能已经退到相册墙、或按开了另一本相册。旧相册的
                // 这一页既不能追加到新相册的列表里，也不能拿去兑现「等下一页」的翻页
                // 待办（会在新相册里凭空跳一张）。同一本相册的网格页 / 看图页都放行。
                if (!photoPageBelongsTo(_state.value.screen, album.id)) return@onSuccess
                _state.update {
                    it.copy(
                        // 视频也进列表：网格里带 ▶ 角标，全屏里直接播。过滤掉的话，
                        // 点开一个播不了的缩略图比看不到它更让人困惑。
                        photos = it.photos + page.items,
                        photosLoading = false,
                        photosFailed = false,
                        hasMorePhotos = page.hasMore,
                    )
                }
                // 0.26.1：新页一到就把「没见过的照片」洗过接到次序表表尾，
                // 预取的格子号也跟着刷新。只在幻灯片随机正开着时做——关播时
                // clear() 过的表不能被一次迟到的续页悄悄重建。sync 是幂等的，
                // autoStep 里还会再调一次，两处都不动已发过的前缀。
                val afterLoad = _state.value
                if (afterLoad.slideshow && afterLoad.slideshowShuffled) {
                    val viewerAlbum = (afterLoad.screen as? Screen.Viewer)?.album
                    if (viewerAlbum != null) {
                        slideshowDeck.sync(viewerAlbum.id, slideshowDeckIds(afterLoad.photos))
                        rollShuffleNext()
                    }
                }
                // 第一页里全是视频（相册可能按日期倒序，最近一段全是视频）时，
                // 用户会看到一片空白却不是空相册，于是自动再取一页。
                val now = _state.value
                if (offset == 0 && now.photos.isEmpty() && page.hasMore) {
                    loadMorePhotos()
                }
                // 幻灯片自动前进撞到「已加载的尾部」时记下的那一步：新照片到了重新找下一张图片。
                // 续页回来一张都没新增（服务端把 hasMore 谎报成 true，或相册真到底但分页给了空页）时，
                // 不能再调 autoStep——它会再次续页，形成「空转续页 + 画面冻在最后一张 + 音乐继续」的死循环。
                // 这时直接干净收尾。
                if (pendingAutoStep) {
                    pendingAutoStep = false
                    if (page.items.isEmpty()) {
                        setSlideshow(false)
                    } else {
                        autoStep()
                    }
                } else if (pendingStep != 0) {
                    // 刚才在末尾按下/轮到的那一步，现在照片到了，补上。
                    val delta = pendingStep
                    pendingStep = 0
                    step(delta)
                }
            }.onFailure { failure ->
                // 0.26.3：换了相册就别把旧相册的失败提示挂到新相册上，也别顺手停掉
                // 新相册的幻灯片（下面那句 setSlideshow(false) 是给「本次续页」用的）。
                if (!photoPageBelongsTo(_state.value.screen, album.id)) return@onFailure
                // 续页失败就把等兑现的那一步作废。留着的话，等下一次加载成功（比如用户
                // 手动点重试）会凭空跳一张，人会觉得幻灯片「自己动了一下」。
                // 0.26.1：这次续页若是幻灯片挂的，取不到就干净收尾，别把画面冻在
                // 最后一张、背景乐还一直放着。
                val slideshowWaiting = pendingAutoStep && _state.value.slideshow
                pendingStep = 0
                pendingAutoStep = false
                if (slideshowWaiting) setSlideshow(false)
                _state.update {
                    it.copy(
                        photosLoading = false,
                        photosFailed = it.photos.isEmpty(),
                        error = describe(failure),
                    )
                }
            }
        }
    }

    /** 网格上那个「重试」。从头再取第一页——失败时本来就没拿到东西，续页没有意义。 */
    fun retryPhotos() {
        val grid = _state.value.screen as? Screen.Grid ?: return
        _state.update { it.copy(photos = emptyList(), photosFailed = false, photosLoading = true) }
        loadPhotoPage(grid.album, 0)
    }

    // --- 后台日期索引（时间轴用） ---

    /** 后台日期索引任务：切相册、回相册墙时取消。 */
    private var dateIndexJob: Job? = null

    private fun startDateIndex(album: SharedAlbum) {
        dateIndexJob?.cancel()
        dateIndexJob = viewModelScope.launch {
            // 首屏还在解码，先让它把第一屏图跑完再抢带宽。
            delay(DATE_INDEX_START_MS)
            runDateIndex(album)
        }
    }

    /**
     * 把整本相册的元数据补齐，供时间轴生成 (年,月) → 索引 的映射。
     *
     * 只拉 JSON、不加载图片，所以代价是「几个请求」而不是「几百张图」。863 张的相册按
     * 300 一页是 3 个来回，局域网 1 秒内。不做这一步，时间轴只能看到已加载的最近一两个月。
     *
     * 三条规矩：
     *  - 用户在看图（Viewer）时暂停——别在他翻照片的时候往列表尾部塞东西；
     *  - 和 loadMorePhotos 共用 photosLoading 单飞，绝不重复取同一段 offset；
     *  - 连续两页空就停：服务端 hasMore 偶尔会撒谎，不停会空转。
     *
     * 拉到**整本**（hasMore 变 false）之后把清单写进缓存——下次进这本相册就不用再拉了。
     * 没拉完不算数：半截清单写进缓存，下次照样得重新拉，白折腾。
     */
    private suspend fun runDateIndex(album: SharedAlbum) {
        if (!_state.value.hasMorePhotos) {
            // 第一页就说没有下一页了：整本已经齐了，直接落缓存。
            writeAlbumCache(album)
            return
        }
        _state.update { it.copy(indexing = true) }
        var complete = false
        try {
            var emptyStreak = 0
            while (currentCoroutineContext().isActive) {
                val current = _state.value
                val grid = current.screen as? Screen.Grid
                if (grid == null) {
                    // 在看图就等他回来接着补；退到相册墙/登录页就收工。
                    if (current.screen is Screen.Viewer) {
                        delay(400)
                        continue
                    }
                    return
                }
                if (grid.album.id != album.id) return
                if (!current.hasMorePhotos) {
                    complete = true
                    return
                }
                if (current.photos.size >= DATE_INDEX_MAX_PHOTOS) return
                if (current.photosLoading) {
                    delay(120)
                    continue
                }

                val offset = current.photos.size
                _state.update { it.copy(photosLoading = true) }
                val page = withContext(Dispatchers.IO) {
                    runCatching { repo.photos(album.id, offset, DATE_INDEX_PAGE_SIZE) }
                }.getOrNull()
                // 0.26.3：取页这段时间里人可能已经换了相册。这一页属于旧相册，别追加到
                // 新相册的列表里；下面那句 photosLoading 复位也不能盖掉新相册的加载态。
                if (!photoPageBelongsTo(_state.value.screen, album.id)) return
                if (page == null) {
                    // 索引失败不打扰用户：网格照常能用，只是时间轴覆盖不全。
                    _state.update { it.copy(photosLoading = false) }
                    return
                }

                emptyStreak = if (page.items.isEmpty()) emptyStreak + 1 else 0
                _state.update {
                    it.copy(
                        photos = it.photos + page.items,
                        photosLoading = false,
                        photosFailed = false,
                        hasMorePhotos = page.hasMore && page.items.isNotEmpty(),
                    )
                }
                if (emptyStreak >= 2) {
                    // 服务端说还有、实际连着两页都是空：就当到头了。
                    complete = true
                    return
                }
                delay(DATE_INDEX_GAP_MS)
            }
        } finally {
            _state.update { it.copy(indexing = false) }
            if (complete) writeAlbumCache(album)
        }
    }

    /** 把当前这一本相册的清单写进缓存。 */
    private suspend fun writeAlbumCache(album: SharedAlbum) {
        // 0.26.3：这里读的是「当前界面上的照片列表」。后台索引跑到收尾时人可能已经换了
        // 相册，拿新相册的列表去写旧相册的缓存，下次进旧相册就会看到别人的照片。
        if (!photoPageBelongsTo(_state.value.screen, album.id)) return
        val address = cacheAddress() ?: return
        val photos = _state.value.photos
        if (photos.isEmpty()) return
        withContext(Dispatchers.IO) {
            albumCache.write(address.display, album, photos, address.baseUrl)
            albumCache.trim(CACHE_LIMIT_BYTES)
        }
        refreshCacheStats()
    }

    /**
     * 把缓存和 NAS 对不上的相册排队重建。
     *
     * 判据是「相册列表里的 photoCount」和「缓存里记的那次快照」比——**不**拿
     * 「缓存里实际存了多少条」去比：照片接口的列表口径和 photoCount 可能不同
     * （视频也混在列表里），两个数直接比会一直判成「变了」，于是每次刷新都重建一遍。
     */
    private fun syncStaleAlbums(albums: List<SharedAlbum>) {
        val address = cacheAddress() ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val manifest = albumCache.readManifest(address.display)
            val stale = albums.filter { manifest[it.id] != it.photoCount }
            if (stale.isEmpty()) return@launch
            _state.update {
                it.copy(cacheRefreshing = true, cacheRebuildTotal = stale.size, cacheRebuildDone = 0)
            }
            stale.forEach { album ->
                rebuildAlbum(album)
                _state.update { it.copy(cacheRebuildDone = it.cacheRebuildDone + 1) }
            }
            _state.update { it.copy(cacheRefreshing = false) }
            refreshCacheStats()
        }
    }

    /**
     * 重新拉整本相册的清单，写进缓存。
     *
     * 只在「NAS 上的张数和缓存里记的不一样」时跑：有人加了或删了照片。
     * 正在看的那本相册会换成新清单——数据本来就变了，显示旧的才是错的。
     */
    private suspend fun rebuildAlbum(album: SharedAlbum) {
        val address = cacheAddress() ?: return
        val photos = fetchWholeAlbum(album) ?: return
        withContext(Dispatchers.IO) {
            albumCache.write(address.display, album, photos, address.baseUrl)
            albumCache.trim(CACHE_LIMIT_BYTES)
        }
        val grid = _state.value.screen as? Screen.Grid
        if (grid != null && grid.album.id == album.id) {
            _state.update { it.copy(photos = photos, hasMorePhotos = false) }
        }
    }

    /** 从头到尾把一本相册的清单拉完。返回 null = 网络失败。 */
    private suspend fun fetchWholeAlbum(album: SharedAlbum): List<SharedPhoto>? {
        val all = ArrayList<SharedPhoto>()
        var offset = 0
        while (all.size < DATE_INDEX_MAX_PHOTOS) {
            val page = withContext(Dispatchers.IO) {
                runCatching { repo.photos(album.id, offset, DATE_INDEX_PAGE_SIZE) }
            }.getOrNull() ?: return null
            if (page.items.isEmpty()) break
            all += page.items
            if (!page.hasMore) break
            offset += page.items.size
        }
        return all
    }

    // --- 缓存管理 ---

    /**
     * 缓存的分区键：规范化后的 NAS 地址（`host:port`）。
     *
     * 用设置里存的地址而不是会话里的：没登录时也要能读到缓存（NAS 关机时看照片）。
     * 走 [FnAddress.parse] 规范化，是为了让「192.168.1.84」和「192.168.1.84:5667」
     * 指向同一份缓存——人怎么填的不该影响缓存能不能用。
     */
    private fun cacheAddress(): FnAddress? = FnAddress.parse(settings.host)

    /**
     * 重新统计缓存占用，设置页显示用。
     *
     * 统计要走磁盘（遍历目录 + Coil 那份的索引），所以放 IO 线程，
     * 结果回来再写进 state。
     */
    fun refreshCacheStats() {
        viewModelScope.launch {
            val stats = withContext(Dispatchers.IO) { albumCache.stats() }
            val (thumbBytes, thumbLimit) = withContext(Dispatchers.IO) {
                image.diskBytes() to image.diskMaxBytes()
            }
            _state.update {
                it.copy(
                    cacheBytes = stats.bytes,
                    cacheAlbumCount = stats.albums,
                    thumbCacheBytes = thumbBytes,
                    thumbCacheLimitBytes = thumbLimit,
                )
            }
        }
    }

    /**
     * 清缓存：元数据索引 + 相册列表 + 缩略图，一次全清。
     *
     * 缩略图也一起清，是因为「省空间」和「下次要重下」得由人自己选——
     * 弹窗里把代价写清楚，点了就执行，不搞「只清一半」那种猜心思的做法。
     */
    fun clearCache() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                albumCache.clearAll()
                image.clearDisk()
            }
            refreshCacheStats()
        }
    }

    fun openViewer(index: Int) {
        val grid = _state.value.screen as? Screen.Grid ?: return
        _state.update {
            it.copy(
                screen = Screen.Viewer(grid.album, index),
                slideshow = false,
                videoPlaying = false,
                lastViewerIndex = index,
                // 信息框不记状态：进大图永远是收起的，要看再按 MENU。
                viewerInfo = false,
            )
        }
        // 这里**不**停背景乐：音乐本来就只在幻灯片里放，而从网格手动点开一张图时
        // 幻灯片必然是关的（歌已经停了）；接着在大图菜单里开幻灯片会走 setSlideshow(true)，
        // 那边 ensureMusicPlaying 对同曲列表只续播不重启——以前在这儿一刀切停掉，
        // 正在放的歌会被打断再从第一首重来。
    }

    /** 大图界面切左上信息框。放在 ViewModel 里是因为 MENU 键只有 Activity 那一层收得到。 */
    fun toggleViewerInfo() {
        if (_state.value.screen !is Screen.Viewer) return
        _state.update { it.copy(viewerInfo = !it.viewerInfo) }
    }

    fun closeViewer() {
        val viewer = _state.value.screen as? Screen.Viewer ?: return
        // 0.24.12 M-5：先让还在淡出的旧屏断声画，再换屏状态。
        signalViewerLeaving()
        _state.update {
            it.copy(
                screen = Screen.Grid(viewer.album),
                slideshow = false,
                videoPlaying = false,
                lastViewerIndex = viewer.index,
            )
        }
        stopSlideshowMusic()
    }

    /** 从网格退回相册墙。照片列表一并放掉——回到墙上看不到它，留着只是占内存。 */
    fun backToWall() {
        // 0.24.12 M-5：含闲置保护到点这条路径，统一先发离开信号再换屏。
        signalViewerLeaving()
        dateIndexJob?.cancel()
        _state.update {
            it.copy(
                screen = Screen.Wall,
                photos = emptyList(),
                slideshow = false,
                videoPlaying = false,
                hasMorePhotos = false,
                indexing = false,
                lastViewerIndex = 0,
            )
        }
        stopSlideshowMusic()
    }

    /** 图片列表边界处停下（开了循环则绕回开头）。 */
    fun step(delta: Int) {
        val current = _state.value
        val viewer = current.screen as? Screen.Viewer ?: return
        // 手动翻页永远一张一张：顺序 / 随机是「幻灯片自动前进」的事，跟人手按左右键无关。
        // 0.26.0 起删掉原先「幻灯片+随机时手动也随机跳」的分支，随机次序表只由 autoStep 消耗。
        val next = viewer.index + delta
        if (next < 0) return
        if (next >= current.photos.size) {
            // 翻到已加载的最后一张，后面还有：先续一页，等照片真到了再挪。
            //
            // 直接把 index 挪到 size 上是不行的——那张照片还不存在，全屏会黑一格，
            // 幻灯片看着就像卡住了。所以记一个待办，由加载回调来兑现。
            if (delta > 0 && current.hasMorePhotos) {
                pendingStep = delta
                loadMorePhotos()
                return
            }
            // 放完了：开了循环就从头再来。手动按右键走到头时也绕回去——
            // 那时人已经知道自己看到底了，绕回去比停住更像正常的相册。
            if (delta > 0 && current.loopSlideshow) {
                _state.update { it.copy(screen = viewer.copy(index = 0)) }
            }
            return
        }
        _state.update { it.copy(screen = viewer.copy(index = next)) }
    }

    /** 次序表取下一张仍能在当前清单里找到的照片；失效 id（删片 / 换页）直接跳过。 */
    private fun takeNextDeckIndex(photos: List<SharedPhoto>): Int? {
        while (slideshowDeck.remaining > 0) {
            val id = slideshowDeck.next() ?: return null
            val index = photos.indexOfFirst { it.id == id && !it.isVideo }
            if (index >= 0) return index
        }
        return null
    }

    /**
     * 幻灯片自动前进：**只走图片**，视频整段跳过。
     *
     * 和 [step] 分开写，是因为两者的取舍正好相反：手动翻页必须能翻到视频（人主动点开就得给看），
     * 自动播放则要把视频排除干净——幻灯片停在一个待播态的视频上，人只会以为卡住了。
     *
     * 边界情况：已加载的后面全是视频就先续页，续不到再看开没开循环，都没辙就停在原地。
     */
    fun autoStep() {
        val current = _state.value
        if (!current.slideshow) return
        val viewer = current.screen as? Screen.Viewer ?: return
        val photos = current.photos
        if (photos.isEmpty()) return

        // 一册里一张图都没有（纯视频相册）：幻灯片放着也没意义，直接收掉（图片+音乐一起停）。
        // 后面还有没取完的页就先续一页再判——不能只凭第一页下结论。
        if (photos.none { !it.isVideo }) {
            if (current.hasMorePhotos) {
                pendingAutoStep = true
                loadMorePhotos()
            } else {
                setSlideshow(false)
            }
            return
        }

        // 随机：按开播时排好的次序表发下一张，一轮之内不重不漏。
        // 每次前进顺手把「已经取回来但开播时还没有」的照片补进表尾（分页 / 首批 / 缓存
        // 整本灌入 / 后台重建都走这里），已发前缀不动，正在预取的下一张也不会失效。
        if (current.slideshowShuffled) {
            slideshowDeck.sync(viewer.album.id, slideshowDeckIds(photos))
            val next = takeNextDeckIndex(photos)
            if (next != null) {
                _state.update { it.copy(screen = viewer.copy(index = next)) }
                rollShuffleNext()
                // 0.26.1：表快见底就提前把下一页取回来，别等发完那一刻干等网络。
                // 这里不挂 pendingAutoStep——表里还有牌，新页回来照常接着发即可，
                // 由 loadPhotoPage 的回调把新照片补进表尾。
                if (shouldPrefetchDeck(
                        remaining = slideshowDeck.remaining,
                        hasMorePhotos = current.hasMorePhotos,
                        photosLoading = current.photosLoading,
                    )
                ) {
                    loadMorePhotos()
                }
                return
            }
            // 表发完了：先问纯函数该怎么办。相册还有页时一律先续页，哪怕循环开着
            // 也不许拿头一页重洗——那会让「梦里水乡」这类大相册的后半本永远播不到。
            // 整本取完之后，才按循环开关决定重洗一轮还是图片和背景乐一起收尾。
            when (
                decideDeckExhaustion(
                    hasMorePhotos = current.hasMorePhotos,
                    loopSlideshow = current.loopSlideshow,
                    canRestart = slideshowDeck.canRestart,
                )
            ) {
                DeckExhaustion.LOAD_MORE -> {
                    pendingAutoStep = true
                    loadMorePhotos()
                }
                DeckExhaustion.RESTART_ROUND -> {
                    slideshowDeck.restartRound()
                    val again = takeNextDeckIndex(photos)
                    if (again != null) {
                        _state.update { it.copy(screen = viewer.copy(index = again)) }
                        rollShuffleNext()
                    } else {
                        setSlideshow(false)
                    }
                }
                DeckExhaustion.FINISH -> setSlideshow(false)
            }
            return
        }

        var next = viewer.index + 1
        while (next < photos.size && photos[next].isVideo) next++
        if (next < photos.size) {
            _state.update { it.copy(screen = viewer.copy(index = next)) }
            return
        }
        if (current.hasMorePhotos) {
            pendingAutoStep = true
            loadMorePhotos()
            return
        }
        if (current.loopSlideshow) {
            val first = photos.indexOfFirst { !it.isVideo }
            if (first >= 0) {
                _state.update { it.copy(screen = viewer.copy(index = first)) }
                return
            }
        }
        // 到底了：循环关 → 图片和背景乐一起干净收尾（人没开循环就是想「放完即停」）。
        // 之前这里只把图冻在最后一张、幻灯片状态却没关，于是音乐继续放、画面卡住，
        // 看着像卡 bug。现在走 setSlideshow(false)，它会顺带 stopSlideshowMusic()。
        setSlideshow(false)
    }

    fun setSlideshow(on: Boolean) {
        if (!on) {
            // 0.26.1：把「等下一页到了再自动前进」的待办一并作废。留着的话，一次
            // 迟到的续页回调会替下一轮开播先跳一张，看着像刚点播放就自己换图。
            pendingAutoStep = false
            _state.update { it.copy(slideshow = false) }
            stopSlideshowMusic()
            slideshowDeck.clear()
            rollShuffleNext()
            return
        }
        val current = _state.value
        val viewer = current.screen as? Screen.Viewer
        _state.update { it.copy(slideshow = true) }
        // 开播这一刻按当时已知的照片重排一次；之后分页追加快照由 autoStep 里的 sync 补。
        if (current.slideshowShuffled && viewer != null) {
            slideshowDeck.start(viewer.album.id, slideshowDeckIds(current.photos))
        }
        rollShuffleNext()
        // 幻灯片只放照片：当前停在视频上就先往后挪到第一张图，别让人按了播放却不见动静。
        if (viewer != null && current.photos.getOrNull(viewer.index)?.isVideo == true) {
            autoStep()
        }
        // 背景乐：有勾选就按曲库顺序循环播放（没扫过曲库会先补一次扫描）。
        if (current.musicSelected.isNotEmpty()) {
            ensureMusicPlaying(current.musicSelected)
        }
        // 歌词轮询：幻灯片开了且歌词开关是开的就起循环；关幻灯片不用管——
        // 循环自己每个 tick 都检查条件，不满足就清空状态并退出（自终止）。
        if (_state.value.lyricsEnabled) maybeStartLyricsLoop()
    }

    fun setSlideshowShuffled(on: Boolean) {
        settings.slideshowShuffled = on
        _state.update { it.copy(slideshowShuffled = on) }
        val current = _state.value
        val viewer = current.screen as? Screen.Viewer
        when {
            // 切到随机：正在放也要立刻重新排一次，否则还是按老顺序往下走。
            on && current.slideshow && viewer != null ->
                slideshowDeck.start(viewer.album.id, slideshowDeckIds(current.photos))
            !on -> slideshowDeck.clear()
        }
        rollShuffleNext()
    }

    /**
     * 背景乐播放顺序：顺序 / 随机。
     *
     * 只在**真的正在出声**时立刻按新顺序重排；只是勾着歌但没在放（幻灯片没开、或已暂停）
     * 就等下次开播再生效——用户改个顺序，不该顺手把音乐拉起来。
     */
    fun setMusicShuffled(on: Boolean) {
        settings.musicShuffled = on
        _state.update { it.copy(musicShuffled = on) }
        if (musicPlayer.playing.value) ensureMusicPlaying(_state.value.musicSelected)
    }

    /**
     * 歌词开关：开 = 幻灯片播放时右上角显示 LRC 歌词，关 = 一律不显示。
     *
     * 关掉时立刻把轮询循环停了并清掉歌词状态——不能等循环自己发现，那样浮层会
     * 多挂最多 100ms，切开关的瞬间能看见词一闪。
     */
    fun setLyricsEnabled(on: Boolean) {
        settings.lyricsEnabled = on
        _state.update { it.copy(lyricsEnabled = on) }
        if (on && _state.value.slideshow) {
            maybeStartLyricsLoop()
        } else if (!on) {
            // stop() 内部一次做完「掐循环 + 清 doc/index」，和原来三行等价；
            // 不能等循环自己发现，那样浮层会多挂最多 100ms。
            clearLyrics()
        }
    }

    /**
     * 启动歌词轮询（已活着就不再起第二个）。
     *
     * 循环体在 `engine/impl/DefaultLyricsTicker`：节奏仍是每 100ms 读一次播放器的
     * 曲目 id 和位置（主线程纯字段读），只写两个低频 StateFlow；不满足
     * `幻灯片开 + 歌词开` 时自己清状态退出。这里只做转发，不改节奏、不改写入条件。
     */
    private fun maybeStartLyricsLoop() = ticker.start()

    /** 停循环并立刻清掉歌词文档与句下标（关开关时调用）。 */
    private fun clearLyrics() = ticker.stop()

    /**
     * 把次序表里「下一张」的格子号写进 state 供大图页预取。
     *
     * 预取只覆盖当前张的左右邻居，随机跳页的目标图十有八九不在缓存里，等换图那一刻
     * 才开始解码就要干等几百毫秒。这里只读 peek、不推进表，所以预取的和真正要播的
     * 永远是同一张；手动翻页不消耗次序表，界面自己会连 index+1 一起预取。
     */
    private fun rollShuffleNext() {
        val current = _state.value
        val photos = current.photos
        val viewer = current.screen as? Screen.Viewer
        if (!current.slideshow || !current.slideshowShuffled || photos.size < 2 || viewer == null) {
            if (current.shuffleNext != null) _state.update { it.copy(shuffleNext = null) }
            return
        }
        val nextIndex = slideshowDeck.peek()
            ?.let { id -> photos.indexOfFirst { it.id == id && !it.isVideo } }
            ?.takeIf { it >= 0 }
        if (nextIndex != current.shuffleNext) {
            _state.update { it.copy(shuffleNext = nextIndex) }
        }
    }

    fun setPreviewOriginal(on: Boolean) {
        settings.previewOriginal = on
        _state.update { it.copy(previewOriginal = on) }
    }

    /** 全屏里那个视频是不是在播。ViewerScreen 随播放状态回传，闲置保护据此判断。 */
    fun setVideoPlaying(on: Boolean) = _state.update { it.copy(videoPlaying = on) }

    /** 一个视频播完了：正在放幻灯片就接着往下走（跳过后续视频），手动看的就停在原处等用户操作。 */
    fun onVideoEnded() {
        if (_state.value.slideshow) autoStep()
    }

    fun openSettings() {
        // 0.28.0：网格页也能按菜单键进设置，所以要记住入口——返回时回到原处。
        settingsReturn = _state.value.screen
        _state.update { it.copy(screen = Screen.Settings) }
        // 进设置页顺手把缓存占用统计一遍：数字要准，不能是进页面那一刻的旧值。
        refreshCacheStats()
    }

    fun closeSettings() {
        val target = settingsReturnTarget(settingsReturn)
        settingsReturn = null
        _state.update { it.copy(screen = target) }
    }

    fun setSlideSeconds(seconds: Int) {
        settings.slideSeconds = seconds
        _state.update { it.copy(slideSeconds = seconds) }
    }

    fun setSlideFade(on: Boolean) {
        settings.slideshowFade = on
        _state.update { it.copy(slideFade = on) }
    }

    fun setSlideZoom(on: Boolean) {
        settings.slideshowZoom = on
        _state.update { it.copy(slideZoom = on) }
    }

    fun setSlideFill(on: Boolean) {
        settings.slideshowFill = on
        _state.update { it.copy(slideFill = on) }
    }

    fun setLoopSlideshow(on: Boolean) {
        settings.loopSlideshow = on
        _state.update { it.copy(loopSlideshow = on) }
    }

    // ----------------------------------------------------------------- 幻灯片背景音乐

    /** 冷启读回来源：存的是飞牛但没登录时退回本地，避免一进弹窗就是空列表。 */
    private fun savedMusicSourceKind(): MusicSourceKind =
        musicPreferences.sourceKind
            .takeIf { it != MusicSourceKind.FN || fnMusicAuth.loggedIn }
            ?: MusicSourceKind.LOCAL

    /**
     * 重新扫描 / 重新拉取当前来源的曲库。飞牛源的「刷新」只重拉歌单列表，
     * 曲目步由用户再按 → 进一次，避免切账号后停留在已失效的旧歌单里。
     */
    fun refreshMusic() {
        if (_state.value.musicSourceKind == MusicSourceKind.FN) {
            loadFnMusicGroups()
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(musicScanning = true) }
            val list = musicScanner.scan()
            _state.update { it.copy(musicEntries = list, musicScanning = false) }
        }
    }

    /** 勾选 / 取消勾选一首曲目，并持久化。放幻灯片时立即热更新背景乐。 */
    fun toggleMusic(id: String) {
        val current = _state.value
        val selected = if (id in current.musicSelected) current.musicSelected - id else current.musicSelected + id
        applyMusicSelection(selected, current)
    }

    /**
     * 一键全选 / 取消全选。
     *
     * 判据只用「当前曲库里真实存在的那批 id」（[AppState.musicEntries]），不拿
     * `musicSelected.size` 去比 `musicEntries.size`：存下来的勾选里可能夹着文件被删 / 改名
     * 后留下的幽灵 id，按数量比会永远判不成「已全选」，按钮就只会一路「全选」再也取消不掉。
     * 取消时同样只减这批 id，幽灵原样留着（它们既不显示也不参与播放，无需惊动）。
     */
    fun toggleMusicAll() {
        val current = _state.value
        // 飞牛歌单步勾的是歌单、其它步勾的是曲目，统一由 selectableIds() 给口径，
        // 且只认「当前这一屏真实存在的项」——幽灵 id 既不显示也不参与播放。
        val visible = current.selectableIds()
        if (visible.isEmpty()) return
        val selected = if (current.musicSelected.containsAll(visible)) {
            current.musicSelected - visible
        } else {
            current.musicSelected + visible
        }
        applyMusicSelection(selected, current)
    }

    /** 勾选集合变了之后的三件套：落盘、回写状态、幻灯片在放就立刻换队列。 */
    private fun applyMusicSelection(selected: Set<String>, current: AppState) {
        settings.musicSelection = selected
        _state.update { it.copy(musicSelected = selected) }
        if (current.slideshow) ensureMusicPlaying(selected)
    }

    /** 权限授予结果回写：拿到就立刻扫一次曲库。 */
    fun onMusicPermissionResult(granted: Boolean) {
        _state.update { it.copy(musicPermission = granted) }
        if (granted) refreshMusic()
    }

    // ----------------------------------------------------------------- 双源曲库（P4）

    /**
     * 来源切换：校验 → 互斥停播 → 持久化 → 换状态 → 拉新源的列表。
     * 两个源的曲目 id 前缀天然隔离（`file:` / `fn:`），所以勾选集合不清空，
     * 切回来时上次的勾还在，与「设置项属于这台电视」的既有语义一致。
     */
    fun setMusicSource(kind: MusicSourceKind) {
        val current = _state.value
        if (kind == current.musicSourceKind) return
        if (kind == MusicSourceKind.FN && !fnMusicAuth.loggedIn) {
            // 不弹 toast 抢焦点，把话写在弹窗里；用户从设置页回来自然看得到。
            _state.update { it.copy(fnMusicError = FN_MUSIC_NEED_LOGIN) }
            return
        }
        musicPlayer.switch(kind)
        musicPreferences.sourceKind = kind
        _state.update {
            it.copy(
                musicSourceKind = kind,
                fnMusicCurrentGroupId = null,
                fnMusicCurrentGroupTitle = "",
                fnMusicTracks = emptyList(),
                fnMusicError = null,
            )
        }
        when (kind) {
            // 切到飞牛只拉歌单列表，**绝不自动起播**：背景乐一律由幻灯片开演驱动，
            // 也绝不因为切源就自己把幻灯片打开。
            MusicSourceKind.FN -> loadFnMusicGroups()
            // 切回本地时若幻灯片正在放，按本地既有链路恢复背景乐（P4 之前就有）。
            MusicSourceKind.LOCAL -> if (current.slideshow) {
                ensureMusicPlaying(current.musicSelected)
            }
        }
    }

    /** 飞牛第一级：歌单列表。loading 中不重复发；失败只写弹窗内的短提示。 */
    private fun loadFnMusicGroups() {
        if (_state.value.fnMusicLoading) return
        if (!fnMusicAuth.loggedIn) {
            _state.update { it.copy(fnMusicError = FN_MUSIC_NEED_LOGIN) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(fnMusicLoading = true, fnMusicError = null) }
            val result = runCatching { fnCatalog.listGroups() }
            _state.update { st ->
                result.fold(
                    onSuccess = { groups ->
                        st.copy(
                            fnMusicLoading = false,
                            fnMusicGroups = groups,
                            // 换账号后旧歌单可能已不属于新账号：退回第一级。
                            fnMusicCurrentGroupId = null,
                            fnMusicCurrentGroupTitle = "",
                            fnMusicTracks = emptyList(),
                        )
                    },
                    onFailure = { st.copy(fnMusicLoading = false, fnMusicError = FN_MUSIC_LOAD_FAILED) },
                )
            }
        }
    }

    /** 歌单行按 →：拉该歌单的曲目并进入曲目步。 */
    fun openFnMusicGroup(groupId: String) {
        val title = _state.value.fnMusicGroups.firstOrNull { it.id == groupId }?.title.orEmpty()
        musicPreferences.lastPlaylistId = groupId
        if (_state.value.fnMusicLoading) return
        viewModelScope.launch {
            _state.update { it.copy(fnMusicLoading = true, fnMusicError = null) }
            val result = runCatching { fnCatalog.listTracks(groupId) }
            _state.update { st ->
                result.fold(
                    onSuccess = { tracks ->
                        st.copy(
                            fnMusicLoading = false,
                            fnMusicTracks = tracks,
                            fnMusicCurrentGroupId = groupId,
                            fnMusicCurrentGroupTitle = title,
                        )
                    },
                    onFailure = { st.copy(fnMusicLoading = false, fnMusicError = FN_MUSIC_LOAD_FAILED) },
                )
            }
        }
    }

    /**
     * 歌单行按 OK：勾选 / 取消勾选整张歌单，行尾出 ✓。
     *
     * 与本地勾曲目共用 [AppState.musicSelected] 和 `settings.musicSelection`：
     * 歌单 id 是 `fnpl:`、曲目 id 是 `file:` / `fn:`，前缀天然隔离，切源互不干扰。
     * 这里**不拉曲目、不播放、也不开幻灯片**——0.20.0 的「整单播放」是
     * 无条件 `musicPlayer.play()`，看图页静止时点一下就单独出声，正是要去掉的行为。
     *
     * 背景乐只在幻灯片开演时由 [ensureMusicPlaying] 把勾选的歌单展开成队列；
     * 幻灯片已经在放时才立刻换队列（与本地勾曲目的 [applyMusicSelection] 一致）。
     */
    fun toggleFnMusicGroup(groupId: String) {
        val current = _state.value
        val selected = if (groupId in current.musicSelected) {
            current.musicSelected - groupId
        } else {
            current.musicSelected + groupId
        }
        applyMusicSelection(selected, current)
    }

    /** 曲目步按 ←/Back：退回歌单列表；曲目列表保留，播放队列不必重建。 */
    fun backToFnMusicGroups() {
        _state.update { it.copy(fnMusicCurrentGroupId = null, fnMusicCurrentGroupTitle = "") }
    }

    fun dismissFnMusicError() = _state.update { it.copy(fnMusicError = null) }

    /**
     * 让背景乐与当前勾选集合对齐：有勾选就按曲库顺序循环播放，没有就停。
     * 曲库还没扫过、或者存下来的勾选一条都对不上曲库（文件挪了/路径写法变了）时，
     * 先补一次扫描再放——「勾了歌却一声不响」里有一部分就是这种情况：
     * 以前这里一发现对不上就直接 stop，用户看到的只有沉默。
     */
    private fun ensureMusicPlaying(selected: Set<String>) {
        Log.d(
            TAG_DIAG,
            "ensureMusicPlaying: selected=${selected.size}, kind=${_state.value.musicSourceKind}, " +
                "slideshow=${_state.value.slideshow}",
        )
        if (selected.isEmpty()) {
            musicPlayer.stop()
            return
        }
        val current = _state.value
        if (current.musicSourceKind == MusicSourceKind.FN) {
            Log.d(TAG_DIAG, "FN branch: hasFnSelection=${hasFnSelection(selected)}")
            // 飞牛勾的是整张歌单（`fnpl:`），必须展开成曲目队列；曲目列表刻意不落盘
            // （歌单会变、曲目会删），冷启 / 重进看图页后 fnMusicTracks 必然为空。
            // 0.20.0 在这里看到「勾选对不上曲目」就直接 stop，于是「勾好歌单 →
            // 播放幻灯片」永远静音——这正是本次要修的根因。
            // ⚠️ 必须排在 orderedSelection 快捷路径之前：否则选择集里同时混着
            // 旧版残留的 `fn:` 曲目时会只播那几首，把新勾的歌单整个丢掉。
            if (hasFnSelection(selected)) ensureFnPlaylistMusic() else musicPlayer.stop()
            return
        }
        val entries = current.visibleMusicEntries()
        val tracks = orderedSelection(entries, selected)
        if (tracks.isNotEmpty()) {
            musicPlayer.play(tracks)
            return
        }
        if (!musicScanner.hasPermission()) {
            // 连读音乐的权限都没有，扫也是空。别反复扫，直接停。
            musicPlayer.stop()
            return
        }
        viewModelScope.launch {
            // 补扫描要给界面一个「正在扫」的信号：用户点了播放、列表却半天不响，
            // 没有这个指示就像没反应。扫完接着放。
            _state.update { it.copy(musicScanning = true) }
            val list = musicScanner.scan()
            _state.update { it.copy(musicEntries = list, musicScanning = false) }
            if (list.isNotEmpty()) musicPlayer.play(orderedSelection(list, selected))
        }
    }

    /** 展开飞牛歌单的在飞 Job：连续勾选时用最新选择集重算，不并发起播。 */
    private var fnQueueJob: Job? = null

    /**
     * 把勾选的歌单展开成一份播放队列——**只在幻灯片开演时**由 [ensureMusicPlaying] 调用，
     * 对应本地源那条「补扫描再放」的兜底。
     *
     * 每个 `fnpl:` 发一次 `listTracks`，按勾选顺序合并、按 id 去重、剔掉不可播的；
     * 随机模式整队洗一遍（只在开播时洗一次，播放期间顺序稳定，所以左右键
     * [skipMusicTrack] 的「上一首 / 下一首」照常可用）。
     *
     * 兼容性：0.20.0 的「整单播放」会把 `fn:` 曲目 id 直接写进选择集。升级后这些
     * id 不再对应任何歌单，这里从当前曲目列表里捞回来一起排队，避免老用户静音。
     *
     * 异步回来时若幻灯片已关、或来源已切回本地，就别再起播
     * （`setSlideshow(false)` 已经停过一次，重复起播会变成「关了还在响」）。
     *
     * 0.21.3：拉取结果改为 [FnGroupTracks]（失败带 cause），由 [buildFnPlayQueue]
     * 合成队列。全失败时写 `fnMusicError` 给用户可见提示 —— 以前 `runCatching{}.orEmpty()`
     * 把「服务端读不到曲目」压成一个空列表后直接 stop，用户只看到一声不响。
     *
     * 0.21.4：NAS 上飞牛音乐后端会短暂崩掉再起来（nginx 502 / 100001），只拉一次
     * 就永久静音太脆。全失败时按 3s / 6s 退避补拉两轮，同时把提示写进全局
     * `error` —— `ErrorToast` 只看 `error`，只写 `fnMusicError` 在看图页是看不见的。
     */
    private fun ensureFnPlaylistMusic() {
        fnQueueJob?.cancel()
        fnQueueJob = viewModelScope.launch {
            val requested = _state.value.musicSelected
            val groupIds = requested.filter { fnPlaylistGuidOrNull(it) != null }
            val looseTrackIds = requested.filter { fnTrackGuidOrNull(it) != null }

            var attempt = 1
            var queue = loadFnQueue(groupIds, looseTrackIds)
            while (shouldRetryFnQueue(queue, attempt, stillWaitingForFnMusic(requested))) {
                val waitMs = fnQueueRetryDelayMs(attempt) ?: break
                Log.d(TAG_DIAG, "attempt $attempt allFailed, retry in ${waitMs}ms")
                delay(waitMs)
                attempt++
                queue = loadFnQueue(groupIds, looseTrackIds)
            }
            Log.d(
                TAG_DIAG,
                "queue: attempts=$attempt, tracks=${queue.tracks.size}, " +
                    "hasFailure=${queue.hasFailure}, allFailed=${queue.allFailed}",
            )
            if (queue.allFailed) {
                // 一张歌单都没拉到：音乐弹窗常驻提示 + 看图页 toast。
                // 只写 fnMusicError 时 ErrorToast 看不见，用户对着一片沉默猜不到原因。
                _state.update {
                    it.copy(
                        fnMusicError = FN_MUSIC_TRACKS_FAILED,
                        error = FN_MUSIC_TRACKS_TOAST,
                    )
                }
            } else {
                // 重试成功（或服务自己恢复）：清掉上一次留下的同类红字。
                _state.update { st ->
                    if (st.fnMusicError == FN_MUSIC_TRACKS_FAILED) {
                        st.copy(fnMusicError = null)
                    } else st
                }
            }
            // 回写让曲目步 / visibleMusicEntries 有内容，但不动 currentGroupId：
            // 用户仍然停在歌单步，不该被强行跳进某一张歌单。
            if (queue.tracks.isNotEmpty()) {
                _state.update { st ->
                    if (st.fnMusicTracks.isEmpty()) st.copy(fnMusicTracks = queue.tracks) else st
                }
            }

            val latest = _state.value
            if (!latest.slideshow || latest.musicSourceKind != MusicSourceKind.FN) {
                Log.d(TAG_DIAG, "abort: slideshow off or source changed")
                musicPlayer.stop()
                return@launch
            }
            val ordered = if (settings.musicShuffled) queue.tracks.shuffled() else queue.tracks
            if (ordered.isEmpty()) {
                Log.w(TAG_DIAG, "empty queue -> stop (hasFailure=${queue.hasFailure})")
                musicPlayer.stop()
            } else {
                musicPlayer.play(ordered)
            }
        }
    }

    /** 退避期间用户可能关了幻灯片、切回本地或改了勾选，任何一样变了就别再发请求。 */
    private fun stillWaitingForFnMusic(requested: Set<String>): Boolean {
        val st = _state.value
        return st.slideshow && st.musicSourceKind == MusicSourceKind.FN &&
            st.musicSelected == requested
    }

    /** 一轮「按勾选拉歌单 → 合成队列」。失败作为 [FnGroupTracks.Failed] 往下传。 */
    private suspend fun loadFnQueue(
        groupIds: List<String>,
        looseTrackIds: List<String>,
    ): FnPlayQueue {
        // 失败要作为「失败」往下传，不再用空列表冒充成功：合成 / 判空交给
        // buildFnPlayQueue()，那里有可单测的 hasFailure / allFailed 两个口径。
        val results = groupIds.map { id ->
            runCatching { fnCatalog.listTracks(id) }.fold(
                onSuccess = { FnGroupTracks.Ok(id, it) },
                onFailure = { e ->
                    Log.w(TAG_DIAG, "listTracks($id) FAILED: ${e.message}", e)
                    FnGroupTracks.Failed(id, e)
                },
            )
        }

        // 升级兼容：0.20.0 落盘的 `fn:` 曲目 id 不属于任何歌单。先从当前曲目
        // 列表捞，捞不到再按上次停留的歌单补拉一次；补拉失败也只记日志。
        val loose = _state.value.fnMusicTracks.filter { it.id in looseTrackIds }.toMutableList()
        if (loose.isEmpty() && looseTrackIds.isNotEmpty()) {
            val legacyId = musicPreferences.lastPlaylistId
                ?.takeIf { fnPlaylistGuidOrNull(it) != null }
            if (legacyId != null) {
                loose += runCatching { fnCatalog.listTracks(legacyId) }
                    .onFailure { Log.w(TAG_DIAG, "legacy listTracks($legacyId) FAILED", it) }
                    .getOrNull().orEmpty()
                    .filter { it.id in looseTrackIds }
            }
        }
        return buildFnPlayQueue(results, loose)
    }

    /** 关掉背景乐（幻灯片停止时调用）。 */
    private fun stopSlideshowMusic() = musicPlayer.stop()

    /**
     * 幻灯片播放中按 ←/→ 切上一首 / 下一首。
     *
     * 返回 true 表示这次按键归音乐（真的换了一首），看图页据此**吃掉**这个按键：
     * 不翻照片、不重置幻灯片的计时——切歌是「换背景乐」，不是「换照片」。
     * 没有可切的曲目（没勾歌 / 幻灯片已结束）时返回 false，按键照旧翻页。
     *
     * 只看 [AppState.slideshow]，「是否暂停」交给看图页判断：那个标记本来就在它手里
     * （本地 state），而暂停时按左右仍然是翻照片——「停下来细看」和「切歌」不该抢同一个键。
     */
    fun skipMusicTrack(delta: Int): Boolean {
        if (!_state.value.slideshow) return false
        return musicPlayer.skip(delta)
    }

    /**
     * 幻灯片「暂停/继续」时同步背景乐：暂停就一起停、继续就一起放。
     * OK 键只切本地 [slideshowPaused] 标记拦翻页，音乐得走这边真正 pause/resume。
     */
    fun setSlideshowPaused(paused: Boolean) {
        if (paused) musicPlayer.pause() else musicPlayer.resume()
    }

    override fun onCleared() {
        // 歌词节拍器持有 100ms 的 Job 和在飞的 LRC 解析 Job，先掐干净。
        ticker.cancel()
        // 原 onCleared 只有这一行，保留：ExoPlayer 绑主线程 Looper，必须 release。
        // ImageEngine.close() 刻意不调用，原因见 di/AppContainer 文件头。
        musicPlayer.release()
        super.onCleared()
    }

    /** 任意按键都来这一下，把闲置计时归零。放在 MainActivity 的预览按键监听里统一调用。 */
    fun noteInteraction() = idleGuard.noteInteraction()

    /**
     * 由一个常驻的定时器每 15 秒点一次。闲置判定与计时都在 [IdleGuard] 里，
     * 这里只负责把当前状态喂进去。
     */
    fun tickIdle() {
        val s = _state.value
        idleGuard.tick(idleMinutes = s.idleMinutes, busy = s.slideshow || s.videoPlaying)
    }

    fun setIdleMinutes(minutes: Int) {
        settings.idleMinutes = minutes
        _state.update { it.copy(idleMinutes = minutes) }
    }

    fun setBackdropWall(on: Boolean) {
        settings.backdropWall = on
        _state.update { it.copy(backdropWall = on) }
    }

    fun setBackdropGrid(on: Boolean) {
        settings.backdropGrid = on
        _state.update { it.copy(backdropGrid = on) }
    }

    fun setFocusZoom(on: Boolean) {
        settings.focusZoom = on
        _state.update { it.copy(focusZoom = on) }
    }

    /** 焦点框描边颜色。存十六进制（带 alpha），UI 渲染时由 [com.kijeye.fnshare.ui.parseRingColor] 还原。 */
    fun setFocusRingColor(hex: String) {
        settings.focusRingColorHex = hex
        _state.update { it.copy(focusRingColorHex = hex) }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun signOut() {
        repo.signOut()
        stopSlideshowMusic()
        // 缓存里存着拍摄地点和文件名——退出登录就该一起清掉，别留在电视上。
        albumCache.clearAll()
        refreshCacheStats()
        // 显示设置是属于这台电视的，不属于哪个账号，退出登录后保留。
        _state.update {
            AppState(
                starting = false,
                screen = Screen.Login,
                slideSeconds = it.slideSeconds,
                slideFade = it.slideFade,
                slideZoom = it.slideZoom,
                slideFill = it.slideFill,
                // 少这一行的话，退出登录后设置页会显示「循环：关」，而本地存的还是开——
                // 下次开机照着存储放，和刚才看到的开关不一致。
                loopSlideshow = it.loopSlideshow,
                idleMinutes = it.idleMinutes,
                backdropWall = it.backdropWall,
                backdropGrid = it.backdropGrid,
                focusZoom = it.focusZoom,
                focusRingColorHex = it.focusRingColorHex,
                slideshowShuffled = it.slideshowShuffled,
                previewOriginal = it.previewOriginal,
                lastHost = it.lastHost,
                lastUser = it.lastUser,
                musicEntries = it.musicEntries,
                musicSelected = it.musicSelected,
                musicShuffled = it.musicShuffled,
                musicScanning = it.musicScanning,
                musicPermission = it.musicPermission,
                lyricsEnabled = it.lyricsEnabled,
                // 音乐子账户与来源不属于相册账号：退出相册登录不该把音乐踢掉。
                musicSourceKind = it.musicSourceKind,
                fnMusicLoggedIn = it.fnMusicLoggedIn,
                fnMusicAccount = it.fnMusicAccount,
                fnMusicSavedUser = it.fnMusicSavedUser,
                fnMusicBaseUrl = it.fnMusicBaseUrl,
                fnMusicGroups = it.fnMusicGroups,
                fnMusicTracks = it.fnMusicTracks,
                fnMusicCurrentGroupId = it.fnMusicCurrentGroupId,
                fnMusicCurrentGroupTitle = it.fnMusicCurrentGroupTitle,
            )
        }
    }

    private fun describe(failure: Throwable): String = when (failure) {
        is FnTwoFactorRequiredException -> "这个账号开了二次验证，请在飞牛 NAS 上给该账号关闭二次验证后再登录"
        else -> failure.message ?: failure::class.java.simpleName
    }

    /**
     * 事件分发（结构化重构第 6 批）。
     *
     * 该代码是从 `MainActivity.kt:208-328` 与 `ui/ViewerScreen.kt:139-171` 的
     * `model::xxx` / `model.xxx(...)` 回调**逐一对照**提取的：一个分支 = 原来一次调用，
     * 参数顺序、默认值（登录的 accessCode 固定传 `""`）、调用时机全部照抄。
     * 本方法只做 `when → 现有方法` 的分派，**不写任何业务逻辑**，下面一千多行的
     * 方法体一行未动。UI 从此只认识 `AppEvent`，不再认识 ViewModel 的方法表。
     *
     * 唯一不走事件的写操作是 `skipMusicTrack(delta)`：它要返回「有没有真的切到下一首」，
     * 遥控器据此决定要不要退回翻页，属于按键逻辑的一部分，仍由 `ViewerActions.onSkipMusic`
     * 直接调。`AppEvent.SkipMusic` 在这里照样分派（丢弃返回值），保持契约穷尽。
     *
     * 对应的生命周期清理应该在原文件的哪个位置调用：
     * 【无需清理】。`onEvent` 是同步分派，不开协程、不排队、不去重、不重放；
     * 需要 IO 的动作由各现有方法自己在 `viewModelScope` 里切 `Dispatchers.IO`。
     * `AppViewModel.onCleared()` 不为此新增任何 cancel——原来那几行 release/cancel 保持不变。
     */
    override fun onEvent(event: AppEvent) {
        // 用 when 语句而不是 `= when` 表达式：`skipMusicTrack` 返回 Boolean，
        // 表达式体会把整个 when 推断成 Any，与接口要求的 Unit 冲突。语句形式下
        // 分支返回值自然被丢弃，不必写 `.let { Unit }` 之类制造无用表达式的补丁。
        when (event) {
            // --- 登录 ---
            is AppEvent.SignIn -> signIn(event.host, event.user, event.password, "")
            is AppEvent.SubmitAccessCode -> submitAccessCode(event.code)
            AppEvent.DismissAccessCode -> dismissAccessCode()
            AppEvent.DismissError -> dismissError()

            // --- 相册墙 / 网格 / 设置 ---
            is AppEvent.OpenAlbum -> openAlbum(event.album)
            AppEvent.OpenSettings -> openSettings()
            AppEvent.CloseSettings -> closeSettings()
            AppEvent.BackToWall -> backToWall()
            AppEvent.RefreshAlbums -> refreshAlbums()
            AppEvent.ClearCache -> clearCache()
            AppEvent.SignOut -> signOut()
            AppEvent.LoadMorePhotos -> loadMorePhotos()
            AppEvent.RetryPhotos -> retryPhotos()
            is AppEvent.OpenViewer -> openViewer(event.index)

            // --- 设置项 ---
            is AppEvent.IdleMinutes -> setIdleMinutes(event.minutes)
            is AppEvent.BackdropWall -> setBackdropWall(event.on)
            is AppEvent.BackdropGrid -> setBackdropGrid(event.on)
            is AppEvent.FocusZoom -> setFocusZoom(event.on)
            is AppEvent.FocusRingColor -> setFocusRingColor(event.hex)

            // --- 飞牛音乐子账户 ---
            is AppEvent.FnMusicSignIn -> fnMusicSignIn(
                event.user, event.password, event.accessCode,
            )
            AppEvent.FnMusicSignOut -> fnMusicSignOut()
            AppEvent.FnMusicDismissLoginError -> dismissFnMusicLoginError()

            // --- 看图页 / 幻灯片 ---
            is AppEvent.Step -> step(event.delta)
            AppEvent.AutoStep -> autoStep()
            is AppEvent.SkipMusic -> skipMusicTrack(event.delta)
            is AppEvent.Slideshow -> setSlideshow(event.on)
            is AppEvent.SlideshowPaused -> setSlideshowPaused(event.paused)
            is AppEvent.SlideSeconds -> setSlideSeconds(event.seconds)
            is AppEvent.SlideFade -> setSlideFade(event.on)
            is AppEvent.SlideZoom -> setSlideZoom(event.on)
            is AppEvent.SlideFill -> setSlideFill(event.on)
            is AppEvent.SlideshowShuffled -> setSlideshowShuffled(event.on)
            is AppEvent.LoopSlideshow -> setLoopSlideshow(event.on)
            is AppEvent.PreviewOriginal -> setPreviewOriginal(event.on)
            AppEvent.ToggleViewerInfo -> toggleViewerInfo()
            AppEvent.CloseViewer -> closeViewer()
            is AppEvent.VideoPlayingChanged -> setVideoPlaying(event.on)
            AppEvent.VideoEnded -> onVideoEnded()

            // --- 背景音乐 / 歌词 ---
            is AppEvent.ToggleMusic -> toggleMusic(event.id)
            AppEvent.ToggleMusicAll -> toggleMusicAll()
            is AppEvent.MusicShuffled -> setMusicShuffled(event.on)
            is AppEvent.LyricsEnabled -> setLyricsEnabled(event.on)
            AppEvent.RefreshMusic -> refreshMusic()
            is AppEvent.MusicPermissionResult -> onMusicPermissionResult(event.granted)
            is AppEvent.MusicSourceChanged -> setMusicSource(event.kind)
            is AppEvent.FnMusicToggleGroup -> toggleFnMusicGroup(event.groupId)
            is AppEvent.FnMusicOpenGroup -> openFnMusicGroup(event.groupId)
            AppEvent.FnMusicBackToGroups -> backToFnMusicGroups()
            AppEvent.FnMusicDismissError -> dismissFnMusicError()

            // --- 生命周期 / 闲置保护 ---
            AppEvent.NoteInteraction -> noteInteraction()
            AppEvent.TickIdle -> tickIdle()

            // --- 新版本检测 ---
            AppEvent.DownloadUpdate -> downloadUpdate()
            AppEvent.DismissUpdate -> dismissUpdate()
            AppEvent.RetryUpdate -> downloadUpdate()
        }
    }

    /** 弹窗点「更新」/「重试」：下载 APK，进度与失败态同步进 AppState 给弹窗显示。 */
    private fun downloadUpdate() {
        val info = updateSource.available.value ?: return
        viewModelScope.launch {
            _state.update { it.copy(updateProgress = 0, updateFailed = false) }
            // 进度来自 updateSource.progress，单独订阅镜像到 state。
            val progressJob = launch {
                updateSource.progress.collect { p -> _state.update { it.copy(updateProgress = p) } }
            }
            val path = updateSource.download(info)
            progressJob.cancel()
            _state.update { it.copy(updateProgress = null, updateFailed = path == null) }
            if (path != null) _installRequest.value = path
        }
    }

    /**
     * 弹窗点「取消」：关弹窗并记住这个版本，同一版本不再打扰。
     * 下次开机检测到更高版本仍会弹（dismissedCode 只挡住当前这个）。
     */
    private fun dismissUpdate() {
        updateSource.available.value?.let { updatePrefs.dismissedCode = it.versionCode }
        updateSource.clearFailure()
        _state.update { it.copy(updateDialog = false, updateProgress = null, updateFailed = false) }
    }

    /** MainActivity 拉起安装界面后调这个清空信号，避免重复触发。 */
    fun consumeInstallRequest() {
        _installRequest.value = null
    }

    /**
     * 设置页「飞牛音乐」登录表单的验证入口。
     *
     * 该代码是从哪个原文件提取的：【新增实现】。刻意不复用相册 `signIn()`：
     * 相册用 NAS 系统账号，音乐是音乐服务里的子账户，两套凭据、两个端口、
     * 连「要访问码」的返回形态都不一样（音乐回 HTML 页而不是错误 JSON）。
     *
     * 对应的生命周期清理应该在原文件的哪个位置调用：
     * `viewModelScope` 会随 `onCleared()` 自动取消，无需新增清理点；
     * `fnMusicAuth` 只持有一个内存 token，原 `onCleared()` 三行保持不变。
     *
     * 线程约定：这里只在主线程写状态，IO 全部在 `FnMusicAuthPort.verify()` 内部。
     * 成功时清掉错误；失败时把 [FnMusicSignInOutcome] 原样交给 UI 分支出文案。
     */
    /**
     * 地址不再由用户输入，统一用相册 host 推导的默认音乐地址
     * （见 `defaultFnMusicBaseUrl` / `FnMusicAuthPort.defaultBaseUrl`），
     * 这样同一台 NAS 的相册与飞牛音乐自动对应，用户只要填账号密码。
     */
    private fun fnMusicSignIn(user: String, password: String, accessCode: String) {
        // 电视上连点两下不该发两次登录，也不该让后一次覆盖前一次的结果。
        if (_state.value.fnMusicLoginPending) return
        _state.update { it.copy(fnMusicLoginPending = true, fnMusicLoginError = null) }
        viewModelScope.launch {
            val outcome = fnMusicAuth.verify(fnMusicAuth.defaultBaseUrl, user, password, accessCode)
            _state.update {
                it.copy(
                    fnMusicLoginPending = false,
                    fnMusicLoggedIn = fnMusicAuth.loggedIn,
                    fnMusicAccount = fnMusicAuth.accountName,
                    fnMusicSavedUser = fnMusicAuth.savedUser,
                    fnMusicBaseUrl = fnMusicAuth.baseUrl.ifBlank { fnMusicAuth.defaultBaseUrl },
                    fnMusicLoginError = outcome.takeIf { result -> result.failed },
                )
            }
            // 已经在飞牛源时，登录成功顺手把歌单刷出来，不用用户再按一次刷新。
            if (!outcome.failed && _state.value.musicSourceKind == MusicSourceKind.FN) {
                loadFnMusicGroups()
            }
        }
    }

    /** 退出音乐子账户：清掉音乐凭据，但不碰相册账号与任何显示设置。 */
    private fun fnMusicSignOut() {
        fnMusicAuth.signOut()
        // 正在用飞牛源就先切回本地，否则留在一个永远拉不到列表的空界面上。
        if (_state.value.musicSourceKind == MusicSourceKind.FN) {
            setMusicSource(MusicSourceKind.LOCAL)
        }
        _state.update {
            it.copy(
                fnMusicLoggedIn = false,
                fnMusicAccount = "",
                fnMusicSavedUser = "",
                fnMusicBaseUrl = fnMusicAuth.defaultBaseUrl,
                fnMusicLoginPending = false,
                fnMusicLoginError = null,
                fnMusicGroups = emptyList(),
                fnMusicTracks = emptyList(),
                fnMusicCurrentGroupId = null,
                fnMusicCurrentGroupTitle = "",
                fnMusicError = null,
            )
        }
    }

    private fun dismissFnMusicLoginError() {
        _state.update { it.copy(fnMusicLoginError = null) }
    }

    private companion object {
        const val PAGE_SIZE = 60

        /** 新版本检测：冷启延迟 5 秒、一天一次、等回相册墙每 500ms 看一眼。 */
        const val UPDATE_CHECK_DELAY_MS = 5_000L
        const val UPDATE_CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L
        const val UPDATE_SHOW_POLL_MS = 500L

        /**
         * 打开相册时先交出多少张真实照片（约两屏 6 列网格）。
         *
         * 取小不取大：回调发生在 IO 线程解析循环里，给出去之后主线程立刻要按这个规模
         * 重算一次网格行；24 条是「首屏有东西可看」和「首帧算行几乎不花时间」的交点。
         */
        const val OPEN_FIRST_BATCH = 24

        /** 双源背景乐队列的诊断 TAG：只打点，不改任何分支行为。 */
        const val TAG_DIAG = "FnMusicDiag"

        /** 日期索引每页请求多少条：纯元数据，300 一条请求，863 张的相册 3 个来回。 */
        const val DATE_INDEX_PAGE_SIZE = 300

        /** 索引上限。再大的相册也不值得一次全拉进内存，时间轴覆盖最近 6000 张已经够用。 */
        const val DATE_INDEX_MAX_PHOTOS = 6000

        /** 首屏出图之后再多等一会儿才启动索引，别和第一批缩略图抢带宽。 */
        const val DATE_INDEX_START_MS = 800L

        /** 每页之间让出一点时间，把行重算和图片解码插进去。 */
        const val DATE_INDEX_GAP_MS = 80L

        /**
         * 内存缓存 64MB / 磁盘缓存 3GB 两个上限，以及「3GB 与 256MB 曾经给反了」的
         * 来龙去脉，已随 ImageLoader 构造一起搬进
         * `engine/impl/CoilImageEngine.kt` 的 companion object（第 4/5 批）。
         * 这里不再留第二份常量，免得改了一处忘了另一处。
         */

        /** 访问码弹窗里码是空的时候就按了「确定」。文案和网络错误共用 error 字段。 */
        const val EMPTY_CODE_HINT = "访问码不能为空"

        /** 双源曲库的弹窗内提示（不进全局 error toast，免得叠到设置页上）。 */
        const val FN_MUSIC_NEED_LOGIN = "请先到设置里登录飞牛音乐"
        const val FN_MUSIC_LOAD_FAILED = "读不到歌单，检查音乐账号与 NAS 状态"
        /** 歌单在、曲目接口却拉不到：不静默 stop，把原因摆到音乐弹窗里。 */
        const val FN_MUSIC_TRACKS_FAILED = "读不到歌单曲目，飞牛音乐服务可能已断开；恢复后会自动重试"
        /** 同一件事在看图页的短提示：ErrorToast 只看全局 error，3 秒后自动收起。 */
        const val FN_MUSIC_TRACKS_TOAST = "读不到飞牛曲目，正在重试；持续无声请重启 NAS 上的飞牛音乐"
    }
}
