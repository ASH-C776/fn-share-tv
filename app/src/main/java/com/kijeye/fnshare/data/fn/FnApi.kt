package com.kijeye.fnshare.data.fn

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.util.Calendar
import java.util.Locale

/**
 * 定义已移到 `domain/model/PhotoModels.kt`（结构化重构第 1 批，逐字搬移）。
 * 这里保留 typealias，让同包及所有 `import com.kijeye.fnshare.data.fn.SharedXxx`
 * 的旧调用点零改动。字段名、默认值、ageOn()/ageMonthsOn() 的计算全部未变。
 */
typealias SharedAlbum = com.kijeye.fnshare.domain.model.SharedAlbum
typealias SharedPhoto = com.kijeye.fnshare.domain.model.SharedPhoto
typealias PhotoPage = com.kijeye.fnshare.domain.model.PhotoPage

/**
 * 只读接口。全部是带 `AccessToken` 与 `authx` 签名的 GET。
 *
 * 缩略图和原图那两类 URL 例外——它们只要 token，不要签名，所以图片加载器加一个
 * 统一加头的拦截器就够了。
 */
class FnApi(private val client: OkHttpClient) {

    fun sharedAlbums(session: FnSession): List<SharedAlbum> {
        val data = get(
            session,
            "/p/api/v1/album_grant/list_to_me",
            // count / hasNext 都是 null（没有分页游标），前端也是直接要 1000 条。
            mapOf(
                "offset" to "0",
                "limit" to "1000",
                "sort_by" to "share_mod_time",
                "sort_direction" to "desc",
            ),
        ).optJSONObject("data") ?: return emptyList()

        val list = data.optJSONArray("list") ?: return emptyList()
        return buildList {
            for (index in 0 until list.length()) {
                val item = list.optJSONObject(index) ?: continue
                add(
                    SharedAlbum(
                        id = item.optLong("albumId"),
                        name = item.optString("albumName"),
                        ownerName = item.optString("ownerName"),
                        photoCount = item.optInt("photoCount"),
                        posterUrl = absolute(
                            session,
                            item.optString("posterUrl").ifBlank { item.optString("posterImgUrl") },
                        ),
                        // 宝宝相册：category=1 + birthday(出生日期) + displayType。
                        // album/list 与 album_grant/list_to_me 同结构，共享相册也带这三项；
                        // 普通相册 category=0、birthday 为空。
                        category = item.optInt("category", 0),
                        birthday = item.optString("birthday").ifBlank { null },
                        displayType = item.optInt("displayType", 0),
                    ),
                )
            }
        }
    }

    fun photos(session: FnSession, albumId: Long, offset: Int, limit: Int): PhotoPage {
        val data = get(
            session,
            "/p/api/v1/album/photos",
            mapOf(
                "album_id" to albumId.toString(),
                "sort_by" to "date_time",
                "sort_direction" to "desc",
                "offset" to offset.toString(),
                "limit" to limit.toString(),
            ),
        ).optJSONObject("data") ?: return PhotoPage(emptyList(), false)

        val list = data.optJSONArray("list") ?: return PhotoPage(emptyList(), false)
        val items = buildList {
            for (index in 0 until list.length()) {
                val item = list.optJSONObject(index) ?: continue
                val thumb = item.optJSONObject("additional")?.optJSONObject("thumbnail")
                // 实况照片（isLive=1）按静态图片处理：即使 fnOS 把它归到 video 类，
                // 这里也强制排除，避免网格出现 ▶、全屏去播不存在的实况视频。
                // 它的缩略图/原图地址和普通照片一样走 mUrl/originalUrl，直接当照片渲染即可。
                val isLive = item.optInt("isLive", 0) == 1
                val rawVideoUrl = absolute(session, thumb?.optString("videoUrl"))
                // 没有取流地址的「视频」按照片处理：否则网格挂着 ▶、点开却是照片查看器，
                // 比干脆不标角标更让人困惑（妙妙相册里就有这种条目）。
                val isVideo = !isLive && (
                    item.optString("category") == "video" ||
                        item.optInt("isVideo", 0) == 1
                    ) && rawVideoUrl != null
                add(
                    SharedPhoto(
                        id = item.optLong("id"),
                        // 缩略图和全屏预览各走一条「从小到大」的分级链（字段缺失就落到下一档），
                        // 这是老项目抓包后定下的档位，两个用途别混：
                        //
                        // | `xxsUrl` | 107×60 | ~2 KB |
                        // | `xsUrl`  | 320×180 | ~10 KB |
                        // | `sUrl`   | 427×240 | ~16 KB |
                        // | `mUrl`   | 1920×1080 | ~204 KB |
                        //
                        // 网格几百个格子，用 sUrl 一档就够了；全屏只要 mUrl（正好 1080p），
                        // 别用 originalUrl——那是原图，一张十几 MB，电视上转圈就是这么来的。
                        // 只盯 mUrl 一档还有另一个毛病：缩略图没生成出来的照片（mUrl 为空）
                        // 会整格空白，落到下一档才有图。
                        thumbUrl = tier(session, thumb, GRID_TIERS),
                        fullUrl = tier(session, thumb, PREVIEW_TIERS),
                        originalUrl = tier(session, thumb, ORIGINAL_TIERS),
                        isVideo = isVideo,
                        videoUrl = if (isVideo) rawVideoUrl else null,
                        takenAt = parseTakenAt(item.optString("dateTime")),
                        location = formatGeo(item.optString("geo")),
                        // 原文件名：优先 `name`，个别接口用 `filename`；都为空就留空（信息栏只显示大小）。
                        name = item.optString("name").ifBlank { item.optString("filename") }.ifBlank { null },
                        // 0.27.0：这三个字段服务端一直都给，之前没解析。两本大相册全量 5734 条
                        // 实测：width/height/fileSize 100% 齐全，mediaDuration 视频 799/800 有值，
                        // rotation 恒 0（竖版直接看高 > 宽，不用再转）。缺值时留 0，信息栏自动少一行。
                        width = item.optInt("width", 0),
                        height = item.optInt("height", 0),
                        durationSeconds = if (isVideo) item.optInt("mediaDuration", 0) else 0,
                        byteSize = item.optLong("fileSize", 0),
                    ),
                )
            }
        }
        // hasNext 有时是 null，就退回「这一页装满了就还有下一页」。
        val hasMore = if (data.has("hasNext") && !data.isNull("hasNext")) {
            data.optBoolean("hasNext")
        } else {
            items.size >= limit
        }
        return PhotoPage(items, hasMore)
    }

