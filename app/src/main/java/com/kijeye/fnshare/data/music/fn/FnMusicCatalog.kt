package com.kijeye.fnshare.data.music.fn

import com.kijeye.fnshare.domain.model.MusicEntry
import com.kijeye.fnshare.domain.model.MusicGroup
import com.kijeye.fnshare.domain.model.MusicSourceKind
import com.kijeye.fnshare.domain.model.fnPlaylistGuidOrNull
import com.kijeye.fnshare.domain.model.fnPlaylistId
import com.kijeye.fnshare.domain.model.fnTrackId
import com.kijeye.fnshare.domain.port.MusicCatalogPort

/**
 * 该代码是从哪个原文件提取的：
 * 【新增实现，无函数体被搬动】。把飞牛的「歌单 → 歌单内曲目」两级接口映射成
 * `MusicCatalogPort` 的「分组 → 曲目」，UI 不认识 `FnPlaylist` / `FnTrack`，
 * 也不认识 guid 之外的任何 HTTP 细节。
 *
 * 对应的生命周期清理应该在原文件的哪个位置调用：
 * 【无需清理】。只缓存一份歌单列表用于 `hasPlaylist()` 校验（切账号后判断旧歌单还在不在），
 * 无 fd、无线程、无监听。`AppViewModel.onCleared()` 不为此新增调用。
 *
 * 线程约定：IO 由 `FnMusicApi` → `FnMusicTransport` 切到 `AppDispatchers.io`；
 * 本类只做纯映射，返回值交给 ViewModel 在主线程写 StateFlow。
 */
class FnMusicCatalog(
    private val api: FnMusicApi,
    private val session: FnMusicSession,
) : MusicCatalogPort {

    override val kind: MusicSourceKind = MusicSourceKind.FN

    @Volatile
    private var playlists: List<FnPlaylist> = emptyList()

    /** 歌单列表不显示曲目数：接口没有 trackCount，硬显示就得为每个歌单多发一次请求。 */
    override suspend fun listGroups(): List<MusicGroup> = session.withAuth {
        playlists = api.playlists()
        playlists.map { playlist ->
            MusicGroup(
                id = fnPlaylistId(playlist.guid),
                title = playlist.name,
                subtitle = "飞牛音乐",
            )
        }
    }

    override suspend fun listTracks(groupId: String): List<MusicEntry> {
        val guid = fnPlaylistGuidOrNull(groupId) ?: return emptyList()
        return session.withAuth {
            api.playlistTracks(guid)
                // isCue 是 CUE 片段，服务端根本给不出可播流；勾了却没声音最难自查。
                .filterNot { it.isCue }
                .map { track ->
                    MusicEntry(
                        id = fnTrackId(track.guid),
                        title = if (track.artist.isBlank()) {
                            track.title
                        } else {
                            "${track.title} · ${track.artist}"
                        },
                        durationMs = track.durationMs,
                        // 网络曲目没有 MediaStore id 与本地路径；身份与地址全在 `fn:` id 里。
                        playable = true,
                    )
                }
        }
    }

    /** 换账号 / 重登后，旧 `lastPlaylistId` 可能已不属于新账号。 */
    fun hasPlaylist(groupId: String): Boolean =
        playlists.any { fnPlaylistId(it.guid) == groupId }
}
