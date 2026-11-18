package com.kijeye.fnshare.presentation.state

import androidx.compose.runtime.Immutable
import com.kijeye.fnshare.domain.model.MusicEntry
import com.kijeye.fnshare.domain.model.MusicGroup
import com.kijeye.fnshare.domain.model.MusicSourceKind

/**
 * 该代码是从哪个原文件提取的：
 * 【新增 UI 状态，无函数体被搬动】。音乐弹窗要画「本地曲目 / 飞牛歌单 / 歌单内曲目」
 * 三种列表，若把十来个零散参数铺进 `MusicDialog` 签名，每次加一种列表都要同时改
 * `AppState` → `MainActivity` → `ViewerContent` → `ViewerScreen` → `MusicDialog` 五处。
 * 这里收成一个只读快照 + 一组动作回调，UI 只认 [MusicGroup] / [MusicEntry] 这两个
 * domain 模型，不 import 任何 `data.music.fn` 的 DTO 或异常。
 *
 * 快照由 [AppState.toMusicCatalogUi] 在 ViewModel 侧整包 copy 出来；纯视觉状态
 * （二级面板开没开、光标在第几行、曲目步还是歌单步）一律留在 `MusicDialog` 的 `remember`
 * 里，不进这里——唯一的例外是 [currentGroupId]：它由 ViewModel 拉完曲目后写入，
 * 切源 / 换账号时由 ViewModel 清空，UI 据此自动退回歌单步。
 *
 * 对应的生命周期清理应该在原文件的哪个位置调用：
 * 【无需清理】。不可变快照与一次性 lambda，无协程、无监听器、无句柄；
 * `AppViewModel.onCleared()` 的三行保持原样。
 *
 * 线程约定：由 ViewModel 在主线程整包 copy 出来，Compose 只读。
 */
@Immutable
data class MusicCatalogUi(
    val sourceKind: MusicSourceKind = MusicSourceKind.LOCAL,
    /** 飞牛子账户是否已登录（冷启可重登即算）。 */
    val fnLoggedIn: Boolean = false,
    /** 设置页显示用：「已登录：tv」。 */
    val fnAccount: String = "",
    /** 飞牛源第一级：歌单列表（本地源恒为空）。 */
    val groups: List<MusicGroup> = emptyList(),
    /** 当前要显示的列表：本地源=本地曲目；飞牛曲目步=该歌单内曲目；飞牛歌单步=空（列表改用 [groups]）。 */
    val tracks: List<MusicEntry> = emptyList(),
    /** 非空 = 飞牛曲目步；切源 / 换账号 / 退回歌单列表时由 ViewModel 清空。 */
    val currentGroupId: String? = null,
    val currentGroupTitle: String = "",
    val loading: Boolean = false,
    /** 拉歌单 / 拉曲目失败的短提示；由弹窗显示，不进全局 error toast。 */
    val error: String? = null,
    /** 当前勾选的曲目 id（本地 `file:` / 飞牛 `fn:` 两种前缀天然不冲突，可共存于同一 Set）。 */
    val selected: Set<String> = emptySet(),
    /** 背景乐播放顺序：true = 随机，false = 顺序。 */
    val musicShuffled: Boolean = false,
    /** 背景乐歌词开关。 */
    val lyricsEnabled: Boolean = true,
    /** 本地源是否正在扫描曲库。 */
    val scanning: Boolean = false,
    /** 是否已拿到读本地音乐的权限；飞牛源不需要，故恒为 true 供 UI 判断。 */
    val hasPermission: Boolean = false,
)

/** 音乐弹窗与看图页菜单共用的写侧入口；全部转发到 `AppEvent`。 */
@Immutable
data class MusicCatalogActions(
    /** 来源二级面板 / 看图页菜单的 ←/→ 切源。未登录时由 ViewModel 拒绝并写 fnMusicError。 */
    val onSourceChanged: (MusicSourceKind) -> Unit = {},
    /** 切换「顺序 / 随机」播放。 */
    val onToggleOrder: () -> Unit = {},
    /** 切换歌词显示开关。 */
    val onToggleLyrics: () -> Unit = {},
    /** 一键全选 / 取消全选（只对当前列表里存在的曲目动手）。 */
    val onToggleAll: () -> Unit = {},
    /** 本地源重新扫描电视 / 飞牛源重新拉歌单。 */
    val onRefresh: () -> Unit = {},
    /** 勾选 / 取消勾选一首曲目（本地曲目步或飞牛曲目步）。 */
    val onToggle: (String) -> Unit = {},
    /** 歌单行按 OK：勾选 / 取消勾选这张歌单，行尾出现 ✓。 */
    val onToggleGroup: (String) -> Unit = {},
    /** 歌单行按 →：进曲目步挑歌。 */
    val onOpenGroup: (String) -> Unit = {},
    /** 曲目步按 ←/Back：退回歌单列表。 */
    val onBackToGroups: () -> Unit = {},
    /** 飞牛源去授权（理论上飞牛不需要本地权限，保留入口以复用原有「去授权」动作）。 */
    val onRequestPermission: () -> Unit = {},
    val onDismissError: () -> Unit = {},
)

/**
 * 把「飞牛歌单步 / 曲目步」的二态折叠成 `groups` / `tracks` 两个列表的纯函数骨架。
 * [AppState.toMusicCatalogUi]（ViewModel 侧整包 copy）与 UI 快照
 * `ViewerContent.toMusicCatalogUi`（看图页内部组合）共用，避免两处各自写一遍折叠
 * 逻辑后慢慢漂移。所有入参都拍平成基础类型，不依赖任何具体持有者。
 *
 * 折叠规则：
 *  - 本地源：`groups` 空，`tracks` = 本地曲目；
 *  - 飞牛歌单步：`groups` = 歌单列表，`tracks` 空（列表改用 [MusicCatalogUi.groups]）；
 *  - 飞牛曲目步：`groups` 仍是歌单列表（供返回），`tracks` = 该歌单内曲目。
 */
