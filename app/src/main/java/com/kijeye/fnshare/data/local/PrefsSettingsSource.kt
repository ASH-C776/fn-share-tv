package com.kijeye.fnshare.data.local

import com.kijeye.fnshare.data.FnSettings
import com.kijeye.fnshare.domain.port.SettingsSource

/**
 * 该代码是从哪个原文件提取的：
 * 【新增适配器，原文件零改动】。逐个属性/方法转发到 `data/FnSettings.kt`，
 * 后者是 `SettingsSource` 契约的镜像来源（`:19-201,205,232,242`）。
 * 所有 getter/setter 的实际实现——AndroidKeyStore AES 加密、旧版明文一次性迁移、
 * `SLIDE_STEPS` 档位归一、`musicSelection` 一行一个 id 的编解码、证书 pin 读写——
 * **全部留在 `FnSettings` 内，本文件一行都没有复制或改写**。
 *
 * 用「包一层」而不是让 `FnSettings : SettingsSource` 直接实现接口，是为了让原类
 * 连 `override` 关键字都不用加，彻底满足「只复制搬移、不修改原有逻辑」。
 * 第 5 批由 `di/AppContainer` 单点 `new PrefsSettingsSource(FnSettings(appContext))`，
 * 再把 `AppViewModel` 里的 `settings` 字段类型换成 `SettingsSource`；
 * 在那之前本类尚未接线，装到设备上行为与基线逐像素一致。
 *
 * 对应的生命周期清理应该在原文件的哪个位置调用：
 * 【无需清理】。`FnSettings` 只持有 `SharedPreferences`，无 fd、无监听器、无线程、
 * 无协程作用域；本适配器只多一个 delegate 引用，随持有者一起被 GC。
 * `MainActivity.onDestroy()` / `AppViewModel.onCleared():1198` 不需要新增调用。
 * `forget()` 是业务动作（退出登录清密码/访问码），**不是**生命周期清理方法。
 *
 * 线程约定：保持同步属性。这是既有事实——`AppViewModel:160-181` 在组合期/主线程
 * 一次性读 20 个属性构造 `AppState`，改 suspend 会逼着改十几处调用点（禁止改写）。
 * 未来迁 DataStore 另开一轮重构，不在本次范围内。
 */
class PrefsSettingsSource(private val delegate: FnSettings) : SettingsSource {

    override var host: String
        get() = delegate.host
        set(value) { delegate.host = value }

    override var userName: String
        get() = delegate.userName
        set(value) { delegate.userName = value }

    override var password: String
        get() = delegate.password
        set(value) { delegate.password = value }

    override var accessCode: String
        get() = delegate.accessCode
        set(value) { delegate.accessCode = value }

    override var deviceId: String
        get() = delegate.deviceId
        set(value) { delegate.deviceId = value }

    override val hasSavedLogin: Boolean get() = delegate.hasSavedLogin

    override var slideSeconds: Int
        get() = delegate.slideSeconds
        set(value) { delegate.slideSeconds = value }

    override var slideshowShuffled: Boolean
        get() = delegate.slideshowShuffled
        set(value) { delegate.slideshowShuffled = value }

    override var previewOriginal: Boolean
        get() = delegate.previewOriginal
        set(value) { delegate.previewOriginal = value }

    override var slideshowFade: Boolean
        get() = delegate.slideshowFade
        set(value) { delegate.slideshowFade = value }

    override var slideshowZoom: Boolean
        get() = delegate.slideshowZoom
        set(value) { delegate.slideshowZoom = value }

    override var slideshowFill: Boolean
        get() = delegate.slideshowFill
        set(value) { delegate.slideshowFill = value }

    override var musicSelection: Set<String>
        get() = delegate.musicSelection
        set(value) { delegate.musicSelection = value }

    override var musicShuffled: Boolean
        get() = delegate.musicShuffled
        set(value) { delegate.musicShuffled = value }

    override var lyricsEnabled: Boolean
        get() = delegate.lyricsEnabled
        set(value) { delegate.lyricsEnabled = value }

    override var loopSlideshow: Boolean
        get() = delegate.loopSlideshow
        set(value) { delegate.loopSlideshow = value }

    override var lastAlbumId: Long
        get() = delegate.lastAlbumId
        set(value) { delegate.lastAlbumId = value }

    override var idleMinutes: Int
        get() = delegate.idleMinutes
        set(value) { delegate.idleMinutes = value }

    override var backdropWall: Boolean
        get() = delegate.backdropWall
        set(value) { delegate.backdropWall = value }

    override var backdropGrid: Boolean
        get() = delegate.backdropGrid
        set(value) { delegate.backdropGrid = value }

    override var focusZoom: Boolean
        get() = delegate.focusZoom
        set(value) { delegate.focusZoom = value }

    override var focusRingColorHex: String
        get() = delegate.focusRingColorHex
        set(value) { delegate.focusRingColorHex = value }

    override fun forget() = delegate.forget()

    override fun loadPins(): Map<String, String> = delegate.loadPins()

    override fun savePin(host: String, fingerprint: String?) = delegate.savePin(host, fingerprint)
}
