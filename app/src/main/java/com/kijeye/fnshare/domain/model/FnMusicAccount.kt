package com.kijeye.fnshare.domain.model

/**
 * 该代码是从哪个原文件提取的：
 * 【新增模型，无函数体被搬动】。飞牛音乐是**音乐里的子账户**，与相册用的 NAS 系统
 * 账号（`FnSettings.userName/password/accessCode`）完全无关：实测相册账号是 `jie`、
 * 音乐账号是 `tv`，歌单按用户隔离。因此凭据必须单独一组 key，退出相册登录也不能
 * 把它们一起清掉。
 *
 * 这里全是**已解密的值**；落盘加密由 `data/FnSettings` 的 `secretSet()` 负责，
 * domain 层不认识 AndroidKeyStore，也不认识 `data.music.fn.FnLoginForm`。
 *
 * 对应的生命周期清理应该在原文件的哪个位置调用：
 * 【无需清理】。不可变值对象，明文只在内存里活到 `FnMusicSession` 用完为止。
 * `AppViewModel.onCleared()` 不为此新增调用。
 */
data class FnMusicAccount(
    val baseUrl: String,
    val user: String,
    val password: String,
    val accessCode: String,
    val deviceId: String,
    /** 服务端返回的 `data.user.name`，设置页显示「已登录：tv」。 */
    val userName: String,
)
