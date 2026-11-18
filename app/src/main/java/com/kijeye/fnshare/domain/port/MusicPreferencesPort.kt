package com.kijeye.fnshare.domain.port

import com.kijeye.fnshare.domain.model.FnMusicAccount
import com.kijeye.fnshare.domain.model.MusicSourceKind

/**
 * 该代码是从哪个原文件提取的：
 * 【新增契约，无函数体被搬动】。刻意**不往 `SettingsSource` 里加属性**：那样要同步改
 * `PrefsSettingsSource` 的 20 多个转发属性，还要动 `AppViewModel` 构造 `AppState` 的
 * 那一段（`:160-181`），属于为了加功能去改写既有链路。另开一个小接口，双源相关状态
 * 只在这一处进出，本地音乐与相册设置一行都不用动。
 *
 * 对应的生命周期清理应该在原文件的哪个位置调用：
 * 【无需清理】。实现只持有 `FnSettings`（SharedPreferences），无 fd、无监听器、无线程。
 * `clearFnAccount()` 是用户主动「退出飞牛音乐」的业务动作，不是生命周期清理；
 * 相册的 `SettingsSource.forget()` 也**不会**调用它。
 *
 * 线程约定：沿用项目既有事实——设置项是同步属性，在主线程读写；内部只有一次
 * SharedPreferences 访问，不做 IO。真正的网络调用在 `FnMusicSession`（切 Dispatchers.IO）。
 */
interface MusicPreferencesPort {

    /** 当前曲库来源，持久化成枚举名。读不到 / 老值非法时退回 LOCAL。 */
    var sourceKind: MusicSourceKind

    /** 已保存的音乐子账户；未登录时 null。token 不在这里（只在内存）。 */
    val fnAccount: FnMusicAccount?

    val hasFnLogin: Boolean get() = fnAccount != null

    /** 验证成功后才调用：把表单连同服务端显示名一起落盘（密码与访问码加密）。 */
    fun saveFnAccount(
        baseUrl: String,
        user: String,
        password: String,
        accessCode: String,
        deviceId: String,
        userName: String,
    )

    /** 退出飞牛音乐：清掉全部音乐凭据，但不碰相册账号与任何显示设置。 */
    fun clearFnAccount()

    /** 当前停留的歌单 id（切账号 / 重登后要校验它还在不在新列表里）。 */
    var lastPlaylistId: String?
}
