package com.kijeye.fnshare.data.music

import com.kijeye.fnshare.domain.model.MusicEntry
import com.kijeye.fnshare.domain.port.MusicSource

/**
 * 该代码是从哪个原文件提取的：
 * 【新增占位实现，无原文件、无逻辑可提取】。目录树里约定的「飞牛网络曲库」实现桩，
 * 目的是让 `MusicSource` 从一开始就有「本地 / 网络两个实现」的形状，
 * 证明 UI 与 ViewModel 只依赖接口而不依赖 `MusicScanner`。
 *
 * ⚠️ **当前不是可用功能**：`scan()` 恒返回空列表，`requiredPermission` 为空串，
 * `hasPermission()` 恒 true。目录树里写的是「先留空实现桩」，本批严格遵守——
 * 不凭空加网络请求、不改任何现有行为、不接线、不注册到 `AppContainer`。
 * 真正接入飞牛 API 是独立需求，需用户单独确认后再做。
 *
 * 对应的生命周期清理应该在原文件的哪个位置调用：
 * 【当前无需清理】。本类无状态、无 OkHttp 客户端、无协程作用域、无定时器。
 * ⚠️ 未来一旦接入网络，持有 OkHttp 客户端或轮询任务时必须补 `release()`，
 * 并由 `AppViewModel.onCleared():1198` 统一调用（与现有 `musicPlayer.release()` 并列）；
 * 不要挂到 `MainActivity.onDestroy()`，ViewModel 比 Activity 活得久。
 *
 * 线程约定：当前无 IO。`scan()` 是 suspend 但立即返回，未来接入后必须把请求
 * 放进 `core.AppDispatchers.io`，结果由 ViewModel 在主线程写进 StateFlow。
 */
class RemoteMusicSource : MusicSource {

    override val requiredPermission: String get() = ""

    override fun hasPermission(): Boolean = true

    override suspend fun scan(): List<MusicEntry> = emptyList()
}