fun buildMusicCatalogUi(
    sourceKind: MusicSourceKind,
    fnLoggedIn: Boolean,
    fnAccount: String,
    fnMusicGroups: List<MusicGroup>,
    fnMusicTracks: List<MusicEntry>,
    fnMusicCurrentGroupId: String?,
    fnMusicCurrentGroupTitle: String,
    fnMusicLoading: Boolean,
    fnMusicError: String?,
    localEntries: List<MusicEntry>,
    selected: Set<String>,
    musicShuffled: Boolean,
    lyricsEnabled: Boolean,
    scanning: Boolean,
    hasPermission: Boolean,
): MusicCatalogUi {
    val isFn = sourceKind == MusicSourceKind.FN
    val isGroupStep = isFn && fnMusicCurrentGroupId == null
    return MusicCatalogUi(
        sourceKind = sourceKind,
        fnLoggedIn = fnLoggedIn,
        fnAccount = fnAccount,
        groups = if (isFn) fnMusicGroups else emptyList(),
        tracks = if (isGroupStep) {
            emptyList()
        } else if (isFn) {
            fnMusicTracks
        } else {
            localEntries
        },
        currentGroupId = fnMusicCurrentGroupId,
        currentGroupTitle = fnMusicCurrentGroupTitle,
        loading = fnMusicLoading,
        error = fnMusicError,
        selected = selected,
        musicShuffled = musicShuffled,
        lyricsEnabled = lyricsEnabled,
        scanning = scanning,
        hasPermission = hasPermission,
    )
}

/**
 * 由 [AppState] 构造音乐弹窗快照（ViewModel 侧整包 copy 后调用）。
 * 折叠逻辑见 [buildMusicCatalogUi]。
 */
fun AppState.toMusicCatalogUi(): MusicCatalogUi = buildMusicCatalogUi(
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
    hasPermission = musicPermission,
)

/**
 * 当前来源下真正能勾选 / 能播放的曲目。
 *
 * 本地 id 与飞牛 id 天然不同前缀（`file:` / `fn:`），所以 [MusicCatalogUi.selected] 可以
 * 两套共存于同一个 Set——这里按来源挑列表，勾选集合本身不用在切源时清空。
 * VM 的 `ensureMusicPlaying()` / `toggleMusicAll()` 与 UI 的「已选 N 首」共用本函数，
 * 避免两边口径不一致（0.19.0 修过的老 bug）。
 */
fun AppState.visibleMusicEntries(): List<MusicEntry> =
    if (musicSourceKind == MusicSourceKind.FN) fnMusicTracks else musicEntries

/**
 * 当前这一屏**能勾选**的 id 集合：飞牛歌单步勾的是歌单（`fnpl:`），
 * 其余情况勾的是曲目（`file:` / `fn:`）。
 *
 * VM 的 `toggleMusicAll()` 与 UI 的「已选 N」/ 全选态共用这个口径，
 * 避免「显示已全选、按下去却没反应」这类老 bug。注意与
 * [visibleMusicEntries] 的区别：后者只管「能播放的曲目」，歌单 id 不在其中。
 */
fun AppState.selectableIds(): Set<String> =
    if (musicSourceKind == MusicSourceKind.FN && fnMusicCurrentGroupId == null) {
        fnMusicGroups.mapTo(HashSet()) { it.id }
    } else {
        visibleMusicEntries().mapTo(HashSet()) { it.id }
    }

/**
 * 看图页**幻灯片二级菜单「音乐」行**右侧的值形态（0.21.1 定稿）。
 *
 * 这一行只报「选了什么」，登录态一律不进这里：账号名属于设置页和音乐弹窗的
 * 来源面板，写在幻灯片菜单里既占地方又和勾选信息重复。所以入参里根本没有账号，
 * 任何来源 / 登录组合都不可能拼出「已登录：tv」。
 *
 *  - 本地 + 有勾选 → [MusicMenuLabel.Local]，UI 套「本地 5首」；
 *  - 飞牛 + 有勾选 → [MusicMenuLabel.Fn]，UI 套「飞牛音乐/相册专用」；
 *    冷启歌单名还没拉回来时 groupTitle 为 null，UI 退回只写「飞牛音乐」，不编名字；
 *  - 没勾选 → [MusicMenuLabel.None]，UI 套「未选择」（两种来源一样，不再显示来源名）。
 *
 * 纯函数、无 Android 依赖，可直接 JVM 单测；字符串资源由 Compose 侧按形态套用。
 */
sealed interface MusicMenuLabel {
    /** 本地源勾中的曲目数，恒 > 0。 */
    data class Local(val count: Int) : MusicMenuLabel

    /** 飞牛源勾中了歌单（或旧版 `fn:` 曲目）；groupTitle 为 null 表示名字暂不可用。 */
    data class Fn(val groupTitle: String?) : MusicMenuLabel

    /** 当前来源一个都没勾。 */
    object None : MusicMenuLabel
}

fun buildMusicMenuLabel(
    sourceKind: MusicSourceKind,
    localSelectedCount: Int,
    fnSelected: Boolean,
    fnGroupTitle: String?,
): MusicMenuLabel = when (sourceKind) {
    MusicSourceKind.LOCAL ->
        if (localSelectedCount > 0) MusicMenuLabel.Local(localSelectedCount)
        else MusicMenuLabel.None

    MusicSourceKind.FN ->
        if (fnSelected) MusicMenuLabel.Fn(fnGroupTitle)
        else MusicMenuLabel.None
}
