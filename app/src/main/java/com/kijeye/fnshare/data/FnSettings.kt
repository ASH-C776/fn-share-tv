package com.kijeye.fnshare.data

import android.content.Context
import com.kijeye.fnshare.data.fn.FnCipher
import org.json.JSONObject

/**
 * 登录信息只存这一份。
 *
 * 密码和访问码用 AndroidKeyStore 里的 AES 密钥加密后再落盘——这跟电视上「开机自动进去看照片」
 * 是同一个取舍：开机要能无感登录，所以凭据得留在本地；但留在本地不意味着明文躺在
 * SharedPreferences 里。明文只在内存出现一次，丢设备也读不到 NAS 密码。真正的边界仍在 NAS 那侧
 * （专用账号 + 只读共享 + 访问码），这层只是别让手机/电视丢了雪上加霜。
 */
class FnSettings(context: Context) {

    private val prefs = context.getSharedPreferences("fn_share", Context.MODE_PRIVATE)

    var host: String
        get() = prefs.getString(KEY_HOST, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_HOST, value).apply()

    var userName: String
        get() = prefs.getString(KEY_USER, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_USER, value).apply()

    /** 密文落盘。读的时候若发现还是旧版明文，会就地加密回去（一次性迁移，见 [secretGet]）。 */
    var password: String
        get() = secretGet(KEY_PASS)
        set(value) = secretSet(KEY_PASS, value)

    var accessCode: String
        get() = secretGet(KEY_CODE)
        set(value) = secretSet(KEY_CODE, value)

    var deviceId: String
        get() = prefs.getString(KEY_DEVICE, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_DEVICE, value).apply()

    val hasSavedLogin: Boolean
        get() = host.isNotEmpty() && userName.isNotEmpty() && password.isNotEmpty()

    /**
     * 读一个加密字段：先当密文解密；解密失败（旧版明文 / 密钥随系统重置丢了）就当它是明文，
     * 并顺手加密存回去，完成一次性迁移。空值直接返回空。
     *
     * 升级前的版本把密码存成明文，第一次读会走「解密失败 → 当明文 → 加密回去」分支，
     * 电视上不会因此要求重新登录；之后每次都走密文分支。
     */
    private fun secretGet(key: String): String {
        val raw = prefs.getString(key, "").orEmpty()
        if (raw.isEmpty()) return ""
        val decrypted = FnCipher.decrypt(raw)
        if (decrypted.isEmpty() && raw.isNotEmpty()) {
            // 旧版明文：加密回去，下次就走密文分支。密钥丢失：原样返回，上层会重新登录。
            prefs.edit().putString(key, FnCipher.encrypt(raw)).apply()
            return raw
        }
        return decrypted
    }

    private fun secretSet(key: String, value: String) =
        prefs.edit().putString(key, FnCipher.encrypt(value)).apply()

    /**
     * 幻灯片每张停留几秒。档位 3/5/8（[SLIDE_STEPS]），默认 5 秒。
     * 老存档里可能有 6/10/15 这类旧档位——读的时候就地归到 5，否则界面会显示一个
     * 「6秒」而按左右键跳去 3 或 8，看着像档位表坏了。
     */
    var slideSeconds: Int
        get() = prefs.getInt(KEY_SLIDE, 5).let { if (it in SLIDE_STEPS) it else 5 }
        set(value) = prefs.edit().putInt(KEY_SLIDE, value).apply()

    /** 幻灯片顺序。开 = 随机（每次翻页随机跳一张，不重复停留），关 = 按相册顺序。 */
    var slideshowShuffled: Boolean
        get() = prefs.getBoolean(KEY_SHUFFLE, false)
        set(value) = prefs.edit().putBoolean(KEY_SHUFFLE, value).apply()

    /** 全屏照片直接拉原图。开 = 清晰但慢，关 = 预览档（1080p）快。全局开关，跨相册生效。 */
    var previewOriginal: Boolean
        get() = prefs.getBoolean(KEY_ORIGINAL, false)
        set(value) = prefs.edit().putBoolean(KEY_ORIGINAL, value).apply()

