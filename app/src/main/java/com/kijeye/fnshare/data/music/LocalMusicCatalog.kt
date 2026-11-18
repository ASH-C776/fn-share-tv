package com.kijeye.fnshare.data.music

import com.kijeye.fnshare.core.AppDispatchers
import com.kijeye.fnshare.domain.model.LOCAL_MUSIC_GROUP_ID
import com.kijeye.fnshare.domain.model.MusicEntry
import com.kijeye.fnshare.domain.model.MusicGroup
import com.kijeye.fnshare.domain.model.MusicSourceKind
import com.kijeye.fnshare.domain.port.MusicCatalogPort
import com.kijeye.fnshare.domain.port.MusicSource
import kotlinx.coroutines.withContext

/**
 * 该代码是从哪个原文件提取的：
 * 【新增适配器，原文件零改动】。只包 `domain/port/MusicSource`（其实现
 * `LocalMusicSource` 又只转发 `MusicScanner`），给本地曲库补上一层「分组」的外观，
 * 好让 UI 用同一个 `MusicCatalogPort` 处理本地与飞牛。扫描逻辑、排序、id 编码
 * **一行都没动**。
 *
 * 对应的生命周期清理应该在原文件的哪个位置调用：
 * 【无需清理】。只缓存一份扫描结果（不可变 List），无 fd、无线程、无监听。
 * `AppViewModel.onCleared()` 不为此新增调用；VM 重建时缓存随之丢弃，下次打开弹窗
 * 会重新 [listGroups] 扫一遍，与既有「刷新」行为一致。
 *
 * 线程约定：[listGroups] / [listTracks] 在 [AppDispatchers.io] 上跑；`MusicSource.scan()`
 * 内部本来就自带一次 IO 调度，外层是同一 dispatcher 的幂等调度。
 */
class LocalMusicCatalog(
    private val source: MusicSource,
) : MusicCatalogPort {

    override val kind: MusicSourceKind = MusicSourceKind.LOCAL

    private var scanned: List<MusicEntry> = emptyList()

    /** 每次打开 / 刷新都重新扫一遍；subtitle 里的首数只是顺手显示，不参与任何判断。 */
    override suspend fun listGroups(): List<MusicGroup> = withContext(AppDispatchers.io) {
        scanned = source.scan()
        listOf(MusicGroup(LOCAL_MUSIC_GROUP_ID, "本地音乐", "${scanned.size} 首"))
    }

    override suspend fun listTracks(groupId: String): List<MusicEntry> = withContext(AppDispatchers.io) {
        if (groupId != LOCAL_MUSIC_GROUP_ID) return@withContext emptyList()
        // 正常路径是「先 listGroups 再 listTracks」；直接调 listTracks 时也别返回空。
        scanned.ifEmpty { source.scan().also { scanned = it } }
    }
}
