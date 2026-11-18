package com.kijeye.fnshare.di

import android.app.Application
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.kijeye.fnshare.core.SharedHttpEngine
import com.kijeye.fnshare.data.FnRepo
import com.kijeye.fnshare.data.FnSettings
import com.kijeye.fnshare.data.cache.AlbumCache
import com.kijeye.fnshare.data.local.DiskAlbumCacheSource
import com.kijeye.fnshare.data.local.PrefsMusicPreferences
import com.kijeye.fnshare.data.local.PrefsSettingsSource
import com.kijeye.fnshare.data.local.UpdatePrefs
import com.kijeye.fnshare.data.lyrics.FnLyricsSource
import com.kijeye.fnshare.data.lyrics.LocalLyricsSource
import com.kijeye.fnshare.data.lyrics.LyricsRepo
import com.kijeye.fnshare.data.music.LocalMusicCatalog
import com.kijeye.fnshare.data.music.LocalMusicSource
import com.kijeye.fnshare.data.music.MusicScanner
import com.kijeye.fnshare.data.music.fn.FnMusicApi
import com.kijeye.fnshare.data.music.fn.FnMusicAuthAdapter
import com.kijeye.fnshare.data.music.fn.FnMusicCatalog
import com.kijeye.fnshare.data.music.fn.FnMusicSession
import com.kijeye.fnshare.data.music.fn.FnMusicStreamInterceptor
import com.kijeye.fnshare.data.music.fn.OkHttpFnMusicTransport
import com.kijeye.fnshare.data.music.fn.defaultFnMusicBaseUrl
import com.kijeye.fnshare.data.remote.DefaultUpdateSource
import com.kijeye.fnshare.data.remote.RemotePhotoSource
import com.kijeye.fnshare.data.remote.RepoSharedHttpEngine
import com.kijeye.fnshare.domain.model.MusicSourceKind
import com.kijeye.fnshare.domain.port.AlbumCacheSource
import com.kijeye.fnshare.domain.port.FnMusicAuthPort
import com.kijeye.fnshare.domain.port.LyricsSource
import com.kijeye.fnshare.domain.port.MusicCatalogPort
import com.kijeye.fnshare.domain.port.MusicPreferencesPort
import com.kijeye.fnshare.domain.port.MusicSource
import com.kijeye.fnshare.domain.port.PhotoSource
import com.kijeye.fnshare.domain.port.SettingsSource
import com.kijeye.fnshare.domain.port.UpdateSource
import com.kijeye.fnshare.engine.api.AudioEngine
import com.kijeye.fnshare.engine.api.ImageEngine
import com.kijeye.fnshare.engine.api.LyricsTicker
import com.kijeye.fnshare.engine.api.MusicPlaybackEngine
import com.kijeye.fnshare.engine.api.VideoEngine
import com.kijeye.fnshare.engine.impl.CoilImageEngine
import com.kijeye.fnshare.engine.impl.ExoVideoEngine
import com.kijeye.fnshare.engine.impl.DefaultLyricsTicker
import com.kijeye.fnshare.engine.impl.DualSourceAudioEngine
import com.kijeye.fnshare.engine.impl.ExoAudioEngine
import com.kijeye.fnshare.engine.impl.FnMusicPlayer
import kotlinx.coroutines.CoroutineScope
import okhttp3.CookieJar
import okhttp3.OkHttpClient

