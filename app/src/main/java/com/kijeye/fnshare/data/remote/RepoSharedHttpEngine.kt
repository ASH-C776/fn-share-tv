package com.kijeye.fnshare.data.remote

import com.kijeye.fnshare.core.SharedHttpEngine
import com.kijeye.fnshare.data.FnDataSource

/**
 * 该代码是从 `ui/AppViewModel.kt:69`（`callFactory = { repo.client }`）与
 * `ui/AppViewModel.kt:95-96`（`OkHttpDataSource.Factory(repo.client)`）这两处用法
 * 提取的（结构化重构第 5 批）。
 *
 * 为什么不让 `RemotePhotoSource` 顺手实现 `SharedHttpEngine`：领域契约该只描述
 * 「能取到什么数据」，一旦挂上 OkHttpClient 就把它绑死在 OkHttp 上（见
 * `domain/port/PhotoSource.kt` 的文件头）。组装层知道具体实现是允许的，所以由
 * `di/AppContainer` 把同一个 `FnRepo` 分别交给本类和 `RemotePhotoSource`。
 *
 * 对应的生命周期清理应该在原文件的哪个位置调用：
 * 【本适配器无需清理】。只转发 `FnDataSource.client`，不新建客户端、不持有线程池。
 * 客户端本身由 `di/AppContainer` 进程级持有，随进程消亡；
 * `AppViewModel.onCleared():1198` 不需要为它新增任何调用。
 *
 * 线程约定：`client` 是构造期就建好的不可变引用，getter 无 IO、可任意线程调用。
 */
class RepoSharedHttpEngine(private val delegate: FnDataSource) : SharedHttpEngine {

    override val client get() = delegate.client
}