    /**
     * 幻灯片换图时是否叠化。开 = 上一张原地淡出（0.8~1 秒），关 = 硬切。
     *
     * 存布尔而不是原来的字符串：过渡方案目前只有「叠化 / 关闭」两种，字符串那套
     * 是为以后可能的推、擦除留的，还没定型就先不留坑。
     */
    var slideshowFade: Boolean
        get() = prefs.getBoolean(KEY_FADE, true)
        set(value) = prefs.edit().putBoolean(KEY_FADE, value).apply()

    /** 幻灯片是否跑 Ken Burns 放大。关掉就是静止画面，最省解码和绘制开销。 */
    var slideshowZoom: Boolean
        get() = prefs.getBoolean(KEY_SLIDE_ZOOM, true)
        set(value) = prefs.edit().putBoolean(KEY_SLIDE_ZOOM, value).apply()

    /**
     * 幻灯片取景比例：开 = 满屏（Crop 铺满，边缘裁切），关 = 原比例（Fit 完整显示）。
     *
     * 默认满屏：两层都铺满屏幕时叠化才没有「独占区域」，任何宽高比之间转场都无缝
     * （见 PhotoStage 的说明）。关掉就是妥协版——完整显示，但竖图与横图之间转场
     * 会在对方的留白区一帧硬切。只在幻灯片里生效，手动看图恒为原比例。
     */
    var slideshowFill: Boolean
        get() = prefs.getBoolean(KEY_FILL, true)
        set(value) = prefs.edit().putBoolean(KEY_FILL, value).apply()

    /**
     * 幻灯片背景音乐选择：被选中的曲目 id 集合（本地文件是 `file:` 加路径）。
     *
     * 落盘成一行一个 id 的文本（见 [encodeMusicSelection]），因为值都是 App 自己写的——
     * ASCII 的 `file:` 前缀加可能含中文的路径——既不需要 JSON 转义，换行也不可能出现在
     * 值里，写出来在问题报告里仍然可读。播放顺序不由这里决定：界面按曲库列表顺序过滤出
     * 被选中的那些，列表顺序即播放顺序。
     */
    var musicSelection: Set<String>
        get() = decodeMusicSelection(prefs.getString(KEY_MUSIC, ""))
        set(value) = prefs.edit().putString(KEY_MUSIC, encodeMusicSelection(value)).apply()

    /**
     * 背景乐播放顺序。开 = 每次开播打乱一遍（随机），关 = 按曲库列表顺序循环。
     *
     * 和图片的 [slideshowShuffled] 分开：图片随机是「每张随机跳」，音乐随机是
     * 「把整张播放列表洗一次」，两者语义不同，各自有各自的开关键。
     */
    var musicShuffled: Boolean
        get() = prefs.getBoolean(KEY_MUSIC_SHUFFLE, false)
        set(value) = prefs.edit().putBoolean(KEY_MUSIC_SHUFFLE, value).apply()

    /**
     * 幻灯片背景乐是否显示 LRC 歌词。开 = 有同名 `.lrc` 的曲目在右侧显示两列竖排歌词，
     * 关 = 任何情况都不显示。默认开——多数人放歌时希望看到词，没有歌词文件的歌会安静隐藏。
     */
    var lyricsEnabled: Boolean
        get() = prefs.getBoolean(KEY_LYRICS, true)
        set(value) = prefs.edit().putBoolean(KEY_LYRICS, value).apply()

    /** 幻灯片或手动翻到末张后回到第一张。相框场景一直挂着时会用上。 */
    var loopSlideshow: Boolean
        get() = prefs.getBoolean(KEY_LOOP, false)
        set(value) = prefs.edit().putBoolean(KEY_LOOP, value).apply()

    /** 上次看的是哪个相册。自动播放优先放它，省得每次都从第一个开始。 */
    var lastAlbumId: Long
        get() = prefs.getLong(KEY_LAST_ALBUM, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_ALBUM, value).apply()

    /**
     * 闲置保护：无操作多少分钟后自动退回相册墙。0 = 关。
     * 客厅电视上没人看时，不该让某一家的照片一直挂在屏幕上。
     */
    var idleMinutes: Int
        get() = prefs.getInt(KEY_IDLE, 10)
        set(value) = prefs.edit().putInt(KEY_IDLE, value).apply()