/**
 * 组装层：把第 1~4 批抽出的 port / engine 契约接到真实实现上，并**只在这里** new 具体类。
 *
 * 该代码是从以下原文件的构造语句逐字提取的（结构化重构第 5 批）：
 *  - `ui/AppViewModel.kt:45`（`FnSettings(app)`）
 *  - `ui/AppViewModel.kt:51`（`FnRepo(settings)`）
 *  - `ui/AppViewModel.kt:68-84`（ImageLoader / OkHttp fetcher / 64MB 内存 / 3GB 磁盘）
 *  - `ui/AppViewModel.kt:91`（`AlbumCache(app)`）
 *  - `ui/AppViewModel.kt:95-96`（`OkHttpDataSource.Factory(repo.client)`）
 *  - `ui/AppViewModel.kt:99`（`MusicScanner(app)`）
 *  - `ui/AppViewModel.kt:105`（`SlideshowMusicPlayer(app)`，现由 [audioEngine] 产出）
 *  - `ui/AppViewModel.kt:114`（`LyricsRepo(app)`）
 *  - `MainActivity.kt:98`（`SingletonImageLoader.setSafe`，现由 [imageEngine] 的 `install()` 承担）
 *
 * 依赖方向：`ui → presentation → domain/port + engine/api ← data 与 engine/impl ← di/AppContainer`。
 * 本类是唯一允许同时 import `data` 与 `engine.impl` 的地方；`AppViewModel` 只看见接口。
 *
 * ⚠️ **为什么是进程级单例**：Coil 的磁盘缓存目录是 `filesDir/coil3_disk_cache`（0.24.4 起），
 * 同目录起两个 ImageLoader 会损坏 journal（见 `engine/impl/CoilImageEngine` 文件头）。
 * 同理 OkHttpClient 上的 AccessToken 拦截器绑着 `FnRepo` 的 token，三处必须同一个实例。
 * `MainActivity` 已在 Manifest 里声明 `configChanges`，配置变更不会重建 Activity；
 * 即便将来 VM 销毁重建，容器仍是同一个，ImageLoader 不会被重建。
 *
 * 对应的生命周期清理应该在原文件的哪个位置调用：
 *  - `AudioEngine.release()` / `LyricsTicker.cancel()`：**每个 ViewModel 各一份**，
 *    由 `ui/AppViewModel.kt:1198` 的 `onCleared()` 调用（原 `musicPlayer.release()` 那一行
 *    改接收者为接口，并在旁边加一行 `ticker.cancel()`）。这两个对象由 [audioEngine] /
 *    [lyricsTicker] 工厂方法产出，故意不放进容器字段，避免 VM 重建时拿到已 release 的实例。
 *    飞牛源产出的 `FnMusicPlayer` 同样实现 `AudioEngine`，共用这一处清理；切源时若提前
 *    丢弃实例，调用方须先 `stop()` 再 `release()`（P4 的互斥停播会在这里接线）。
 *  - `ImageEngine.close()`：**不调用**。基线的 `AppViewModel` 从未 shutdown 过 ImageLoader，
 *    而本容器是进程级单例；在 `onCleared()` 里 close 会引入「VM 重建后全局仍指向已关闭的
 *    loader → 满屏灰块」的新 bug。保持基线行为：随进程消亡，由 OS 回收。
 *  - `SharedHttpEngine` / 四个数据源：无清理点（各自文件头已注明）。
 */
class AppContainer private constructor(private val app: Application) {

    /** 只在这里 new：pin、token、AccessToken 拦截器全挂在它身上。 */
    private val fnSettings = FnSettings(app)
    private val fnRepo = FnRepo(fnSettings)

    /** 三处共用的 HTTP 通道：接口请求、Coil 缩略图、ExoPlayer 视频流。 */
    val http: SharedHttpEngine = RepoSharedHttpEngine(fnRepo)

    val settings: SettingsSource = PrefsSettingsSource(fnSettings)
    val photoSource: PhotoSource = RemotePhotoSource(fnRepo)
    val albumCacheSource: AlbumCacheSource = DiskAlbumCacheSource(AlbumCache(app))
    val musicSource: MusicSource = LocalMusicSource(MusicScanner(app))
    val lyricsSource: LyricsSource = LocalLyricsSource(LyricsRepo(app))

    /** 更新检测专用：独立时间戳，独立干净 HTTP 客户端（见 DefaultUpdateSource 文件头）。 */
    val updatePrefs: UpdatePrefs = UpdatePrefs(app)
    val updateSource: UpdateSource = DefaultUpdateSource(
        context = app,
        giteeApi = "https://gitee.com/api/v5/repos/kijeye/fn-share-tv/releases",
        githubApi = "https://api.github.com/repos/ASH-C776/fn-share-tv/releases",
    )

    /* ---------------- 双源音乐（P2b 接线；本地链路一行未改） ---------------- */

