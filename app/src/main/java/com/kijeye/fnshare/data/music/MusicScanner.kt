package com.kijeye.fnshare.data.music

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.system.Os
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 读电视存储里 `music`（含 `Music` / `音乐`）文件夹下的音频，作为幻灯片背景乐。
 *
 * 扫描主路是**媒体库（MediaStore）**，磁盘遍历只作辅助：
 *
 * 1. 先 `File.listFiles()` 列音乐目录作补充。但这条路在这类电视 ROM 上往往**走不通**——
 *    电视把音乐目录的属主透出来（`u0_aXX:media_rw`，权限 0770），应用自身 UID 列目录直接
 *    `Permission denied`，扫不到任何歌。所以这条路空手而归是常态，不要依赖它。
 * 2. **媒体库是主路**：`MediaStore.Audio` 查一遍。媒体库跑在系统 media 进程里，能读应用
 *    读不到的目录；同一路径以媒体库的标题/时长为准（TITLE 通常比文件名干净）。
 *
 * 关键修复：媒体库**不会自动索引**新拷进来的歌。每次扫描前主动调
 * [forceRescan] 让系统媒体扫描器重新扫一遍音乐文件夹，否则新歌永远出不来
 *（这就是早先「加了几首歌却只显示旧的 1 首」的根因）。
 *
 * 时长拿不到时才有代价：媒体库还没索引的歌只能为它单开一次
 * [MediaMetadataRetriever] 解码；已索引的直接用列值，不重复解码。
 */
class MusicScanner(private val context: Context) {

