package com.kijeye.fnshare.data.music.fn

/**
 * 该代码是从哪个原文件提取的：
 * 【新增 DTO，无函数体被搬动】。字段名逐字对照 2026-11-05 在
 * `http://192.168.1.100:5666/music/api/v1` 的实测响应（见 `docs/FN_MUSIC_API.md`
 * 「实测校正」一节）。原文档写的 `data.userId` / `data.username`、歌词的
 * `id` / `format` **都不存在**，不照抄。
 *
 * 对应的生命周期清理应该在原文件的哪个位置调用：
 * 【无需清理】。全部是不可变值。[FnLoginForm] 的明文密码只在内存里活到
 * `FnMusicSession.verify()` 返回，不落盘、不写日志、不进异常消息。
 * 真正需要 release 的是播放器和歌词轮询，位置见 `ui/AppViewModel.onCleared()`。
 */

/** 登录表单。验证通过后才由 `FnMusicSession` 落盘（密码与访问码走 FnCipher 加密）。 */
data class FnLoginForm(
    val baseUrl: String,
    val user: String,
    /** 明文。哈希只在 `FnMusicApi.login()` 发出请求前一刻做。 */
    val password: String,
    /** 明文；留空表示 NAS 没开访问码闸门。 */
    val accessCode: String,
    val deviceId: String,
)

/** 登录成功。`userName` 取 `data.user.name`，用于设置页显示「已登录：tv」。 */
data class FnLoginResult(val token: String, val userName: String)

/** 歌单。列表接口只给 guid 与 name，没有 trackCount。 */
data class FnPlaylist(val guid: String, val name: String)

/** 歌单内的一首歌。`isCue = true` 是 CUE 片段，不能播放。 */
data class FnTrack(
    val guid: String,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val isCue: Boolean,
)

/** 一条歌词。`preferred` 指向的 guid 由 API 层挑选后再填进这里。 */
data class FnLyric(
    val guid: String,
    val content: String,
    val isLrc: Boolean,
    val offsetMs: Long,
)

/** 飞牛音乐的所有可预期失败都归到这一类，UI 按子类映射成一句人话。 */
open class FnMusicException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 响应不是 JSON。访问码闸门回的是 HTML 页面，而且**状态码可能是 200 甚至 206**
 * （播放流那次实测就是 206 + text/html），只判状态码必然漏。
 */
class FnMusicAccessCodeRequiredException :
    FnMusicException("需要访问码，或访问码不正确")

/**
 * 登录接口的 120001 / 120002。注意登录接口账号或密码错**也是 120001**，
 * 与业务接口的「token 过期」同码，必须按调用场景区分，不能一律当成重登信号。
 */
class FnMusicBadCredentialException(message: String = "账号或密码错误") :
    FnMusicException(message)

/** 业务接口的 99999 / 120001 / HTTP 401：token 失效，允许重登一次再重试。 */
class FnMusicUnauthorizedException(message: String = "登录已过期") :
    FnMusicException(message)

/** 连不上、超时、非 2xx。 */
class FnMusicNetworkException(message: String, cause: Throwable? = null) :
    FnMusicException(message, cause)

/** JSON 结构不对（缺 data / list 等）。 */
class FnMusicProtocolException(message: String) : FnMusicException(message)
