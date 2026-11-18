package com.kijeye.fnshare.data.lyrics

import android.content.ContentUris
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import com.kijeye.fnshare.data.music.musicPathFromId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.LinkedHashMap

/**
 * 按曲目找歌词：同一目录、同名、扩展名换成 `.lrc`。
 *
 * 查找走两条路，对应 [com.kijeye.fnshare.data.music.MusicScanner] 记下的电视 ROM 存储坑：
 *
 * 1. **文件直读**：`MusicEntry.id`（`file:<绝对路径>`）还原出音频路径，兄弟文件直接
 *    `File.readBytes()`。应用自己目录或属主正常的共享存储走这条路。
 * 2. **媒体库兜底**：个别 ROM 的 FUSE 把共享存储底层属主透出来（`u0_a53:media_rw 0660`），
 *    别的应用拷进来的文件按路径 open 直接 EACCES，`READ_MEDIA_AUDIO` 给了也没用。
 *    `.lrc` 不是媒体文件、媒体库通常不索引，但查一遍 `MediaStore.Files`——真被索引过的
 *    话就换 `content://` 让 MediaProvider 自己开 fd 交过来。
 * 3. 两路都空：对 `.lrc` 路径触发一次媒体扫描（fire-and-forget），下一回合有可能就有。
 *    本回合安静返回 null——上层据此整体不显示，用户顶多看不到歌词，绝不能崩。
 *
 * 结果按曲目 id 做 LRU 缓存；File 命中的条目带 mtime+size 指纹，用户换过 `.lrc` 后
 * 重进这首歌能拿到新词。走媒体库兜底的条目没有可靠指纹，会话内沿用旧词（量级可忽略）。
 */
class LyricsRepo(private val context: Context) {

    private data class CacheEntry(val doc: LyricsDoc?, val fingerprint: String?)

    /** 上限 8 首：幻灯片一个曲库通常几首到几十首，够覆盖来回切换的热区。 */
    private val cache = object : LinkedHashMap<String, CacheEntry>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>): Boolean =
            size > 8
    }

    /** 找并解析 [entryId] 这首歌的歌词；任何失败都安静返回 null。IO 线程调用安全。 */
    suspend fun load(entryId: String): LyricsDoc? = withContext(Dispatchers.IO) {
        runCatching { loadInternal(entryId) }.getOrNull()
    }

    private fun loadInternal(entryId: String): LyricsDoc? {
        val audioPath = musicPathFromId(entryId) ?: return null
        val lrcFile = File(
            audioPath.substringBeforeLast('/', audioPath),
            audioPath.substringAfterLast('/').substringBeforeLast('.', audioPath) + ".lrc",
        )
        val lrcPath = lrcFile.absolutePath

        // File 直读路：带指纹缓存，文件换过能察觉。
        val stat = runCatching { lrcFile.takeIf { it.isFile }?.let { it.lastModified() to it.length() } }
            .getOrNull()
        if (stat != null) {
            val fingerprint = "${stat.first}:${stat.second}"
            cache[lrcPath]?.let { cached ->
                if (cached.fingerprint == fingerprint) return cached.doc
            }
            val doc = readAndParse { lrcFile.readBytes() }
            // 只有真解析出歌词才收工。文件 stat 得到（元数据可读）而 open 被 DAC 挡下，
            // 正是电视 ROM 的典型表现：读不到就继续往下试媒体库，不能在这里一口回绝。
            if (doc != null) {
                cache[lrcPath] = CacheEntry(doc, fingerprint)
                return doc
            }
        }

        // 文件 stat 都失败：可能被 DAC 挡了，试媒体库兜底。
        val viaStore = readViaMediaStore(lrcPath)
        if (viaStore != null) {
            cache[lrcPath] = CacheEntry(viaStore, fingerprint = null)
            return viaStore
        }

        // 两路都空：请系统扫描器把这个 .lrc 收进媒体库，下次也许就有。不阻塞本回合。
        MediaScannerConnection.scanFile(context, arrayOf(lrcPath), null) { _, _ -> }
        return null
    }

    /** `DATA = lrcPath` 查 `_id`，命中则 content:// 开流读。任何一步失败返回 null。 */
    private fun readViaMediaStore(lrcPath: String): LyricsDoc? {
        val projection = arrayOf(MediaStore.Files.FileColumns._ID)
        val id = runCatching {
            context.contentResolver.query(
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
                projection,
                "${MediaStore.Files.FileColumns.DATA} = ?",
                arrayOf(lrcPath),
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else null
            }
        }.getOrNull() ?: return null

        val uri: Uri = ContentUris.withAppendedId(
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), id,
        )
        return readAndParse {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("openInputStream 返回 null")
        }
    }

    private fun readAndParse(read: () -> ByteArray): LyricsDoc? = runCatching {
        val bytes = read()
        LrcParser.decode(bytes)?.let { LrcParser.parse(it) }
    }.onFailure {
        Log.w(TAG, "歌词读取失败：${it.message}")
    }.getOrNull()

    private companion object {
        const val TAG = "LyricsRepo"
    }
}
