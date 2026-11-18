package com.kijeye.fnshare.data.cache

import android.content.Context
import com.kijeye.fnshare.data.fn.SharedAlbum
import com.kijeye.fnshare.data.fn.SharedPhoto
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 相册的**元数据缓存**：把整本相册的照片清单（id、拍摄时间、地址、文件名、地点）落在本地。
 *
 * ## 为什么缓存这个
 *
 * 照片的拍摄时间是死的——EXIF 里的年月不会变。每次进相册都重拉一遍整本清单纯属浪费，
 * 更糟的是它让时间轴在头几秒是残缺的（只看得见已加载的那几十张）。
 * 缓存之后：进相册一个请求都不发，时间轴一打开就是完整的，跳到任意一年都是瞬时。
 *
 * ## 存什么、怎么存
 *
 * - **存事实，不存结论**：只存拍摄时间，不存「哪年哪月」「几个月龄」。
 *   宝宝相册改了生日也不用清缓存——月龄是运行时算的（见 `buildTimeline`）。
 * - **地址存相对路径**：fnOS 给的是相对路径，绝对地址是 `baseUrl + path` 拼出来的
 *   （见 `FnApi.absolute`）。存相对路径，NAS 换了端口不用重下缓存。
 * - **一个相册一个文件**：一本相册的索引不可拆——拆了就半截，没法用。
 * - **先写 .tmp 再 rename**：掉电 / 进程被杀只会留下一个 .tmp，rename 是原子的。
 *
 * ## 放哪
 *
 * 落在 `filesDir` 而不是 `cacheDir`：Android 会在空间紧张时自动清 `cacheDir`，
 * 缓存被清掉等于白做。`filesDir` 要用户手动「清除数据」才会没。
 */
class AlbumCache(context: Context) {

    private val root = File(context.filesDir, DIR)

    /** 一本相册的缓存内容。 */
    data class Entry(
        val albumId: Long,
        val albumName: String,
        /**
         * 建这份缓存时相册列表里报的 `photoCount`。
         *
         * 失效判据只看它，**不**看缓存里实际存了多少条：`/album/photos` 返回的列表口径
         * 和 `photoCount` 可能不同（照片接口把视频也混在列表里），两个数直接比会误判。
         */
        val photoCount: Int,
        val savedAt: Long,
        val photos: List<SharedPhoto>,
    )

    /** 缓存占用情况，给设置页显示。 */
    data class Stats(val bytes: Long, val albums: Int)

    // --- 读 ---

    /**
     * 读一本相册的缓存。文件不存在 / 解析失败 / 版本不认识，一律返回 null（当没有缓存）。
     *
     * 解析失败要删掉那份坏文件：留着的话每次进相册都要试一次，浪费还容易让人以为
     * 「缓存读不出来」是个 bug。
     *
     * [firstBatchSize] / [onFirstBatch] 是给「打开相册」用的：解析到前 N 条就先回调
     * 一次，网格可以立刻画出前几张真实照片，同一线程继续还原剩下的条目。0.24.5 之前
     * 是整本读完才返回，一本 3099 张的清单（1.3MB JSON + 3099 次日期解析）全压在
     * 「按下 OK → 跳转」之间，照片越多点开越慢。不传这两个参数时行为和以前完全一样。
     */
    fun read(
        host: String,
        albumId: Long,
        baseUrl: String,
        firstBatchSize: Int = 0,
        onFirstBatch: ((List<SharedPhoto>) -> Unit)? = null,
    ): Entry? {
        val file = albumFile(host, albumId)
        if (!file.exists()) return null
        val text = try {
            file.readText()
        } catch (e: Exception) {
            file.delete()
            return null
        }
        val entry = parseAlbumEntry(text, albumId, baseUrl, firstBatchSize, onFirstBatch)
        if (entry == null) {
            file.delete()
            return null
        }
        // 读一次就 touch 一下：淘汰时按「最久没打开」排，靠的就是这个时间戳。
        file.setLastModified(System.currentTimeMillis())
        return entry
    }

