package com.kijeye.fnshare.presentation.state

import androidx.compose.runtime.Immutable
import com.kijeye.fnshare.domain.model.FnMusicSignInOutcome
import com.kijeye.fnshare.domain.model.MusicEntry
import com.kijeye.fnshare.domain.model.MusicGroup
import com.kijeye.fnshare.domain.model.MusicSourceKind
import com.kijeye.fnshare.domain.model.SharedAlbum
import com.kijeye.fnshare.domain.model.SharedPhoto

/**
 * 该代码是从 `ui/AppModels.kt` **整个文件逐字搬移**的（结构化重构第 6 批）：
 * [OwnerGroup]、[CACHE_LIMIT_BYTES]、[Screen]、[AppState] 的字段名、类型、默认值、
 * 注释、[Immutable] 标注一个都没改；只把 package 从 `ui` 换成 `presentation.state`，
 * 并把三个模型类型的 import 从 `data.fn` / `data.music` 换成 `domain.model`
 * （第 1 批已在 `data` 包留了 typealias，两边是同一个类型，不是新类型）。
 *
 * 为什么搬：状态快照是 presentation 层的产物，放在 `ui` 包里会让 `domain` / `data`
 * 想引用它时反向依赖 UI。搬完后依赖方向是
 * `ui → presentation/state → domain/model`，单向。
 *
 * 兼容层：`ui/AppModels.kt` 留 `typealias AppState` / `typealias OwnerGroup` 和
 * `CACHE_LIMIT_BYTES` 的转发，所有既有调用点零改动。[Screen] 是 sealed interface，
 * **不能用 typealias**（Kotlin 不允许通过类型别名访问嵌套类，第 1 批 `GridRow.Day`
 * 已经踩过），所以 `MainActivity.kt` 与 `ui/AppViewModel.kt` 改为直接 import 本包的
 * [Screen]，`Screen.Wall` 这类限定名写法原样保留。
 *
 * 对应的生命周期清理应该在原文件的哪个位置调用：
 * 【无需清理】。全是不可变 data class / data object 与一个编译期常量，
 * 不持有 Context、协程、监听器或文件句柄。`MainActivity.onDestroy()` 与
 * `AppViewModel.onCleared()` 都不为此新增调用；`AppState` 的生命周期就是
 * `MutableStateFlow` 本身的生命周期，随 ViewModel 一起被 GC。
 */

/** 一位共享人，以及他共享出来的相册。相册墙就是这一组的列表。 */
@Immutable
data class OwnerGroup(val name: String, val albums: List<SharedAlbum>)

/**
 * 手动刷新相册的阶段，显示在设置页「刷新相册」那一行的值胶囊里。
 *
 * 冷启动不走这套状态（保持 IDLE）——启动路径零行为变化。只有人主动按了刷新，
 * 才依次点亮：连接 → 检查 → 更新 x/y，最后落到「已是最新 / 刷新失败」，
 * 终态几秒后自动回 IDLE（胶囊恢复成「›」）。
 */
enum class RefreshPhase {
    IDLE,
    CONNECTING,
    CHECKING,
    UPDATING,
    UP_TO_DATE,
    FAILED,
}

/**
 * 相册元数据缓存（设置页里的「照片索引」）的上限：256MB。
 *
 * 一条照片清单约 350 字节，256MB 够存七十多万张。实测一台存了几百本相册的电视，
 * 整份索引也才 3.4MB——这个上限基本是不会碰到的天花板，留着它只是让设置页
 * 有个稳定的分母能显示「离满还差多少」。
 *
 * 原来的 3GB 是给反了：真正吃空间的是缩略图（那一个早就顶满 256MB），
 * 索引给 3GB 等于没有任何约束。
 */
internal const val CACHE_LIMIT_BYTES = 256L * 1024 * 1024

sealed interface Screen {
    data object Login : Screen
    data object Wall : Screen
    data object Settings : Screen
    /** 设置页「更多」入口打开：应用信息、开源地址、使用说明。返回固定回设置页。 */
    data object More : Screen
    data class Grid(val album: SharedAlbum) : Screen
    data class Viewer(val album: SharedAlbum, val index: Int) : Screen
}