    /**
     * 焦点跟随的动态毛玻璃背景，相册墙与照片页分开开关。
     * 默认都开——这是这个应用最出效果的一处；机器弱、嫌晃眼的可以单独关掉一边。
     */
    var backdropWall: Boolean
        get() = prefs.getBoolean(KEY_BACKDROP_WALL, true)
        set(value) = prefs.edit().putBoolean(KEY_BACKDROP_WALL, value).apply()

    var backdropGrid: Boolean
        get() = prefs.getBoolean(KEY_BACKDROP_GRID, true)
        set(value) = prefs.edit().putBoolean(KEY_BACKDROP_GRID, value).apply()

    /**
     * 卡片获得焦点时，里面的图是否再放大一档（1.12 倍，被卡片圆角裁掉溢出部分）。
     *
     * 默认开——「图在画框里放大」是这套焦点反馈最出效果的一处。关掉之后只剩外框
     * 1.05 和一圈白边：老人嫌画面晃、或者照片本身是合影不想被裁掉边角时用得着。
     */
    var focusZoom: Boolean
        get() = prefs.getBoolean(KEY_FOCUS_ZOOM, true)
        set(value) = prefs.edit().putBoolean(KEY_FOCUS_ZOOM, value).apply()

    /**
     * 焦点框描边颜色，存十六进制（带 alpha，`#AARRGGBB`）。
     *
     * 默认纯白（#FFFFFFFF），和之前 `FocusFrame` 用 `MaterialTheme.colorScheme.primary`
     * （近白）的观感一致。相册墙以前写死 `Color.White`、网格走 primary，两处其实不统一，
     * 现在都收口到这一个设置，默认白反而顺手把两边对齐了。
     */
    var focusRingColorHex: String
        get() = prefs.getString(KEY_FOCUS_RING, "#FFFFFFFF").orEmpty().ifEmpty { "#FFFFFFFF" }
        set(value) = prefs.edit().putString(KEY_FOCUS_RING, value).apply()

    // --- 飞牛音乐子账户（与上面的相册账号完全独立） ---
    //
    // 相册登录用的是 NAS 系统账号，飞牛音乐用的是音乐里的子账户，实测两者不是同一个
    // 用户名。共用一组 key 会让「退出相册登录」顺手把音乐踢掉，也会让改相册访问码
    // 连带弄坏音乐播放流。所以这里另起五个 key，forget() 刻意不动它们。

    var fnMusicBaseUrl: String
        get() = prefs.getString(KEY_FN_MUSIC_URL, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_FN_MUSIC_URL, value).apply()

    var fnMusicUser: String
        get() = prefs.getString(KEY_FN_MUSIC_USER, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_FN_MUSIC_USER, value).apply()

    /** 与相册密码同样走 AndroidKeyStore AES，明文不落盘。 */
    var fnMusicPassword: String
        get() = secretGet(KEY_FN_MUSIC_PASS)
        set(value) = secretSet(KEY_FN_MUSIC_PASS, value)

    /** 访问码可为空（NAS 没开闸门时）；同样加密落盘。 */
    var fnMusicAccessCode: String
        get() = secretGet(KEY_FN_MUSIC_CODE)
        set(value) = secretSet(KEY_FN_MUSIC_CODE, value)

    var fnMusicDeviceId: String
        get() = prefs.getString(KEY_FN_MUSIC_DEVICE, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_FN_MUSIC_DEVICE, value).apply()

    /** 服务端 `data.user.name`，只用来在设置页显示，不参与鉴权。 */
    var fnMusicUserName: String
        get() = prefs.getString(KEY_FN_MUSIC_NAME, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_FN_MUSIC_NAME, value).apply()

    /** 双源开关与「上次停留的歌单」也放这组，避免碰 SettingsSource 的既有属性表。 */
    var musicSourceKind: String
        get() = prefs.getString(KEY_MUSIC_SOURCE, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_MUSIC_SOURCE, value).apply()

    var fnMusicLastPlaylistId: String
        get() = prefs.getString(KEY_FN_MUSIC_PLAYLIST, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_FN_MUSIC_PLAYLIST, value).apply()

    /** 只清音乐子账户；相册 forget() 不调用它。 */
    fun forgetFnMusic() {
        prefs.edit()
            .remove(KEY_FN_MUSIC_URL)
            .remove(KEY_FN_MUSIC_USER)
            .remove(KEY_FN_MUSIC_PASS)
            .remove(KEY_FN_MUSIC_CODE)
            .remove(KEY_FN_MUSIC_DEVICE)
            .remove(KEY_FN_MUSIC_NAME)
            .remove(KEY_FN_MUSIC_PLAYLIST)
            .apply()
    }

