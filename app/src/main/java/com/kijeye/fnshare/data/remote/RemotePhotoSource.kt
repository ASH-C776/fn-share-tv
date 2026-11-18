package com.kijeye.fnshare.data.remote

import com.kijeye.fnshare.data.FnDataSource
import com.kijeye.fnshare.domain.model.PhotoPage
import com.kijeye.fnshare.domain.model.SharedAlbum
import com.kijeye.fnshare.domain.port.PhotoSource

/**
 * 该代码是从 `data/FnRepo.kt` 提取的（结构化重构第 5 批），方式是**包一层转发**：
 * 五个方法逐个委托给 `FnDataSource`，原类一行未改。
 * 握手、token 过期静默重登再重放、OkHttp 拦截器加 `AccessToken`、自签证书 pin
 * 全部仍在 `FnRepo` 内，本文件没有复制、改写或新增任何判断。
 *
 * 唯一的收敛：`signIn` / `resume` 的 `FnSession` 返回值在这里被丢弃。
 * `ui/AppViewModel.kt` 的所有调用点都用 `runCatching { }.exceptionOrNull()`，
 * 从未读取返回值，因此这是纯签名收敛、零行为变化。
 *
 * 对应的生命周期清理应该在原文件的哪个位置调用：
 * 【本适配器无需清理】。只多一个 delegate 引用，无协程、无定时器、无 fd。
 * 底层 `FnRepo` 由 `di/AppContainer` 进程级持有；`signOut()` 是业务动作，
 * 调用位置仍是 `ui/AppViewModel.kt` 的 `signOut()`，不要挪到 `onCleared():1198`。
 *
 * 线程约定：不切线程，与 `data/FnRepo.kt` 完全一致；IO 调度仍由
 * `ui/AppViewModel.kt` 既有的 `withContext(Dispatchers.IO)` 负责。
 */
class RemotePhotoSource(private val delegate: FnDataSource) : PhotoSource {

    override fun signIn(rawHost: String, userName: String, password: String, accessCode: String) {
        delegate.signIn(rawHost, userName, password, accessCode)
    }

    override fun resume() {
        delegate.resume()
    }

    override fun signOut() = delegate.signOut()

    override fun albums(): List<SharedAlbum> = delegate.albums()

    override fun photos(albumId: Long, offset: Int, limit: Int): PhotoPage =
        delegate.photos(albumId, offset, limit)
}