/**
 * 全应用的唯一状态快照，单 ViewModel 管四屏。
 *
 * 标 [Immutable] 是给 Compose 的编译器承诺：实例内容不变就当稳定类型。里面的 List/Set
 * 字段本会被判成 unstable，导致下游全屏（网格、看图页）跟着一遍遍重组。
 *
 * ⚠️ 这份快照是**整包 copy**：任何字段更新都会产生新实例，所有读 [AppState] 的组合点
 * 都会重组。[Immutable] 只让 Compose 在实例相等时跳过重组，**不提供按字段订阅**；
 * 想减少重组得靠各屏自己用 `derivedStateOf` 或按字段 collect，别指望这个标注。
 */
@Immutable
data class AppState(
    val starting: Boolean = true,
    val screen: Screen = Screen.Login,
    val account: String = "",
    val owners: List<OwnerGroup> = emptyList(),
    val albumsLoading: Boolean = false,
    val photos: List<SharedPhoto> = emptyList(),
    val photosLoading: Boolean = false,
    /** 上一次取照片失败了。相册是空的且这个为真时，网格上要给一个「重试」。 */
    val photosFailed: Boolean = false,
    val hasMorePhotos: Boolean = false,
    /**
     * 后台正在补全整本相册的日期索引。
     *
     * 时间轴要「没有照片的年月不显示」，就必须知道整本相册的年月分布，而 `photos` 是
     * 分页前缀（一页 60 张）。这个任务只拉元数据、不加载图片，见 `AppViewModel.runDateIndex`。
     */
    val indexing: Boolean = false,

    // --- 缓存 ---

    /** 照片元数据索引的占用（字节）。设置页显示成「3.4 MB / 256 MB」。 */
    val cacheBytes: Long = 0L,
    /** 索引了几本相册。 */
    val cacheAlbumCount: Int = 0,
    /** 缩略图磁盘缓存的占用（字节）。 */
    val thumbCacheBytes: Long = 0L,
    /** 缩略图磁盘缓存的上限（字节）。写死的 3GB，不随磁盘占用漂。 */
    val thumbCacheLimitBytes: Long = 0L,
    /** 正在后台重建过期的相册缓存。 */
    val cacheRefreshing: Boolean = false,
    /** 本轮要重建几本。 */
    val cacheRebuildTotal: Int = 0,
    /** 本轮已经重建完几本。 */
    val cacheRebuildDone: Int = 0,
    /** 手动刷新的阶段；冷启动不动它，始终是 IDLE。 */
    val refreshPhase: RefreshPhase = RefreshPhase.IDLE,
    val slideshow: Boolean = false,
    /** 幻灯片顺序：开 = 随机跳一张，关 = 按相册顺序。与设置面板同源。 */
    val slideshowShuffled: Boolean = false,
    /**
     * 随机模式提前挑好的下一张。预取只覆盖相邻两张，随机跳页的目标图不在范围内，
     * 等到换图才解码就要干等几百毫秒——把「下一张」提前放进 state，UI 好提前预取。
     */
    val shuffleNext: Int? = null,
    /** 全屏照片直接拉原图（清晰但慢）。全局开关，菜单和设置页改的是同一个。 */
    val previewOriginal: Boolean = false,
    /** 当前正在放一个视频（且是播放中）。闲置保护把它和幻灯片同等看待——「有人在看」就不退回。 */
    val videoPlaying: Boolean = false,
    /** 从全屏退回网格时该把焦点放回哪一张。新进相册是 0。 */
    val lastViewerIndex: Int = 0,
    /**
     * 大图界面左上信息框是否展开。遥控器的 MENU 键开关，**每次进大图都从收起开始**
     * （[com.kijeye.fnshare.ui.AppViewModel.openViewer] 里重置），退出大图即失效。
     */
    val viewerInfo: Boolean = false,
    val slideSeconds: Int = 6,
    /** 幻灯片换图是否叠化。关 = 硬切。 */
    val slideFade: Boolean = true,
    /** 幻灯片是否跑 Ken Burns 放大。关 = 静止画面。 */
    val slideZoom: Boolean = true,
    /** 幻灯片取景：true = 满屏铺满（裁边），false = 原比例完整显示。 */
    val slideFill: Boolean = true,
    val loopSlideshow: Boolean = false,
    /** 闲置多少分钟后自动退回相册墙。0 = 关。只统计无操作时间，幻灯片在放时不算闲置。 */
    val idleMinutes: Int = 10,
    /** 相册墙是否跟随焦点显示动态毛玻璃背景。与照片页分开开关。 */
    val backdropWall: Boolean = true,
    /** 照片网格是否跟随焦点显示动态毛玻璃背景。与相册墙分开开关。 */
    val backdropGrid: Boolean = true,
    /** 卡片获得焦点时里面的图是否再放大一档（画框感）。关掉只剩外框轻微放大 + 白边。 */
    val focusZoom: Boolean = true,
    /** 焦点框描边颜色（十六进制），落盘值见 [com.kijeye.fnshare.data.FnSettings.focusRingColorHex]。 */
    val focusRingColorHex: String = "#FFFFFFFF",
    /** 上次用过的地址和账号（密码也一并记住，见 [lastPassword]）。退出登录后仍然留在登录页里，电视上用遥控器重敲一遍太遭罪。 */
    val lastHost: String = "",
    val lastUser: String = "",
    val lastPassword: String = "",
    /** NAS 开了访问码闸门，登录页该弹窗请人输一次码。表单里已经填好的信息原样保留。 */
    val needAccessCode: Boolean = false,
    val error: String? = null,

    // --- 幻灯片背景音乐 ---
    /** 扫描到的本地曲库（仅 `music` 文件夹下的音频）。 */
    val musicEntries: List<MusicEntry> = emptyList(),
    /** 在曲库里勾选、将作为幻灯片背景乐播放的曲目 id。 */
    val musicSelected: Set<String> = emptySet(),
    /** 背景乐播放顺序：true = 随机（每次开播洗一遍列表），false = 按曲库顺序循环。 */
    val musicShuffled: Boolean = false,
    /** 是否正在扫描曲库（界面据此显示「扫描中」）。 */
    val musicScanning: Boolean = false,
    /** 是否已拿到读音乐的权限；没拿到时曲库界面显示「去授权」。 */
    val musicPermission: Boolean = false,
    /** 背景乐是否显示 LRC 歌词（有歌词文件的曲目在右侧显示两列竖排歌词）。 */
    val lyricsEnabled: Boolean = true,

    // --- 飞牛音乐子账户（与相册账号完全独立） ---
    /** 已保存音乐子账户（冷启可重登）即算已登录。 */
    val fnMusicLoggedIn: Boolean = false,
    /** 设置页显示用：「已登录：tv」。 */
    val fnMusicAccount: String = "",
    /** 登录表单回填地址；未登录时是由相册 host 推出来的默认值。 */
    val fnMusicBaseUrl: String = "",
    /** 登录表单回填账号名（密码不回填，重新验证时重敲一次）。 */
    val fnMusicSavedUser: String = "",
    val fnMusicLoginPending: Boolean = false,
    /** 上一次验证结果；成功时清空，失败时由弹窗按分支显示对应提示。 */
    val fnMusicLoginError: FnMusicSignInOutcome? = null,

    // --- 新版本检测（0.31.0）---
    /** 检测到新版本时弹窗；只在相册墙显示，其它屏不打断。 */
    val updateDialog: Boolean = false,
    /** 下载进度 0..100；null = 不在下载中。 */
    val updateProgress: Int? = null,
    /** 下载失败，弹窗切到失败态给「重试 / 取消」。 */
    val updateFailed: Boolean = false,

    // --- 双源曲库（P4） ---
    /** 当前曲库来源；持久化在 `MusicPreferencesPort.sourceKind`，与相册账号无关。 */
    val musicSourceKind: MusicSourceKind = MusicSourceKind.LOCAL,
    /** 飞牛第一级：歌单列表。接口不返回曲目数，所以 [MusicGroup.subtitle] 只作来源标注。 */
    val fnMusicGroups: List<MusicGroup> = emptyList(),
    /** 飞牛第二级：当前歌单内曲目；整单播放时也用来当播放队列。 */
    val fnMusicTracks: List<MusicEntry> = emptyList(),
    /** 非空 = 曲目步；切源 / 换账号时由 ViewModel 清空。 */
    val fnMusicCurrentGroupId: String? = null,
    val fnMusicCurrentGroupTitle: String = "",
    val fnMusicLoading: Boolean = false,
    /** 拉歌单 / 拉曲目失败的短提示；由弹窗显示，不进全局 error toast。 */
    val fnMusicError: String? = null,
)
