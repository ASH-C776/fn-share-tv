package com.kijeye.fnshare.data.lyrics

import com.kijeye.fnshare.core.AppDispatchers
import com.kijeye.fnshare.data.music.fn.FnMusicApi
import com.kijeye.fnshare.data.music.fn.FnMusicSession
import com.kijeye.fnshare.domain.model.LyricsDoc
import com.kijeye.fnshare.domain.model.fnTrackGuidOrNull
import com.kijeye.fnshare.domain.port.LyricsSource
import kotlinx.coroutines.withContext

/**
 * 该代码是从哪个原文件提取的：
 * 【新增实现，无函数体被搬动】。与 `LocalLyricsSource` 实现同一个
 * `domain/port/LyricsSource`，所以歌词浮层与 `DefaultLyricsTicker` 不需要认识
 * 「飞牛」这两个字：切源时换掉 provider，两列竖排楷体和 100ms 轮询一行不动。
 *
 * 该代码放在 `data/lyrics` 而不是 `data/music/fn`：它消费的是歌词，不是曲库；
 * 只 import 协议层的 [FnMusicApi] 与会话，不碰任何播放器。
 *
 * 对应的生命周期清理应该在原文件的哪个位置调用：
 * 【无需清理】。不缓存、不轮询、不持有协程作用域；每次换曲才发一次请求，
 * 失败安静返回 null（上层据此整体隐藏歌词浮层，与本地源语义一致）。
 * 真正需要 `cancel()` 的是歌词行号轮询，位置在 `ui/AppViewModel.onCleared()`。
 *
 * 线程约定：[load] 在 [AppDispatchers.io] 上跑；LRC 正文里的 `[offset:]` 标签
 * 由 `LrcParser.parse()` 处理，API 那个 `offset` 字段实测恒为 0，不重复平移。
 */
class FnLyricsSource(
    private val api: FnMusicApi,
    private val session: FnMusicSession,
) : LyricsSource {

    override suspend fun load(trackId: String): LyricsDoc? = withContext(AppDispatchers.io) {
        // 本地曲目是 `file:` 开头，交给 LocalLyricsSource；这里只处理 `fn:<guid>`。
        val guid = fnTrackGuidOrNull(trackId) ?: return@withContext null
        val lyric = try {
            session.withAuth { api.lyrics(guid) }
        } catch (e: Exception) {
            null
        }
        val content = lyric?.content.orEmpty()
        if (content.isBlank()) null else LrcParser.parse(content)
    }
}
