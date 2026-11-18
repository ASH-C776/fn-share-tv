package com.kijeye.fnshare.data.music.fn

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * 该代码是从哪个原文件提取的：
 * 【新增实现，无函数体被搬动】。端点与字段全部来自 2026-11-05 对
 * `http://192.168.1.100:5666/music/api/v1` 的实测（见 `docs/FN_MUSIC_API.md`
 * 「实测校正」），不引用任何其他实现。
 *
 * 三个 provider（地址 / token / 访问码）而不是构造期固定值，是为了让设置页改地址、
 * 改访问码、会话重登换 token 之后**立刻**对所有请求生效，不用重建 API 对象或播放器。
 *
 * 对应的生命周期清理应该在原文件的哪个位置调用：
 * 【无需清理】。无缓存、无轮询、无协程作用域；每次调用都是一次独立 HTTP 往返，
 * 响应体在 `FnMusicTransport` 里 `use` 关闭。播放器与歌词轮询的清理仍在
 * `ui/AppViewModel.onCleared()`；本类不进那里。
 *
 * 线程约定：`suspend` 方法内部由 transport 切到 `AppDispatchers.io`；返回值由调用方
 * （`FnMusicCatalog` / `FnMusicSession`）交给 ViewModel 在主线程写 StateFlow。
 *
 * ⚠️ 三个必须保留的判断（都是实测踩出来的，删了就会出现「界面一片空白但没报错」）：
 *  1. 访问码闸门回的是 **HTML**，状态码可能是 200 / 206 / 403，只判状态码必然漏；
 *  2. 登录接口账号密码错也是 **120001**，与业务接口的「token 过期」同码，按场景分流；
 *  3. 曲目接口的 `data.total` 才是总数，`size=50` 只是分页大小，不读 total 会静默截断。
 */
