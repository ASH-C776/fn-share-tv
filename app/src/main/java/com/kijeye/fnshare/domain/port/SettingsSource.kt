package com.kijeye.fnshare.domain.port

/**
 * 该代码是从哪个原文件提取的：
 * 【新增契约，无函数体被搬动】。属性与方法的名字、类型、可变性逐字镜像
 * `data/FnSettings.kt:19-201,205,232,242`。所有 getter/setter 的实际实现（含
 * AndroidKeyStore AES 加密、旧版明文一次性迁移、`SLIDE_STEPS` 档位归一、
 * `musicSelection` 一行一个 id 的编解码）**全部留在 `FnSettings` 里**，
 * 第 3 批的 `PrefsSettingsSource` 只是逐属性转发，不复制、不改写。
 *
 * 刻意保持**同步属性**而不是 `suspend fun`：现有 `AppViewModel` 在组合期和主线程里
 * 直接读写 `settings.xxx`（例如 `:160-181` 一次性读 20 个属性构造 AppState）。改成
 * suspend 会逼着改十几处调用点，属于「改写业务逻辑」，本重构禁止。
 * 因此本接口是唯一一处「SharedPreferences 在主线程读写」的既有事实，
 * 保持原样；未来若要迁到 DataStore，另开一轮重构，不在本次范围内。
 *
 * 对应的生命周期清理应该在原文件的哪个位置调用：
 * 【无需清理】。`FnSettings` 只持有 `SharedPreferences`，无 fd、无监听器、无线程。
 * `AppViewModel.onCleared():1198` 与 `MainActivity.onDestroy()` 都不需要为它新增调用。
 * `forget()` 是业务动作（退出登录清密码/访问码），不是生命周期清理。
 */
interface SettingsSource {

    var host: String
    var userName: String
    var password: String
    var accessCode: String
    var deviceId: String
    val hasSavedLogin: Boolean

    var slideSeconds: Int
    var slideshowShuffled: Boolean
    var previewOriginal: Boolean
    var slideshowFade: Boolean
    var slideshowZoom: Boolean
    var slideshowFill: Boolean

    var musicSelection: Set<String>
    var musicShuffled: Boolean
    var lyricsEnabled: Boolean

    var loopSlideshow: Boolean
    var lastAlbumId: Long
    var idleMinutes: Int
    var backdropWall: Boolean
    var backdropGrid: Boolean
    var focusZoom: Boolean
    var focusRingColorHex: String

    /** 退出登录：只清密码与访问码，显示设置不动。 */
    fun forget()

    /** 自签证书 pin：host → SHA-256 指纹。 */
    fun loadPins(): Map<String, String>

    fun savePin(host: String, fingerprint: String?)
}