    fun forget() {
        prefs.edit().remove(KEY_PASS).remove(KEY_CODE).apply()
    }

    /**
     * 把选中列表序列化给 `SharedPreferences`：一行一个 id，保持传入顺序、去重、丢掉空串。
     */
    private fun encodeMusicSelection(ids: Collection<String>): String =
        ids.filter { it.isNotBlank() }.joinToString("\n")

    /** 读回 [encodeMusicSelection] 写下的内容；空行与首尾空白会被忽略。 */
    private fun decodeMusicSelection(raw: String?): Set<String> {
        val kept = LinkedHashSet<String>()
        raw.orEmpty().lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isNotBlank()) kept += trimmed
        }
        return kept
    }

    /**
     * 证书指纹按主机名持久化（TOFU 连续性跨重启）。
     *
     * 之前只放内存，进程一重启就清空，等于「每次启动都重新信任第一次见到的证书」——
     * 攻击者只要挑重启后的首次连接就能 MITM。这里落盘成一份 `host -> 指纹` 的 JSON，
     * 启动时由 [FnRepo] 读回进程内的 ConcurrentHashMap 作快速查表。
     */
    fun loadPins(): Map<String, String> {
        val raw = prefs.getString(KEY_PINS, "").orEmpty()
        if (raw.isEmpty()) return emptyMap()
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
        val map = mutableMapOf<String, String>()
        obj.keys().forEach { map[it] = obj.optString(it) }
        return map
    }

    /** 更新某个主机的指纹：`fingerprint` 为 null 表示「证书变了，清掉旧记录」。 */
    fun savePin(host: String, fingerprint: String?) {
        val current = loadPins().toMutableMap()
        if (fingerprint == null) current.remove(host) else current[host] = fingerprint
        val out = JSONObject()
        current.forEach { (k, v) -> out.put(k, v) }
        prefs.edit().putString(KEY_PINS, out.toString()).apply()
    }

    companion object {
        /** 幻灯片停留档位。设置页和看图里的幻灯片面板共用这一份，两边必须同源。 */
        val SLIDE_STEPS = listOf(3, 5, 8)

        private const val KEY_HOST = "host"
        const val KEY_USER = "user"
        const val KEY_PASS = "pass"
        const val KEY_CODE = "code"
        const val KEY_DEVICE = "device"
        const val KEY_PINS = "cert_pins"
        const val KEY_SLIDE = "slide_seconds"
        const val KEY_LOOP = "loop_slideshow"
        const val KEY_LAST_ALBUM = "last_album"
        const val KEY_IDLE = "idle_minutes"
        const val KEY_BACKDROP_WALL = "backdrop_wall"
        const val KEY_BACKDROP_GRID = "backdrop_grid"
        const val KEY_FOCUS_ZOOM = "focus_zoom"
        const val KEY_FOCUS_RING = "focus_ring_color"
        const val KEY_SHUFFLE = "slideshow_shuffled"
        const val KEY_ORIGINAL = "preview_original"
        const val KEY_FADE = "slideshow_fade"
        const val KEY_SLIDE_ZOOM = "slideshow_zoom"
        const val KEY_FILL = "slideshow_fill"
        const val KEY_MUSIC = "slideshow_music"
        const val KEY_MUSIC_SHUFFLE = "music_shuffled"
        const val KEY_LYRICS = "lyrics_enabled"

        // 飞牛音乐子账户：前缀 fnm_ 与相册账号的 host/user/pass/code 明确分开。
        private const val KEY_FN_MUSIC_URL = "fnm_url"
        private const val KEY_FN_MUSIC_USER = "fnm_user"
        private const val KEY_FN_MUSIC_PASS = "fnm_pass"
        private const val KEY_FN_MUSIC_CODE = "fnm_code"
        private const val KEY_FN_MUSIC_DEVICE = "fnm_device"
        private const val KEY_FN_MUSIC_NAME = "fnm_name"
        private const val KEY_FN_MUSIC_PLAYLIST = "fnm_playlist"
        private const val KEY_MUSIC_SOURCE = "music_source_kind"
    }
}