class FnMusicApi(
    private val transport: FnMusicTransport,
    private val baseUrlProvider: () -> String,
    private val tokenProvider: () -> String?,
    private val accessCodeProvider: () -> String,
) {

    private enum class Scene { LOGIN, BUSINESS }

    companion object {
        private const val PAGE_SIZE = 50
        /** 防止异常 total 把 NAS 和电视一起拖死；2000 首已远超家庭歌单。 */
        private const val MAX_TRACKS = 2_000
        const val DEFAULT_PATH_SUFFIX = "/music/api/v1"
    }

    /**
     * 登录。表单里的 baseUrl / 访问码**优先于 provider**——验证时凭据还没落盘，
     * 不能拿旧值去试新账号。密码在此处现算 SHA256 hex，明文不出本函数。
     */
    suspend fun login(form: FnLoginForm): FnLoginResult {
        val body = JSONObject()
            .put("username", form.user)
            .put("password", fnMusicSha256Hex(form.password))
            .put("deviceId", form.deviceId)
            .toString()
        val request = FnMusicRequest(
            method = "POST",
            url = url(form.baseUrl, "user/password-login"),
            headers = mapOf(
                "Content-Type" to "application/json; charset=utf-8",
                "x-access-code" to fnMusicBase64(form.accessCode),
                "x-access-source" to "web",
            ),
            body = body,
        )
        val data = dataOf(parseEnvelope(transport.execute(request), Scene.LOGIN))
        val token = data.optString("userToken")
        if (token.isBlank()) throw FnMusicProtocolException("登录响应缺 userToken")
        val name = data.optJSONObject("user")?.optString("name").orEmpty().ifBlank { form.user }
        return FnLoginResult(token, name)
    }

    /** 歌单列表。不显示曲目数：这个接口没有 trackCount，硬拉一遍要 N 次请求。 */
    suspend fun playlists(): List<FnPlaylist> {
        val data = dataOf(parseEnvelope(transport.execute(authedGet("playlist/list")), Scene.BUSINESS))
        val list = data.optJSONArray("list") ?: return emptyList()
        return (0 until list.length()).mapNotNull { i ->
            val item = list.optJSONObject(i) ?: return@mapNotNull null
            val guid = item.optString("guid")
            if (guid.isBlank()) return@mapNotNull null
            FnPlaylist(guid = guid, name = item.optString("name").ifBlank { "未命名歌单" })
        }
    }

    /** 某个歌单的全部曲目，按 `data.total` 翻页拉全。 */
    suspend fun playlistTracks(playlistGuid: String): List<FnTrack> {
        val out = ArrayList<FnTrack>()
        var page = 1
        var total = Int.MAX_VALUE
        val guidParam = enc(playlistGuid)
        while (out.size < total && out.size < MAX_TRACKS) {
            val path = "track/playlist-detail/list" +
                "?playlistGUID=$guidParam&page=$page&size=$PAGE_SIZE&sort=trackAddedAt,desc"
            val data = dataOf(parseEnvelope(transport.execute(authedGet(path)), Scene.BUSINESS))
            total = data.optInt("total", 0).coerceAtLeast(0)
            val list = data.optJSONArray("list") ?: JSONArray()
            for (i in 0 until list.length()) {
                if (out.size >= MAX_TRACKS) break
                val item = list.optJSONObject(i) ?: continue
                val guid = item.optString("guid")
                if (guid.isBlank()) continue
                val artist = item.optJSONArray("artists")
                    ?.optJSONObject(0)
                    ?.optString("name")
                    .orEmpty()
                out += FnTrack(
                    guid = guid,
                    title = item.optString("title").ifBlank { "未知曲目" },
                    artist = artist,
                    durationMs = item.optLong("duration", 0L),
                    isCue = item.optBoolean("isCue", false),
                )
            }
            // 空页 / 不足一页都说明到头了；也防服务端忽略 size 时的死循环。
            if (list.length() < PAGE_SIZE) break
            page++
        }
        return out
    }

    /**
     * 歌词。优先 `data.preferred` 指向的那条 guid；没有就退第一条 isLRC 的；
     * 再没有就退第一条非空内容（`format: "lrc"` 的老响应也兼容）。
     */
    suspend fun lyrics(trackGuid: String): FnLyric? {
        val data = dataOf(
            parseEnvelope(
                transport.execute(authedGet("lyric/list?trackGUID=${enc(trackGuid)}")),
                Scene.BUSINESS,
            ),
        )
        val list = data.optJSONArray("list") ?: return null
        val items = (0 until list.length()).mapNotNull { i ->
            val item = list.optJSONObject(i) ?: return@mapNotNull null
            FnLyric(
                guid = item.optString("guid"),
                content = item.optString("content"),
                isLrc = item.optBoolean("isLRC", false),
                offsetMs = item.optLong("offset", 0L),
            )
        }
        val preferred = data.optString("preferred")
        return items.firstOrNull { it.guid == preferred && it.content.isNotBlank() }
            ?: items.firstOrNull { it.isLrc && it.content.isNotBlank() }
            ?: items.firstOrNull { it.content.isNotBlank() }
    }

    /** 播放流地址。ExoPlayer 的 OkHttpDataSource 直接拿它，header 见 [authHeaders]。 */
    fun streamUrl(trackGuid: String): String =
        url(baseUrlProvider(), "track/stream?guid=${enc(trackGuid)}")

    /** 播放流与所有业务接口共用的三个 header；每次调用都取最新 token / 访问码。 */
    fun authHeaders(): Map<String, String> = mapOf(
        "Authorization" to tokenProvider().orEmpty(),
        "x-access-code" to fnMusicBase64(accessCodeProvider()),
        "x-access-source" to "web",
    )

    private fun authedGet(path: String): FnMusicRequest {
        if (tokenProvider().isNullOrBlank()) throw FnMusicUnauthorizedException("尚未登录飞牛音乐")
        return FnMusicRequest("GET", url(baseUrlProvider(), path), authHeaders())
    }

    private fun parseEnvelope(response: FnMusicResponse, scene: Scene): JSONObject {
        if (response.status == 401) throw FnMusicUnauthorizedException()
        val looksJson = response.contentType?.contains("json", ignoreCase = true) == true
        val startsWithBrace = response.body.trimStart().startsWith("{")
        if (!looksJson && !startsWithBrace) throw FnMusicAccessCodeRequiredException()

        val json = try {
            JSONObject(response.body)
        } catch (e: JSONException) {
            // 服务端拿访问码闸门挡下来时给的是整页 HTML，不是错误 JSON。
            throw FnMusicAccessCodeRequiredException()
        }
        if (response.status !in 200..299) {
            throw FnMusicNetworkException("飞牛音乐返回 HTTP ${response.status}")
        }
        val code = json.optInt("code", 0)
        if (code == 0) return json
        val msg = json.optString("msg").orEmpty()
        if (scene == Scene.LOGIN) {
            if (code == 120001 || code == 120002) {
                throw FnMusicBadCredentialException("账号或密码错误（$code）")
            }
            throw FnMusicException("登录失败 $code $msg")
        }
        if (code == 99999 || code == 120001) throw FnMusicUnauthorizedException()
        throw FnMusicException("接口返回 $code $msg")
    }

    private fun dataOf(json: JSONObject): JSONObject =
        json.optJSONObject("data") ?: throw FnMusicProtocolException("响应缺 data")

    private fun url(base: String, path: String): String {
        val clean = base.trim().trimEnd('/')
        if (clean.isEmpty()) throw FnMusicNetworkException("飞牛音乐地址为空")
        return "$clean/$path"
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
}

private const val B64_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

/**
 * 访问码的 Base64。没用 `android.util.Base64`：那个类在 JVM 单测里是返回默认值的桩，
 * 协议层就没法在纯 JVM 上测；也没用 `java.util.Base64`（minSdk 23 要 desugaring）。
 * 访问码是 ASCII 数字，手写 20 行换来「设备与单测逐字节一致」。
 */
fun fnMusicBase64(text: String): String {
    val bytes = text.toByteArray(Charsets.UTF_8)
    val out = StringBuilder()
    var i = 0
    while (i < bytes.size) {
        val b0 = bytes[i].toInt() and 0xff
        val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xff else 0
        val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xff else 0
        val n = (b0 shl 16) or (b1 shl 8) or b2
        out.append(B64_CHARS[(n ushr 18) and 63])
        out.append(B64_CHARS[(n ushr 12) and 63])
        out.append(if (i + 1 < bytes.size) B64_CHARS[(n ushr 6) and 63] else '=')
        out.append(if (i + 2 < bytes.size) B64_CHARS[n and 63] else '=')
        i += 3
    }
    return out.toString()
}

/** 登录密码：SHA256 后再转十六进制小写，服务端只收这个形态。 */
fun fnMusicSha256Hex(text: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
