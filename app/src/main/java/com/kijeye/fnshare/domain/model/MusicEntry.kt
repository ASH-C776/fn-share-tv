package com.kijeye.fnshare.domain.model

/**
 * 该代码是从哪个原文件提取的：
 * `data/music/MusicEntry.kt` 整个文件的**全部声明**（`MusicEntry` data class、
 * `MUSIC_FILE_PREFIX`、`musicIdFromPath`、`musicPathFromId`、`musicTitleFromFileName`、
 * `MAX_TITLE_CHARS`、`PLAYABLE_EXTENSIONS`、`isPlayableAudioName`）。
 * **逐字搬移，只改 package 声明，未改动任何一行逻辑或常量值**。
 * 原文件保留为「兼容转发层」：类型用 `typealias`，顶层函数/常量用一行转发，
 * 于是 `data/music/MusicScanner.kt`、`data/music/SlideshowMusicPlayer.kt`、
 * `ui/AppViewModel.kt`、`ui/MusicDialog.kt` 等既有 import 全部无需改动。
 *
 * 对应的生命周期清理应该在原文件的哪个位置调用：
 * 【无需清理】。全是纯值与纯函数，`PLAYABLE_EXTENSIONS` 是只读 Set 常量，
 * 不持有线程、定时器、文件句柄或 Context。原 `MusicEntry.kt` 没有清理点，
 * `MainActivity.onDestroy()` / `AppViewModel.onCleared():1198` 不需要新增调用。
 */

/**
 * 幻灯片能播的一首曲子。
 *
 * [id] 同时充当身份和地址：本地文件是 `file:` 加绝对路径。身份因此不会因为列表被重新扫描
 * 就变掉——地址不变，id 就不变，上次存下的选中项下次仍然对得上。
 *
 * 时长与大小都来自 MediaStore 的列，扫描阶段顺手读出来，不再为每首歌开一次解码器
 * （几十首就是几十次 IO，纯粹浪费）。
 */
data class MusicEntry(
    val id: String,
    val title: String,
    /** MediaStore 给的时长（毫秒），读不到记 0。 */
    val durationMs: Long = 0L,
    val sizeBytes: Long = 0L,
    /**
     * 这首歌在系统媒体库里的 `_id`，0 表示媒体库没索引过。
     *
     * 有它就要走 `content://` 打开：个别电视 ROM 的 FUSE 把底层文件的属主和权限位
     * 原样透出来（比如 `u0_a53:media_rw 0660`），别的应用拷进来的歌按路径直接 open
     * 会被 DAC 挡成 EACCES——`READ_MEDIA_AUDIO` 给了也没用，那是 ROM 的存储栈没做
     * 属主映射。MediaProvider 自己打开再交 fd 的 `content://` 路线不吃这一套。
     */
    val mediaStoreId: Long = 0L,
    /**
     * 这个容器 ExoPlayer 认不认。
     *
     * 扫描只看扩展名，所以 ape / dsd / wma 这类也会进列表——它们能读出来（[MusicScanner]
     * 用 `MediaMetadataRetriever` 拿时长，系统解码器认），却放不出声：
     * `ProgressiveMediaPeriod` 里的那一串 extractor 没有对应的解封装器。列表里要标出来，
     * 勾了却没声音是最难自己诊断的一种失败。
     */
    val playable: Boolean = true,
)

/** 磁盘文件的 id 前缀：`file:` + 绝对路径。 */
const val MUSIC_FILE_PREFIX = "file:"

/** 磁盘上那首歌在存储里对应的 id。 */
fun musicIdFromPath(path: String): String = MUSIC_FILE_PREFIX + path

/** 从存储 id 还原出磁盘路径；id 不是 `file:` 写法时返回 null。 */
fun musicPathFromId(id: String): String? = id
    .takeIf { it.startsWith(MUSIC_FILE_PREFIX) }
    ?.removePrefix(MUSIC_FILE_PREFIX)
    ?.takeIf { it.isNotBlank() }

/**
 * 拿不到标题时怎么显示：去掉扩展名的文件名。
 *
 * 下划线保留不动。`my_song.mp3` 是制作者自己起的名，改成「my song」等于 App 擅自修改
 * 一个它只被要求展示的名字。
 */
fun musicTitleFromFileName(name: String): String {
    val trimmed = name.substringBeforeLast('.', name).trim()
    return trimmed.ifBlank { name }.take(MAX_TITLE_CHARS)
}

/** 标题上限；远超电视一行能放下的长度。 */
private const val MAX_TITLE_CHARS = 80

/**
 * ExoPlayer（media3 1.7.1）**能解封装**的音频扩展名。
 *
 * 依据它自带的 extractor 列表：Mp3、Flac、Wav、Mp4/M4a、Ogg(Opus/Vorbis)、Adts(Aac)、
 * Matroska(Mka)、Amr、Ac3/Ac4、Avi、Flv。
 * 不在表里的（ape / dsd / wma / aiff）能扫出来、也能显示时长，但一放就报
 * `UnrecognizedInputFormatException`——那正是「勾了歌、幻灯片在走、却一声不响」的根。
 */
private val PLAYABLE_EXTENSIONS = setOf(
    "mp3", "flac", "wav", "m4a", "m4b", "mp4", "aac", "ogg", "opus",
    "mka", "mkv", "amr", "ac3", "ac4", "avi", "flv", "ts", "3gp",
)

/** 这个文件名对应的容器，播放器认不认。 */
fun isPlayableAudioName(name: String): Boolean =
    name.substringAfterLast('.', "").lowercase() in PLAYABLE_EXTENSIONS