    /** 读相册列表缓存。NAS 掉线时用它兜底，让相册墙还能显示上次的结果。 */
    fun readAlbums(host: String, baseUrl: String): List<SharedAlbum>? {
        val file = albumListFile(host)
        if (!file.exists()) return null
        return try {
            val obj = JSONObject(file.readText())
            if (obj.optInt(KEY_VERSION) != CACHE_VERSION) {
                file.delete()
                return null
            }
            val arr = obj.optJSONArray(KEY_ALBUMS) ?: JSONArray()
            val list = ArrayList<SharedAlbum>(arr.length())
            for (i in 0 until arr.length()) {
                val a = arr.optJSONObject(i) ?: continue
                list += SharedAlbum(
                    id = a.optLong(KEY_ALBUM_ID),
                    name = a.optString(KEY_NAME),
                    ownerName = a.optString(KEY_OWNER),
                    photoCount = a.optInt(KEY_PHOTO_COUNT),
                    posterUrl = a.optString(KEY_POSTER).ifBlank { null }?.let { joinBase(it, baseUrl) },
                    category = a.optInt(KEY_CATEGORY),
                    birthday = a.optString(KEY_BIRTHDAY).ifBlank { null },
                    displayType = a.optInt(KEY_DISPLAY_TYPE),
                )
            }
            list
        } catch (e: Exception) {
            file.delete()
            null
        }
    }

    // --- 写 ---

    /** 把整本相册的元数据写进缓存。 */
    fun write(host: String, album: SharedAlbum, photos: List<SharedPhoto>, baseUrl: String) {
        val items = JSONArray()
        photos.forEach { photo -> items.put(photoToJson(photo, baseUrl)) }
        val obj = JSONObject()
        obj.put(KEY_VERSION, CACHE_VERSION)
        obj.put(KEY_HOST, host)
        obj.put(KEY_ALBUM_ID, album.id)
        obj.put(KEY_ALBUM_NAME, album.name)
        obj.put(KEY_PHOTO_COUNT, album.photoCount)
        val savedAt = System.currentTimeMillis()
        obj.put(KEY_SAVED_AT, savedAt)
        obj.put(KEY_ITEMS, items)
        writeAtomically(albumFile(host, album.id), obj.toString())
        // 清单里也记一笔：下次判断「这本要不要重建」时不用去翻那几百 KB 的正文。
        writeManifest(host, album.id, album.photoCount, savedAt)
    }

    /** 写相册列表缓存。 */
    fun writeAlbums(host: String, albums: List<SharedAlbum>, baseUrl: String) {
        val arr = JSONArray()
        albums.forEach { album ->
            val a = JSONObject()
            a.put(KEY_ALBUM_ID, album.id)
            a.put(KEY_NAME, album.name)
            a.put(KEY_OWNER, album.ownerName)
            a.put(KEY_PHOTO_COUNT, album.photoCount)
            a.put(KEY_POSTER, stripBase(album.posterUrl, baseUrl) ?: "")
            a.put(KEY_CATEGORY, album.category)
            a.put(KEY_BIRTHDAY, album.birthday ?: "")
            a.put(KEY_DISPLAY_TYPE, album.displayType)
            arr.put(a)
        }
        val obj = JSONObject()
        obj.put(KEY_VERSION, CACHE_VERSION)
        obj.put(KEY_HOST, host)
        obj.put(KEY_ALBUMS, arr)
        writeAtomically(albumListFile(host), obj.toString())
    }

    // --- 清单（哪几本缓存了、各自记的是多少张） ---

