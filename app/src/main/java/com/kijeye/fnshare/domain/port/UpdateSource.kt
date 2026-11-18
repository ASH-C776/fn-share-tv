package com.kijeye.fnshare.domain.port

import com.kijeye.fnshare.domain.model.UpdateInfo
import kotlinx.coroutines.flow.StateFlow

/**
 * 新版本检测与下载的领域契约。
 *
 * UI / ViewModel 只认这个接口，不认识 Gitee / GitHub 的 API 域名、JSON 结构、
 * 也不认识 OkHttp。具体实现在 data/remote，经 engine/api → di 注入。
 */
interface UpdateSource {
    /** 最近一次检测结果的最新版本；null = 没有更新或还没查过。 */
    val available: StateFlow<UpdateInfo?>

    /** 下载进度 0..100；不在下载中时为 null。 */
    val progress: StateFlow<Int?>

    /** 下载是否失败（网络断、空间不足、大小校验不过）。 */
    val failed: StateFlow<Boolean>

    /**
     * 查两端 Release，取最高且比 [currentCode] 新的版本写进 [available]。
     * 任何网络/解析失败都吞掉，不抛给调用方——更新检测失败绝不能影响正常看图。
     */
    suspend fun check(currentCode: Int)

    /**
     * 下载 [info] 到应用私有目录，进度写入 [progress]。
     * 成功返回本地文件路径，失败返回 null（同时置 [failed]）。
     */
    suspend fun download(info: UpdateInfo): String?

    /** 关掉弹窗后清空失败态，下次进来是干净的。 */
    fun clearFailure()
}