    /** 这个扫描要不要先要权限、要哪个权限。Android 13+ 是 [Manifest.permission.READ_MEDIA_AUDIO]，
     *  之前是 [Manifest.permission.READ_EXTERNAL_STORAGE]。 */
    val requiredPermission: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, requiredPermission) == PackageManager.PERMISSION_GRANTED

    /**
     * 扫一遍。耗时操作，调用方应在 IO 线程（这里自己切到 [Dispatchers.IO]）。
     * 没权限时返回空列表——界面据此决定显示「去授权」而不是「没扫到歌」。
     */
    suspend fun scan(): List<MusicEntry> = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext emptyList()

        // 先让系统媒体库把音乐文件夹重新索引一遍。这台电视的存储栈把目录属主透出来
        // （`/sdcard/Music` 是别的 UID 的 0770），应用自己 `File.listFiles()` 直接
        // Permission denied，磁盘那路扫不到任何歌，只能靠媒体库兜底。但媒体库不会自动
        // 索引新拷进来的文件 → 应用只看得见旧的那几首。主动触发一次索引，新文件进表后，
        // 下面的 queryMediaStore 才能查到它们。（媒体库跑在系统进程里，不受应用目录权限限制。）
        forceRescan()

        // 媒体库一次查完，建成 规范路径 -> (标题, 时长) 的索引：磁盘那路扫到同一首歌时直接取用，
        // 免得为每首歌都开一次解码器。
        val indexed = queryMediaStore()
        val found = LinkedHashMap<String, MusicEntry>()
        // 同一物理文件可能被多个路径写法指到（大小写不敏感的文件系统上
        // Music/music/MUSIC 是同一个目录），inode 去重挡住这种重复。
        val seenInodes = HashSet<String>()
        // 媒体库没索引的歌：先记下路径、列表先出，最后再并发补时长。
        // 逐首串行开 [MediaMetadataRetriever] 的话，几十首歌能让人等好几秒。
        val needDuration = mutableListOf<String>()

        for (dir in musicDirs()) {
            val files = runCatching { dir.listFiles() }.getOrNull().orEmpty()
            for (file in files) {
                if (!file.isFile || !isAudioFile(file.name)) continue
                val inode = inodeKey(file)
                if (inode != null && !seenInodes.add(inode)) continue
                val path = canonicalPath(file)
                if (path in found) continue
                val fromDb = indexed[path]
                val dbDuration = fromDb?.durationMs?.takeIf { it > 0L } ?: 0L
                if (dbDuration == 0L) needDuration += path
                found[path] = MusicEntry(
                    id = musicIdFromPath(path),
                    title = fromDb?.title?.takeIf { it.isNotBlank() }
                        ?: musicTitleFromFileName(file.name),
                    durationMs = dbDuration,
                    sizeBytes = file.length(),
                    mediaStoreId = fromDb?.storeId ?: 0L,
                    playable = isPlayableAudioName(file.name),
                )
            }
        }

        // 媒体库里有、但磁盘那路没扫到的（外接卷被挡、或目录名不叫 music 的旧索引），
        // 只要父目录过得了 isInMusicFolder 就补进来。
        //
        // 前提是磁盘那路起码扫到了一首：那说明 File 访问正常，媒体库里的条目就要拿
        // 文件是否真实存在过一遍——歌删了而媒体库还没更新的「幽灵记录」（大小 0、点了播不出）
        // 不能进列表。反过来，磁盘一首都没扫到说明 File 被挡了，这时只能信媒体库。
        val diskAccessWorks = found.isNotEmpty()
        for ((path, meta) in indexed) {
            if (path in found) continue
            val file = File(path)
            val inode = inodeKey(file)
            if (inode != null && inode in seenInodes) continue
            if (diskAccessWorks && !file.exists()) continue
            val parent = file.parentFile?.absolutePath?.plus("/") ?: continue
            if (!isInMusicFolder("", parent)) continue
            found[path] = MusicEntry(
                id = musicIdFromPath(path),
                title = meta.title.ifBlank { musicTitleFromFileName(file.name) },
                durationMs = meta.durationMs.coerceAtLeast(0L),
                sizeBytes = file.length(),
                mediaStoreId = meta.storeId,
                playable = isPlayableAudioName(file.name),
            )
        }

        // 没索引的歌并发补时长。解码是 CPU + IO 混合活，4 路并发已经把电视那几颗
        // 小核喂饱；再多只会互相抢内存带宽。
        if (needDuration.isNotEmpty()) {
            val decoderDispatcher = Dispatchers.IO.limitedParallelism(4)
            val durations = ConcurrentHashMap<String, Long>()
            coroutineScope {
                needDuration.forEach { path ->
                    launch(decoderDispatcher) { durations[path] = readDurationMs(path) }
                }
            }
            for (path in needDuration) {
                val entry = found[path] ?: continue
                found[path] = entry.copy(durationMs = durations[path] ?: 0L)
            }
        }

        found.values.sortedBy { it.title.lowercase() }
    }

    /**
     * 强制系统媒体库重新索引各存储根下的 `music` 文件夹。
     *
     * 为什么需要这一步：应用自身 UID 在这台电视（以及同类 ROM）上列不到 `Music` 目录
     * （目录属主透出、DAC 挡住 `listFiles`，报 Permission denied），磁盘那路是死的，
     * 全靠媒体库兜底；而媒体库不会自动索引新拷进来的歌。这里主动调 `MediaScannerConnection`
     * 让**系统媒体扫描器**（跑在 media UID 进程里，能读应用读不到的目录）扫一遍音乐文件夹，
     * 新文件进表后 [queryMediaStore] 才查得到。即使某个候选路径不存在，扫描器也只会回调空 uri，
     * 不会报错。
     *
     * 注意：目录型 `scanFile` 的回调触发次数在不同 ROM 上不稳定（按目录或按文件都可能），
     * 所以**不靠回调计数来结束等待**——直接触发后让出一小段时间给扫描器索引，再继续查表。
     */
    private suspend fun forceRescan() {
        val roots = LinkedHashSet<String>()
        runCatching {
            Environment.getExternalStorageDirectory()?.absolutePath?.let { roots += it }
        }
        runCatching {
            context.getExternalFilesDirs(null).filterNotNull().forEach { dir ->
                var node: File? = dir
                repeat(4) { node = node?.parentFile }
                node?.absolutePath?.let { roots += it }
            }
        }
        // 不能先 isDirectory 过滤——应用自己 stat 不到这个目录（Permission denied），
        // 过滤掉就传不进去了。media 扫描器能 stat，不存在的路径它自己忽略。
        val paths = roots.flatMap { root -> MUSIC_DIR_NAMES.map { File(root, it).absolutePath } }
            .distinct().toTypedArray()
        if (paths.isEmpty()) return

        MediaScannerConnection.scanFile(context, paths, null) { _, _ -> }
        // 媒体库索引是异步的，给扫描器一点时间把新文件扫进 audio 表。
        // 电视音乐文件夹通常就几首到几十首，1.5s 足够；即便个别文件没赶上这次查询，
        // 下一次「扫描电视」会再触发一遍。
        delay(1500)
    }

    /**
     * 媒体库里一首歌的元数据：标题、时长，以及能换 `content://` 地址的 `_id`。
     */
    private data class StoreMeta(val title: String, val durationMs: Long, val storeId: Long)

    /**
     * 媒体库里所有过得了 [isInMusicFolder] 的曲目：规范路径 -> [StoreMeta]。
     * 查不到（极少数 ROM 的只读限制）就当没有，不影响磁盘那路。
     */
    private fun queryMediaStore(): Map<String, StoreMeta> {
        val projection = buildList {
            add(MediaStore.Audio.Media._ID)
            add(MediaStore.Audio.Media.DISPLAY_NAME)
            add(MediaStore.Audio.Media.TITLE)
            add(MediaStore.Audio.Media.DURATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.Audio.Media.RELATIVE_PATH)
            }
            // DATA 在 API 29+ 被标弃用但仍返回绝对路径；低版本只能靠它定位文件。
            add(MediaStore.Audio.Media.DATA)
        }.toTypedArray()

        val out = LinkedHashMap<String, StoreMeta>()
        val cursor = runCatching {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                "${MediaStore.Audio.Media.TITLE} ASC",
            )
        }.getOrNull() ?: return out

        cursor.use {
            val idCol = it.getColumnIndex(MediaStore.Audio.Media._ID)
            val nameCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val titleCol = it.getColumnIndex(MediaStore.Audio.Media.TITLE)
            val durCol = it.getColumnIndex(MediaStore.Audio.Media.DURATION)
            val relCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                it.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
            } else {
                -1
            }
            val dataCol = it.getColumnIndex(MediaStore.Audio.Media.DATA)
            if (dataCol < 0) return out

            while (it.moveToNext()) {
                val data = it.getString(dataCol).orEmpty()
                if (data.isBlank()) continue
                val relPath = if (relCol >= 0) it.getString(relCol).orEmpty() else ""
                if (!isInMusicFolder(relPath, data)) continue
                val title = if (titleCol >= 0) it.getString(titleCol).orEmpty() else ""
                val name = it.getString(nameCol).orEmpty()
                val dur = if (durCol >= 0) it.getLong(durCol) else 0L
                val storeId = if (idCol >= 0) it.getLong(idCol) else 0L
                out[canonicalPath(File(data))] =
                    StoreMeta(title.ifBlank { musicTitleFromFileName(name) }, dur, storeId)
            }
        }
        return out
    }

    /**
     * 可能要扫的音乐目录：内置存储根目录，外加每块外部卷（U 盘/移动硬盘）的根目录。
     * 每个根都把 `Music` / `music` / `音乐` 几种写法试一遍——Linux 文件名区分大小写，
     * `Music` 和 `music` 是两个不同的目录，电视上的文件管理器又都爱自作主张。
     */
    private fun musicDirs(): List<File> {
        val roots = LinkedHashSet<String>()
        runCatching {
            Environment.getExternalStorageDirectory()?.absolutePath?.let { roots += it }
        }
        // /storage/XXXX-XXXX/Android/data/<pkg>/files 往上退四级就是那块卷的根。
        runCatching {
            context.getExternalFilesDirs(null).filterNotNull().forEach { dir ->
                var node: File? = dir
                repeat(4) { node = node?.parentFile }
                node?.absolutePath?.let { roots += it }
            }
        }

        val dirs = LinkedHashMap<String, File>()
        for (root in roots) {
            for (name in MUSIC_DIR_NAMES) {
                val dir = File(root, name)
                if (dir.isDirectory) dirs.putIfAbsent(dir.absolutePath, dir)
            }
        }
        return dirs.values.toList()
    }

    /** 扩展名（小写）在 [AUDIO_EXTENSIONS] 里就算音频。电视上常见的是 mp3/flac/m4a。 */
    private fun isAudioFile(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS

    /** 单个文件的时长。读不出来记 0，不因此把这首歌丢掉。 */
    private fun readDurationMs(path: String): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * 同一个文件在不同来源里可能写成 `/sdcard/Music/a.mp3` 或
     * `/storage/emulated/0/Music/a.mp3`。统一成规范路径，才不会在列表里出现两份，
     * 也不会让昨天存下的选中项今天对不上 id。
     */
    private fun canonicalPath(file: File): String =
        runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)

    /**
     * 同一物理文件的唯一标识：设备号 + inode。
     *
     * 只靠路径去不了重——大小写不敏感的文件系统上 `/sdcard/Music`、`/sdcard/music`、
     * `/sdcard/MUSIC` 三个写法 `isDirectory` 全是 true，`canonicalPath` 也不会把大小写
     * 还原成目录的真实名字，于是同一首歌会被扫成三条。inode 不随写法变，用它才挡得住。
     * 文件不存在或 stat 不到时返回 null（该条目不参与这套去重）。
     */
    private fun inodeKey(file: File): String? = runCatching {
        val st = Os.stat(file.absolutePath)
        "${st.st_dev}:${st.st_ino}"
    }.getOrNull()

    /**
     * 这首歌是不是在用户的 `music` 文件夹里。
     *
     * 相对路径首段是 `music`（任意大小写）或 `音乐` 即可；低版本没有相对路径，退而求其次
     * 看绝对路径里是否含 `/music/`。带 `/music/` 的才算——`/sdcard/Podcasts/` 之类不算，
     * 也不会误伤叫 `musicbox` 的目录（首段精确匹配）。
     */
    private fun isInMusicFolder(relativePath: String, dataPath: String): Boolean {
        val rel = relativePath.trim()
        if (rel.isNotEmpty()) {
            val top = rel.substringBefore('/').lowercase()
            if (top == "music" || top == "音乐") return true
        }
        val lower = dataPath.lowercase()
        return lower.contains("/music/") || lower.contains("/音乐/")
    }
}

/** 音乐文件夹的几种常见写法，大小写都要试。 */
private val MUSIC_DIR_NAMES = listOf("Music", "music", "MUSIC", "音乐")

/** 认得出的音频扩展名。 */
private val AUDIO_EXTENSIONS = setOf(
    "mp3", "flac", "m4a", "m4b", "aac", "wav", "ogg", "opus",
    "wma", "ape", "amr", "mka", "aiff", "aif", "dsf", "dff",
)
