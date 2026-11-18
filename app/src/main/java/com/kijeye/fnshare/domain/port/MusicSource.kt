package com.kijeye.fnshare.domain.port

import com.kijeye.fnshare.domain.model.MusicEntry

/**
 * 该代码是从哪个原文件提取的：
 * 【新增契约，无函数体被搬动】。签名逐字镜像 `data/music/MusicScanner.kt:43,50,57`
 * 现有的 `requiredPermission` / `hasPermission()` / `suspend scan()`，
 * 目的是让第 3 批的 `LocalMusicSource` 能直接包住 `MusicScanner` 而不改它一行代码。
 * 注意 `hasPermission()` 保持**同步** `fun`（目录树里写过 suspend 版本，以现有实现为准；
 * 改成 suspend 会逼着改原类和 `AppViewModel:180` 的调用点，违反「不改写」）。
 * 未来飞牛网络曲库由 `RemoteMusicSource` 实现同一个接口，UI 与 ViewModel 只看见本接口。
 *
 * 对应的生命周期清理应该在原文件的哪个位置调用：
 * 【本接口无需清理】。没有定时器、播放器或协程作用域。
 * `MusicScanner` 内部无状态，`scan()` 自带 `withContext(Dispatchers.IO)`。
 * 若未来的网络实现持有 OkHttp 客户端或轮询任务，必须自行提供 `release()`，
 * 并由 `AppViewModel.onCleared():1198` 统一调用（与现有 `musicPlayer.release()` 并列）。
 *
 * 线程约定：实现方必须保证 [scan] 的文件/媒体库遍历在 `Dispatchers.IO` 上执行，
 * 结果由 ViewModel 在主线程写进 StateFlow。UI 层禁止直接调用本接口。
 */
interface MusicSource {

    /** 扫描曲库所需的 Android 权限名；没拿到时曲库界面显示「去授权」。 */
    val requiredPermission: String

    /** 当前是否已拿到读音乐的权限。同步查询，实现内不做 IO。 */
    fun hasPermission(): Boolean

    /** 重新扫描整份曲库。必须在 `Dispatchers.IO` 上跑。 */
    suspend fun scan(): List<MusicEntry>
}