    /**
     * 读「缓存清单」：相册 id → 建缓存时记下的 photoCount 快照。
     *
     * 有了它，判断「哪本该重建」就只是读一个小文件，不用去解析每本相册的完整清单
     * （一本八百张的缓存就是几百 KB，逐本解析在启动路径上太慢）。
     */
    fun readManifest(host: String): Map<Long, Int> {
        val file = manifestFile(host)
        if (!file.exists()) return emptyMap()
        return try {
            val obj = JSONObject(file.readText())
            if (obj.optInt(KEY_VERSION) != CACHE_VERSION) return emptyMap()
            val albums = obj.optJSONObject(KEY_ALBUMS) ?: return emptyMap()
            val out = HashMap<Long, Int>(albums.length())
            albums.keys().forEach { key ->
                val id = key.toLongOrNull() ?: return@forEach
                out[id] = albums.optJSONObject(key)?.optInt(KEY_PHOTO_COUNT) ?: 0
            }
            out
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /** 记一笔：某本相册缓存的是多少张。 */
    fun writeManifest(host: String, albumId: Long, photoCount: Int, savedAt: Long) {
        val file = manifestFile(host)
        val albums = try {
            val obj = JSONObject(file.readText())
            obj.optJSONObject(KEY_ALBUMS) ?: JSONObject()
        } catch (e: Exception) {
            JSONObject()
        }
        val entry = JSONObject()
        entry.put(KEY_PHOTO_COUNT, photoCount)
        entry.put(KEY_SAVED_AT, savedAt)
        albums.put(albumId.toString(), entry)
        val obj = JSONObject()
        obj.put(KEY_VERSION, CACHE_VERSION)
        obj.put(KEY_HOST, host)
        obj.put(KEY_ALBUMS, albums)
        writeAtomically(file, obj.toString())
    }

    // --- 清 / 统计 / 淘汰 ---

    /** 全删。退出登录时调——缓存里有拍摄地点和文件名，是隐私。 */
    fun clearAll() {
        root.listFiles()?.forEach { it.delete() }
    }

    /** 当前占用（字节）与缓存了几本相册。 */
    fun stats(): Stats {
        var bytes = 0L
        var albums = 0
        root.listFiles()?.forEach { file ->
            bytes += file.length()
            if (file.name.startsWith(PREFIX_ALBUM)) albums++
        }
        return Stats(bytes, albums)
    }

    /**
     * 超出上限就淘汰最久没打开的那几本。
     *
     * 按文件的修改时间排——每次读缓存都会 touch 一下文件，所以「最久没动」就是
     * 「最久没打开」。淘汰的单位是整本相册：索引拆不得，拆了就半截没法用。
     */
    fun trim(maxBytes: Long) {
        if (maxBytes <= 0) return
        val files = root.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return
        files.sortedBy { it.lastModified() }.forEach { file ->
            if (total <= maxBytes) return
            val size = file.length()
            if (file.delete()) total -= size
        }
    }

    // --- 内部 ---

    private fun albumFile(host: String, albumId: Long) =
        File(root, "$PREFIX_ALBUM${hostKey(host)}_$albumId.json")

    private fun albumListFile(host: String) = File(root, "${PREFIX_LIST}${hostKey(host)}.json")

    private fun manifestFile(host: String) = File(root, "${PREFIX_MANIFEST}${hostKey(host)}.json")

    /** 主机名做哈希当文件名前缀：换 NAS 之后 id 撞车也不会读到别人的缓存。 */
    private fun hostKey(host: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(host.toByteArray())
        return digest.take(6).joinToString("") { "%02x".format(it) }
    }

    /**
     * 原子写：先写 .tmp，成功后 rename。
     *
     * rename 在同一个目录里是原子的——要么旧文件，要么新文件，不会出现「写了一半的 JSON」。
     * 直接覆盖写的话，掉电或进程被杀会留下半截文件，下次读只能当没有缓存，白跑一遍。
     */
    private fun writeAtomically(target: File, content: String) {
        try {
            root.mkdirs()
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeText(content)
            if (!tmp.renameTo(target)) {
                // 某些文件系统上 rename 会因为目标已存在而失败：先删再改名。
                target.delete()
                tmp.renameTo(target)
            }
        } catch (e: Exception) {
            // 缓存写不进去不能影响正事（看图），吞掉即可。
        }
    }
}

// --- 文件顶层的常量与小工具（类外的 JSON 还原函数也要用） ---

private const val DIR = "album_cache"

/**
 * 清单缓存格式版本。**改条目字段就要 +1**：旧文件会被当成「不认识」直接删掉，
 * 下次进相册重新索引一遍，让新字段真正有值。
 *
 * - 1：id / 拍摄时间 / 是否视频 / 三档地址 / 视频地址 / 文件名 / 地点
 * - 2（0.27.0）：增加 `w` / `h` / `dur` / `bytes`（像素宽高、视频时长、真实文件大小）。
 *   不 +1 的话，升级后旧缓存里这四项恒为 0，信息栏的尺寸与时长要等相册自己变了才出现。
 *
 * `internal` 是为了让单测直接引这个值构造样本，不另抄一份数字（抄的数字迟早会对不上）。
 */
internal const val CACHE_VERSION = 2

private const val PREFIX_ALBUM = "a_"
private const val PREFIX_LIST = "l_"
private const val PREFIX_MANIFEST = "m_"

private const val KEY_VERSION = "v"
private const val KEY_HOST = "host"
private const val KEY_ALBUM_ID = "id"
private const val KEY_ALBUM_NAME = "name"
private const val KEY_OWNER = "owner"
private const val KEY_PHOTO_COUNT = "count"
private const val KEY_SAVED_AT = "savedAt"
private const val KEY_ITEMS = "items"
private const val KEY_ALBUMS = "albums"
private const val KEY_POSTER = "poster"
private const val KEY_CATEGORY = "category"
private const val KEY_BIRTHDAY = "birthday"
private const val KEY_DISPLAY_TYPE = "displayType"

/** 照片条目里的字段（和相册级的字段不在一个作用域里，但名字要分得开免得看晕）。 */
private const val KEY_ID = "pid"
private const val KEY_TAKEN_AT = "taken"
private const val KEY_IS_VIDEO = "video"
private const val KEY_URLS = "urls"
private const val KEY_VIDEO_URL = "vurl"
private const val KEY_NAME = "fname"
private const val KEY_LOCATION = "loc"

/** 0.27.0 起条目里多存的技术字段（宽、高、时长、字节数）。缺省一律 0 = 未知。 */
private const val KEY_WIDTH = "w"
private const val KEY_HEIGHT = "h"
private const val KEY_DURATION = "dur"
private const val KEY_BYTES = "bytes"

private val TAKEN_FORMAT: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

/**
 * 把一份清单缓存文本还原成 [AlbumCache.Entry]（0.24.5 从 `read()` 抽出来的纯函数）。
 *
 * 抽出来两个原因：① 不依赖 Context，JVM 单测能直接喂字符串，不用上设备钉住时序；
 * ② [onFirstBatch] 的触发时机能离线回归——打开相册要的就是「前几条先到、剩下的
 * 继续解析」，回调晚一帧，用户就又回到「点完等一下」。
 *
 * 只回调一次，交出去的是**副本**：后面继续 append 不会改动已经交给网格的首批列表。
 * 少于 [firstBatchSize] 条的小相册不回调，由调用方直接用完整结果，省一次状态更新。
 */
internal fun parseAlbumEntry(
    text: String,
    albumId: Long,
    baseUrl: String,
    firstBatchSize: Int = 0,
    onFirstBatch: ((List<SharedPhoto>) -> Unit)? = null,
): AlbumCache.Entry? = try {
    val obj = JSONObject(text)
    if (obj.optInt(KEY_VERSION) != CACHE_VERSION) {
        null
    } else {
        val items = obj.optJSONArray(KEY_ITEMS) ?: JSONArray()
        val photos = ArrayList<SharedPhoto>(items.length())
        var emitted = false
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            photos += item.toPhoto(baseUrl)
            if (!emitted && firstBatchSize > 0 && photos.size >= firstBatchSize) {
                emitted = true
                onFirstBatch?.invoke(ArrayList(photos))
            }
        }
        AlbumCache.Entry(
            albumId = obj.optLong(KEY_ALBUM_ID, albumId),
            albumName = obj.optString(KEY_ALBUM_NAME),
            photoCount = obj.optInt(KEY_PHOTO_COUNT),
            savedAt = obj.optLong(KEY_SAVED_AT),
            photos = photos,
        )
    }
} catch (e: Exception) {
    null
}

/** 绝对地址 → 相对路径（去掉 baseUrl 前缀）。本来就是绝对的（外链）就原样留着。 */
internal fun stripBase(url: String?, baseUrl: String): String? {
    if (url.isNullOrEmpty()) return null
    return if (baseUrl.isNotEmpty() && url.startsWith(baseUrl)) {
        url.removePrefix(baseUrl)
    } else {
        url
    }
}

/** 相对路径 → 绝对地址。绝对的就原样用。 */
internal fun joinBase(path: String?, baseUrl: String): String? {
    if (path.isNullOrEmpty()) return null
    return if (path.startsWith("http://") || path.startsWith("https://")) {
        path
    } else {
        baseUrl + path
    }
}

/**
 * 一条照片 → 缓存里的 JSON（0.27.0 从 `write()` 抽出来的纯函数）。
 *
 * 抽出来是为了能离线验证「写进去 → 读回来」逐字段相等——缓存格式一旦对不上，
 * 表现是信息栏悄悄少一行，不会崩、也不会有人报，只能靠单测钉住。
 */
internal fun photoToJson(photo: SharedPhoto, baseUrl: String): JSONObject {
    val item = JSONObject()
    item.put(KEY_ID, photo.id)
    photo.takenAt?.let { item.put(KEY_TAKEN_AT, TAKEN_FORMAT.format(it)) }
    item.put(KEY_IS_VIDEO, photo.isVideo)
    val urls = JSONArray()
    urls.put(stripBase(photo.thumbUrl, baseUrl) ?: "")
    urls.put(stripBase(photo.fullUrl, baseUrl) ?: "")
    urls.put(stripBase(photo.originalUrl, baseUrl) ?: "")
    item.put(KEY_URLS, urls)
    item.put(KEY_VIDEO_URL, stripBase(photo.videoUrl, baseUrl) ?: "")
    photo.name?.let { item.put(KEY_NAME, it) }
    photo.location?.let { item.put(KEY_LOCATION, it) }
    // 0 不写：老相册（没有这些值的）缓存体积不变，读回来也是 0 = 未知。
    if (photo.width > 0) item.put(KEY_WIDTH, photo.width)
    if (photo.height > 0) item.put(KEY_HEIGHT, photo.height)
    if (photo.durationSeconds > 0) item.put(KEY_DURATION, photo.durationSeconds)
    if (photo.byteSize > 0) item.put(KEY_BYTES, photo.byteSize)
    return item
}

/** 把缓存里的一条 JSON 还原成 [SharedPhoto]。 */
private fun JSONObject.toPhoto(baseUrl: String): SharedPhoto {
    val urls = optJSONArray(KEY_URLS)
    fun urlAt(index: Int): String? {
        val raw = urls?.optString(index).orEmpty()
        if (raw.isEmpty()) return null
        return joinBase(raw, baseUrl)
    }
    val videoRaw = optString(KEY_VIDEO_URL).ifBlank { null }
    return SharedPhoto(
        id = optLong(KEY_ID),
        thumbUrl = urlAt(0),
        fullUrl = urlAt(1),
        originalUrl = urlAt(2),
        isVideo = optBoolean(KEY_IS_VIDEO),
        videoUrl = videoRaw?.let { joinBase(it, baseUrl) },
        takenAt = optString(KEY_TAKEN_AT).ifBlank { null }?.let {
            runCatching { LocalDateTime.parse(it, TAKEN_FORMAT) }.getOrNull()
        },
        location = optString(KEY_LOCATION).ifBlank { null },
        name = optString(KEY_NAME).ifBlank { null },
        // 老缓存（v1）里没有这四项，读回来是 0 = 未知，信息栏自动少显示一行。
        width = optInt(KEY_WIDTH, 0),
        height = optInt(KEY_HEIGHT, 0),
        durationSeconds = optInt(KEY_DURATION, 0),
        byteSize = optLong(KEY_BYTES, 0),
    )
}
