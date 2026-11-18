package com.kijeye.fnshare.domain.model

/**
 * 该代码是从哪个原文件提取的：
 * 【新增模型，无函数体被搬动】。为「本地音乐 / 飞牛音乐」双源新增的最小公共词汇表。
 *
 * 曲目模型**刻意复用既有的 [MusicEntry]**：它的 id 本来就是「身份 + 地址」
 * （本地是 `file:` 前缀，见 `domain/model/MusicEntry.kt:22`），时长、可播标记、
 * MediaStore id 都已在里面。再另造一个 MusicTrack 只会产生两套身份和一次有损转换
 * （本地的 mediaStoreId 会在转换里丢掉），反而不符合低耦合。飞牛曲目用 `fn:` + guid
 * 填进同一个 id，于是 `engine/api/AudioEngine.play(List<MusicEntry>)` 与
 * `domain/port/LyricsSource.load(id)` 两套既有契约都能原样复用，本地链路一行不动。
 *
 * 对应的生命周期清理应该在原文件的哪个位置调用：
 * 【无需清理】。全是枚举、data class 与纯字符串函数，不持有线程、定时器或句柄。
 * `AppViewModel.onCleared()` 不为此新增调用。
 *
 * 线程约定：本文件所有函数都是纯函数，可在任意线程调用；真正做 IO 的是
 * `domain/port/MusicCatalogPort` 的实现（必须切到 Dispatchers.IO）。
 */

/** 当前选中的曲库来源。持久化时存 name()，别在 UI 里散落 "local" / "fn" 字符串。 */
enum class MusicSourceKind { LOCAL, FN }

/**
 * 曲库的一层分组。本地源只有一个「全部曲目」组；飞牛源一个组 = 一个歌单。
 * UI 只认这个类型，不 import 任何 `data.music.fn` 的 DTO。
 */
data class MusicGroup(
    val id: String,
    val title: String,
    val subtitle: String = "",
)

/** 本地源唯一的组 id。 */
const val LOCAL_MUSIC_GROUP_ID = "local:all"

/** 飞牛曲目的 id 前缀：`fn:` + trackGuid。播放流与歌词都靠它反解 guid。 */
const val FN_TRACK_PREFIX = "fn:"

/** 飞牛歌单的 id 前缀：`fnpl:` + playlistGuid。 */
const val FN_PLAYLIST_PREFIX = "fnpl:"

fun fnTrackId(guid: String): String = FN_TRACK_PREFIX + guid

/** 不是飞牛曲目（本地 `file:` 开头）时返回 null，歌词源据此分流。 */
fun fnTrackGuidOrNull(id: String): String? = id
    .takeIf { it.startsWith(FN_TRACK_PREFIX) }
    ?.removePrefix(FN_TRACK_PREFIX)
    ?.takeIf { it.isNotBlank() }

fun fnPlaylistId(guid: String): String = FN_PLAYLIST_PREFIX + guid

fun fnPlaylistGuidOrNull(id: String): String? = id
    .takeIf { it.startsWith(FN_PLAYLIST_PREFIX) }
    ?.removePrefix(FN_PLAYLIST_PREFIX)
    ?.takeIf { it.isNotBlank() }

/** 单个选择 id 是否属于飞牛侧：歌单（`fnpl:`）或旧版直接勾的曲目（`fn:`）。 */
fun isFnSelectionId(id: String): Boolean =
    fnPlaylistGuidOrNull(id) != null || fnTrackGuidOrNull(id) != null

/**
 * 选择集里有没有飞牛项。
 *
 * 飞牛源下的选择集可能是「从本地切过来、只勾过 `file:` 曲目」的状态，
 * 此时不该为了展开歌单发网络请求，也不该误判成「有背景乐」。
 * [isFnSelectionId] 同时给菜单算「本地已选 N 首」用，避免切回本地源时把
 * 落盘的歌单 id 也数成一首歌。
 */
fun hasFnSelection(selected: Set<String>): Boolean = selected.any(::isFnSelectionId)

/**
 * 选择集里被勾选的飞牛歌单标题，供二级菜单显示「飞牛音乐/歌单名」。
 *
 * 按 [groups] 的顺序取第一张，多选时显示也稳定（不会随 Set 的迭代顺序跳）。
 * 冷启时歌单列表还没拉回来、或勾的是旧版 `fn:` 曲目 id，都返回 null ——
 * 调用方退回只显示来源，不要在菜单上编一个名字出来。
 */
fun selectedFnGroupTitle(selected: Set<String>, groups: List<MusicGroup>): String? =
    groups.firstOrNull { fnPlaylistGuidOrNull(it.id) != null && it.id in selected }
        ?.title
        ?.takeIf { it.isNotBlank() }
