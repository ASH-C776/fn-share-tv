package com.kijeye.fnshare.domain.port

import com.kijeye.fnshare.domain.model.MusicEntry
import com.kijeye.fnshare.domain.model.MusicGroup
import com.kijeye.fnshare.domain.model.MusicSourceKind

/**
 * 该代码是从哪个原文件提取的：
 * 【新增契约，无函数体被搬动】。签名对齐 `domain/port/MusicSource.kt:32` 的
 * `suspend scan(): List<MusicEntry>`（本地源原样包住它，一行不改），并补上「分组」
 * 这一层给飞牛歌单用。UI / ViewModel 只看见本接口与 [MusicGroup] / [MusicEntry]，
 * 不认识歌单 GUID、token 或任何 HTTP 细节。
 *
 * 对应的生命周期清理应该在原文件的哪个位置调用：
 * 【本接口无需清理】。两个方法都是一次性 suspend，不持有定时器或播放器。
 * 实现 `FnMusicCatalog` 复用 `di/AppContainer` 的进程级 OkHttpClient，连接池随进程回收；
 * `AppViewModel.onCleared()` 只需 release 播放器与歌词轮询（见 `engine/api/AudioEngine.kt`
 * 与 `engine/api/LyricsTicker.kt` 文件头），不为此新增调用。
 *
 * 线程约定：实现方必须把网络 / 媒体库遍历放在 Dispatchers.IO；返回值由 ViewModel
 * 在主线程写进 StateFlow。UI 禁止直接调用本接口。
 *
 * 「刷新」= 重新调 [listGroups]，不另设 refresh()：本地 scan() 每次都是全新扫描，
 * 飞牛每次都重发歌单请求。少一个方法就少一条会忘记同步的状态。
 */
interface MusicCatalogPort {

    val kind: MusicSourceKind

    /** 列出这一层的分组；本地返回一个「全部曲目」组，飞牛返回全部歌单。 */
    suspend fun listGroups(): List<MusicGroup>

    /** 列出某个分组内的曲目；顺序即播放顺序。空组返回空列表。 */
    suspend fun listTracks(groupId: String): List<MusicEntry>
}
