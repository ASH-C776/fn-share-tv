package com.kijeye.fnshare.presentation.event

import com.kijeye.fnshare.domain.model.MusicSourceKind
import com.kijeye.fnshare.domain.model.SharedAlbum

/**
 * 该代码是从 `MainActivity.kt:208-316` 与 `ui/ViewerScreen.kt:139-171` 的
 * `ViewerActions` / 各屏回调**逐一对照**提取的（结构化重构第 5 批）。
 * 一个事件 = 原来一次 `model::xxx` 或 `model.xxx(...)` 调用；参数类型逐字照抄，
 * 不合并、不改名、不新增业务语义。
 *
 * 第 6 批已接线：`MainActivity` 的回调与 `ViewerActions` 的构造全部改成
 * 「发 [AppEvent] 给 [AppEventSink]」，由 `AppViewModel.onEvent()` 用 `when` 分派到
 * **现有方法**（方法体一行不动）。UI 从此只认识事件，不认识 ViewModel 的方法表，
 * 符合「UI 只渲染 / 焦点 / 动画、状态与事件归 VM」的边界。
 *
 * 读侧（state / 5 个 StateFlow / 引擎只读句柄）仍由 `MainActivity` 直接读 `AppViewModel`，
 * 抽成聚合接口留到第 7 批；写侧已经完全事件化。
 *
 * 对应的生命周期清理应该在原文件的哪个位置调用：
 * 【无需清理】。全部是不可变 data class / data object，没有协程、监听器或句柄；
 * `MainActivity.onDestroy()` 与 `AppViewModel.onCleared():1198` 都不为此新增调用。
 * 事件是一次性值，发完即被消费，不排队、不去重、不重放——不要给它加缓冲。
 */
sealed interface AppEvent {

    // --- 登录 ---

    data class SignIn(val host: String, val user: String, val password: String) : AppEvent
    data class SubmitAccessCode(val code: String) : AppEvent
    data object DismissAccessCode : AppEvent
    data object DismissError : AppEvent

    // --- 相册墙 / 网格 / 设置 ---

    data class OpenAlbum(val album: SharedAlbum) : AppEvent
    data object OpenSettings : AppEvent
    data object CloseSettings : AppEvent
    /** 设置页「更多」入口：打开信息页 / 从信息页返回设置页。 */
    data object OpenMore : AppEvent
    data object CloseMore : AppEvent
    data object BackToWall : AppEvent
    data object RefreshAlbums : AppEvent
    data object ClearCache : AppEvent
    data object SignOut : AppEvent
    data object LoadMorePhotos : AppEvent
    data object RetryPhotos : AppEvent
    data class OpenViewer(val index: Int) : AppEvent

    // --- 设置项 ---

    data class IdleMinutes(val minutes: Int) : AppEvent
    data class BackdropWall(val on: Boolean) : AppEvent
    data class BackdropGrid(val on: Boolean) : AppEvent
    data class FocusZoom(val on: Boolean) : AppEvent
    data class FocusRingColor(val hex: String) : AppEvent

    // --- 飞牛音乐子账户（设置项入口） ---

    /** 地址不再由用户输入，内部用 `defaultFnMusicBaseUrl(settings.host)` 推导。
     *  表单第 3 格是可选访问码；空串表示 NAS 没开访问码闸门。 */
    data class FnMusicSignIn(
        val user: String,
        val password: String,
        val accessCode: String,
    ) : AppEvent
    data object FnMusicSignOut : AppEvent
    data object FnMusicDismissLoginError : AppEvent

    // --- 看图页 / 幻灯片 ---

    data class Step(val delta: Int) : AppEvent
    data object AutoStep : AppEvent
    /** 幻灯片播放中按 ←/→ 切歌；需要返回「有没有真的切」，故单独保留在 ViewerActions 兼容层。 */
    data class SkipMusic(val delta: Int) : AppEvent
    data class Slideshow(val on: Boolean) : AppEvent
    data class SlideshowPaused(val paused: Boolean) : AppEvent
    data class SlideSeconds(val seconds: Int) : AppEvent
    data class SlideFade(val on: Boolean) : AppEvent
    data class SlideZoom(val on: Boolean) : AppEvent
    data class SlideFill(val on: Boolean) : AppEvent
    data class SlideshowShuffled(val on: Boolean) : AppEvent
    data class LoopSlideshow(val on: Boolean) : AppEvent
    data class PreviewOriginal(val on: Boolean) : AppEvent
    data object ToggleViewerInfo : AppEvent
    data object CloseViewer : AppEvent
    data class VideoPlayingChanged(val on: Boolean) : AppEvent
    data object VideoEnded : AppEvent

    // --- 背景音乐 / 歌词 ---

    data class ToggleMusic(val id: String) : AppEvent
    data object ToggleMusicAll : AppEvent
    data class MusicShuffled(val on: Boolean) : AppEvent
    data class LyricsEnabled(val on: Boolean) : AppEvent
    data object RefreshMusic : AppEvent
    data class MusicPermissionResult(val granted: Boolean) : AppEvent

    // --- 双源曲库（P4）：写侧只有这几个入口，UI 不认识 HTTP / 歌单 GUID ---

    /** 来源二级面板或看图页菜单的 ←/→ 切源。未登录时由 ViewModel 拒绝。 */
    data class MusicSourceChanged(val kind: MusicSourceKind) : AppEvent
    /** 歌单行按 OK：勾选 / 取消勾选整张歌单（与本地勾曲目同一套选择集）。 */
    data class FnMusicToggleGroup(val groupId: String) : AppEvent
    /** 歌单行按 →：进曲目步。 */
    data class FnMusicOpenGroup(val groupId: String) : AppEvent
    /** 曲目步按 ←/Back：退回歌单列表。 */
    data object FnMusicBackToGroups : AppEvent
    data object FnMusicDismissError : AppEvent

    // --- 生命周期 / 闲置保护 ---

    /** 任意按键预览里「有操作」；不拦截按键。 */
    data object NoteInteraction : AppEvent
    /** MainActivity 里 15 秒一次的闲置检查。 */
    data object TickIdle : AppEvent

    // --- 新版本检测（0.31.0）---

    /** 弹窗里点「更新」：开始下载。 */
    data object DownloadUpdate : AppEvent
    /** 弹窗里点「取消」：关弹窗，记住不再提示这个版本。 */
    data object DismissUpdate : AppEvent
    /** 下载失败后点「重试」。 */
    data object RetryUpdate : AppEvent
}