    private fun get(session: FnSession, path: String, params: Map<String, String>): JSONObject {
        val url = session.address.baseUrl.toHttpUrlOrNull()
            ?.newBuilder()
            ?.encodedPath(path)
            ?.apply { params.forEach { (key, value) -> addQueryParameter(key, value) } }
            ?.build()
            ?: throw FnException("地址无效")

        val request = Request.Builder()
            .url(url)
            .header("AccessToken", session.token)
            .header("authx", FnSign.authx(path, params))
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                // 401 基本就是 token 过期——交给仓库层静默重登一次，而不是直接报错踢回登录页。
                if (response.code == 401) throw FnSessionExpiredException("HTTP 401")
                throw FnException("请求失败 ${response.code}")
            }
            val json = JSONObject(body)
            val code = if (json.has("code")) json.optInt("code") else json.optInt("errno", -1)
            if (code != 0) {
                // 这几个业务码都表示会话失效：用同一个异常让仓库层重登重试。
                if (code == 5000 || code == 5001 || code == 131072) {
                    throw FnSessionExpiredException("code=$code")
                }
                throw FnException("接口返回 $code ${json.optString("msg")}")
            }
            return json
        }
    }

    /** 服务器给的是 `/p/api/v1/...` 这种相对地址，补上主机与端口。 */
    private fun absolute(session: FnSession, path: String?): String? {
        if (path.isNullOrEmpty()) return null
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        return session.address.baseUrl + path
    }

    /** 按档位顺序取第一个非空的缩略图地址，补全成绝对 URL。全空就返回 null。 */
    private fun tier(session: FnSession, thumb: JSONObject?, tiers: List<String>): String? {
        if (thumb == null) return null
        for (key in tiers) {
            val url = absolute(session, thumb.optString(key).ifBlank { null })
            if (url != null) return url
        }
        return null
    }

    /**
     * 把服务端反查好的 `geo` 去掉国家，剩下的原样带上。
     *
     * 原始格式六段：`中国,广东省,佛山市,南海区,北环路,大沥镇`
     * ＝ 国家 / 省 / 市 / 区 / 道路 / 末端地名，收成「广东省 佛山市 南海区 北环路 大沥镇」。
     * 只砍国家那一截——「谁都知道自己在哪个国家」，但路名和末端那一段（镇 / 街道 / 园区名）
     * 是分辨「这次到底在哪」的关键，砍掉就只剩下一个市，信息量不够。
     *
     * 判第一段是不是国家看后缀：省 / 市 / 自治区 / 特别行政区开头的那是行政区划，别误删
     * （海外照片有时候不带国家前缀）。没 GPS 的照片这里是空串 → null，界面整行不显示。
     */
    private fun formatGeo(raw: String): String? {
        if (raw.isBlank()) return null
        val parts = raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        val isAdminDivision = listOf("省", "市", "自治区", "特别行政区").any { parts[0].endsWith(it) }
        val body = if (parts.size > 1 && !isAdminDivision) parts.drop(1) else parts
        return body.joinToString(" ").ifBlank { null }
    }

    /**
     * 把 `dateTime` 解析成 LocalDateTime。fnOS 给的是 EXIF 风格「2026:10:28 18:25:41」，
     * 日期部分用冒号分隔，标准解析器不认，手动拆。这是照片「拍摄地本地时间」，
     * 按原样显示即可，不要按电视所在时区再换算一次（那样会把别人的旅行照片时间挪到本地）。
     */
    private fun parseTakenAt(raw: String): LocalDateTime? {
        if (raw.isBlank()) return null
        val parts = raw.split(" ")
        if (parts.size != 2) return null
        val ymd = parts[0].split(":")
        val hms = parts[1].split(":")
        if (ymd.size != 3 || hms.size < 2) return null
        return runCatching {
            LocalDateTime.of(
                ymd[0].toInt(),
                ymd[1].toInt(),
                ymd[2].toInt(),
                hms[0].toInt(),
                hms[1].toInt(),
                if (hms.size >= 3) hms[2].toInt() else 0,
            )
        }.getOrNull()
    }

    private companion object {
        /** 网格缩略图的档位：从最小开始，缺了往下降级。要回 1920 档把 mUrl 挪到 sUrl 前面。 */
        val GRID_TIERS = listOf("sUrl", "xsUrl", "xxsUrl", "mUrl", "originalUrl")

        /** 全屏预览的档位：1080p 的 mUrl 起步，足够填满电视，又不用等原图。 */
        val PREVIEW_TIERS = listOf("mUrl", "sUrl", "originalUrl")

        /** 「原图」开关打开时才用的档位：真原图起步，缺了往预览档落。 */
        val ORIGINAL_TIERS = listOf("originalUrl", "mUrl", "sUrl")
    }
}
