package com.kijeye.fnshare.domain.port

import com.kijeye.fnshare.domain.model.PhotoPage
import com.kijeye.fnshare.domain.model.SharedAlbum

/**
 * 该代码是从 `data/FnDataSource.kt` 的 `FnAuth + FnPhotoApi + FnDataSource` 三个接口
 * 提取的（结构化重构第 5 批）。提取的是**上层真正用到的那部分签名**：
 *  - `signIn / resume / signOut`（原 `FnAuth`）
 *  - `albums / photos`（原 `FnPhotoApi`）
 *
 * 刻意不搬 `FnSession` 返回类型与 `val client: OkHttpClient`：
 *  - `AppViewModel` 里 `resume()` / `signIn()` 一律包在 `runCatching { }.exceptionOrNull()`
 *    中，返回值从未被读取，接口收敛成 `Unit` 不改变任何分支；
 *  - `client` 是传输层细节，属于 `core/SharedHttpEngine`，由 `di/AppContainer` 单点持有，
 *    不该出现在领域契约里（图片、视频、接口三处共用它，但都不从领域层拿）。
 * 原 `FnDataSource` / `FnRepo` 一行未改，`data/remote/RemotePhotoSource` 只做转发。
 *
 * 对应的生命周期清理应该在原文件的哪个位置调用：
 * 【本接口无需清理】。没有定时器、协程作用域或打开的 fd。底层 `FnRepo` 的
 * OkHttpClient / 会话 token 由 `di/AppContainer` 进程级持有，随进程消亡；
 * `signOut()` 是业务动作（清 token 与存档账号），**不是**生命周期清理方法，
 * 调用位置仍是 `ui/AppViewModel.kt` 的 `signOut()`，不要挪到 `onCleared()`。
 *
 * 线程约定：全部是同步阻塞方法，实现方不切线程；现有调用点一律在
 * `withContext(Dispatchers.IO)` 内执行（`ui/AppViewModel.kt` 的 `signIn` / `loadAlbums` /
 * `loadPhotoPage` / `runDateIndex` / `fetchWholeAlbum`），接线后这些 `withContext` 原样保留。
 */
interface PhotoSource {

    /** 走完整握手登录；失败时抛具体异常（访问码缺失、二次验证、地址错误……），由上层决定怎么处理。 */
    fun signIn(rawHost: String, userName: String, password: String, accessCode: String)

    /** 用本地存下来的账号重新登。失败原因同样如实抛出。 */
    fun resume()

    /** 清掉 token 和存档的账号密码（显示设置不属于账号，不动）。 */
    fun signOut()

    /** 别人共享给我的相册。返回接口给的原顺序。 */
    fun albums(): List<SharedAlbum>

    /** 一个相册的照片分页。视频也在里面，由上层决定怎么显示。 */
    fun photos(albumId: Long, offset: Int, limit: Int): PhotoPage
}