    /** 音乐子账户与来源选择的独立设置；`SettingsSource` 一个属性都没加。 */
    val musicPreferences: MusicPreferencesPort = PrefsMusicPreferences(fnSettings)

    /**
     * 飞牛是**另一个端口、另一套认证**。这里从共享 client 复制 TLS pin 与超时，
     * 但换成空 CookieJar：相册的 session cookie 按 host 共享，同 host 不同端口会被
     * 自动带去 5666，没有好处。末尾的「清道夫」去掉相册链路上加的 `AccessToken`
     * （它注册在前，所以清道夫能删掉它），飞牛服务只认 `Authorization` 与
     * `x-access-code`。不直接 clear 全部拦截器，是为了保住 FnTrust 的 TLS 配置。
     */
    private val fnApiClient: OkHttpClient = http.client.newBuilder()
        .cookieJar(CookieJar.NO_COOKIES)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().removeHeader("AccessToken").build())
        }
        .build()

    /** 业务接口自己在 `FnMusicRequest` 里写 header，不需要额外拦截器。 */
    private val fnTransport = OkHttpFnMusicTransport(fnApiClient)

    /**
     * `by lazy`：api 的 tokenProvider 要读 session，session 构造又要 api。
     * 两个 provider 都是「发请求那一刻」才求值，初始化环在这里解开。
     */
    val fnMusicApi: FnMusicApi by lazy {
        FnMusicApi(
            transport = fnTransport,
            // 验证中的表单优先：那时凭据还没落盘，第二步 playlists 必须先用
            // 表单里的地址与访问码，否则会被 HTML 闸门误判成「访问码不对」。
            baseUrlProvider = {
                fnMusicSession.pendingBaseUrl()
                    ?: musicPreferences.fnAccount?.baseUrl?.takeIf { it.isNotBlank() }
                    ?: defaultFnMusicBaseUrl(settings.host)
            },
            tokenProvider = { fnMusicSession.tokenOrNull() },
            accessCodeProvider = {
                fnMusicSession.pendingAccessCode()
                    ?: musicPreferences.fnAccount?.accessCode.orEmpty()
            },
        )
    }

    val fnMusicSession: FnMusicSession by lazy { FnMusicSession(fnMusicApi, musicPreferences) }
    val fnMusicCatalog: FnMusicCatalog by lazy { FnMusicCatalog(fnMusicApi, fnMusicSession) }
    val fnMusicLyrics: LyricsSource by lazy { FnLyricsSource(fnMusicApi, fnMusicSession) }

    /** 设置页登录只认这个 port：ViewModel 不 import 任何 `data.music.fn` 异常或 DTO。 */
    val fnMusicAuth: FnMusicAuthPort by lazy {
        FnMusicAuthAdapter(fnMusicSession, musicPreferences) {
            defaultFnMusicBaseUrl(settings.host)
        }
    }

    /** 本地源也包一层「分组」，UI 用同一个 `MusicCatalogPort` 处理两个源。 */
    val localMusicCatalog: MusicCatalogPort = LocalMusicCatalog(musicSource)

    /** 播放流专用：每次请求动态注入三个 header，并拦下「206 + 网页」这个假成功。 */
    private val fnStreamClient: OkHttpClient by lazy {
        fnApiClient.newBuilder()
            .addInterceptor(FnMusicStreamInterceptor(fnMusicApi))
            .build()
    }

    fun musicCatalog(kind: MusicSourceKind): MusicCatalogPort = when (kind) {
        MusicSourceKind.LOCAL -> localMusicCatalog
        MusicSourceKind.FN -> fnMusicCatalog
    }

    fun lyricsSourceFor(kind: MusicSourceKind): LyricsSource = when (kind) {
        MusicSourceKind.LOCAL -> lyricsSource
        MusicSourceKind.FN -> fnMusicLyrics
    }

    /**
     * 图片引擎按接口持有。第 5 批曾有一个 `imageLoader: ImageLoader` 过渡出口
     * （那时 `ViewerContent` 还是 `ImageLoader` 类型）；第 6 批把
     * `ui/ViewerScreen.kt` 的 `photoRequest` 换到 `ImageRequestFactory`、预取换到
     * `AppViewModel.enqueueImage()` 之后，那个出口连同 `CoilImageEngine.rawLoader`
     * 一起删掉了。[ImageEngine] 自始至终不向 UI 暴露 ImageLoader——这正是
     * 「接口隔离」要保住的边界，也保证全局只有一个实例。
     */
    val imageEngine: ImageEngine = CoilImageEngine(app, http.client)

    /** 与基线一致：每次访问都新建一个 Factory（无状态，`createDataSource()` 才真正开流）。 */
    fun videoDataSourceFactory(): DataSource.Factory = OkHttpDataSource.Factory(http.client)

    /**
     * 0.24.12 M-6：视频引擎按接口产出。
     *
     * 此前 `ui/VideoPlayer.kt` 自己 `remember { ExoVideoEngine(context) }`，UI 直接 import
     * 实现层，引擎既换不掉也没法注入假实现，所有播放时序只能靠真机肉眼验收——这是这个
     * bug 从 0.24.0 一路修到 0.24.11 仍复发的结构性原因。现在具体实现只在组装层 new。
     * 每段视频一个实例（外层 `key(videoUrl)` 重挂），**不要**缓存成字段。
     */
    fun videoEngine(): VideoEngine = ExoVideoEngine(app)

    /**
     * 每个 ViewModel 一个播放器：ExoPlayer 绑调用线程的 Looper，`release()` 之后不可复用。
     * 放在工厂方法里而不是字段里，VM 重建时拿到的是全新实例。
     *
     * 默认 [MusicSourceKind.LOCAL]：`ui/AppViewModel.kt:125` 现有的 `container.audioEngine()`
     * 调用点保持原样，本地链路行为零变化。切到 FN 时才产出 `FnMusicPlayer`；
     * 两个源的互斥停播由 ViewModel 在切源事件里负责（P4）。
     */
    fun audioEngine(kind: MusicSourceKind = MusicSourceKind.LOCAL): AudioEngine = when (kind) {
        MusicSourceKind.LOCAL -> ExoAudioEngine(app)
        MusicSourceKind.FN -> FnMusicPlayer(app, fnMusicApi, fnStreamClient)
    }

    /**
     * 双源播放器的**稳定代理**：每个 ViewModel 一个，内部同时持有本地与飞牛两个实例。
     * ViewModel 的 `musicPlaying` / `musicFailed` / 歌词节拍器全指向这一个对象，
     * 切源只调 `switch()`，不会让 MainActivity 已建立的 collect 指向失效的旧 flow。
     * 初始来源取持久化值；未登录时 ViewModel 会在 init 里把它压回 LOCAL。
     */
    fun musicPlayback(): MusicPlaybackEngine = DualSourceAudioEngine(
        local = audioEngine(MusicSourceKind.LOCAL),
        remote = audioEngine(MusicSourceKind.FN),
        initialKind = musicPreferences.sourceKind,
    )

    /**
     * 每个 ViewModel 一个节拍器：它持有协程 Job，`cancel()` 之后不可复用，理由同 [audioEngine]。
     *
     * 歌词源跟随当前曲库来源：用一个委托 [LyricsSource] 把 `load()` 转发给 [lyricsSourceFor]，
     * 按 [MusicPlaybackEngine.kind] 实时选本地 / 飞牛。切源（`musicPlayer.switch`）后下一次
     * 换曲即自动改用对应歌词源，[DefaultLyricsTicker] 的 100ms 轮询 / 二分查找一行未改，
     * 本地链路行为零变化。
     */
    fun lyricsTicker(
        scope: CoroutineScope,
        audio: AudioEngine,
        shouldRun: () -> Boolean,
    ): LyricsTicker {
        val source = object : LyricsSource {
            override suspend fun load(trackId: String) =
                lyricsSourceFor((audio as? MusicPlaybackEngine)?.kind ?: MusicSourceKind.LOCAL).load(trackId)
        }
        return DefaultLyricsTicker(scope, audio, source, shouldRun)
    }

    companion object {

        @Volatile
        private var instance: AppContainer? = null

        fun get(app: Application): AppContainer = instance ?: synchronized(this) {
            instance ?: AppContainer(app.applicationContext as Application).also { instance = it }
        }
    }
}
