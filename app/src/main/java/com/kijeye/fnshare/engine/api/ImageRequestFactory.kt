package com.kijeye.fnshare.engine.api

import coil3.request.ImageRequest
import com.kijeye.fnshare.domain.model.SharedPhoto

/**
 * 图片请求工厂的契约（结构化重构第 4 批，新增接口，方法签名对齐现有调用点）。
 *
 * 该代码是从以下三处**调用点的函数签名**提取的，实现里的每个参数值逐字照抄原处：
 *  - `ui/ViewerScreen.kt:277-325`（photoUrl / photoRequest）
 *  - `ui/FocusFrame.kt:94-115`（卡片请求，CARD_PX = 384）
 *  - `ui/Backdrop.kt:122-129`（背景请求，1280×720）
 *
 * 为什么工厂单独成接口：`photoRequest` 依赖组合期才知道的屏幕像素尺寸和「原图」开关，
 * 塞进长生命周期的 `ImageEngine` 会变成「运行时修改引擎内部变量」，违反单向数据流。
 * 由 `ImageEngine.requestFactory(w, h) { previewOriginal }` 在组合期产出，viewport 变了
 * 就重新产出一个新工厂，引擎本身始终无状态。
 *
 * ⚠️ 保留 `ImageRequest` 在签名里（用户已拍板）：`ui/PhotoStage.kt` 用 model 相等性判断
 * 「同一张照片换请求」，pack 成自定义类型会重新触发 0.17.1 修掉的叠化失效 bug。
 *
 * 0.24.9：删掉 `videoCoverRequest()` / `VideoCoverRequest` / `cardMemoryCacheKey()`。
 * 视频不再叠图片封面，改为「加载转圈 → 播放器首帧」，见 `ui/VideoLoading.kt`。
 * 卡片请求回到 Coil 默认内存 key，网格和相册墙照样共享同一份解码结果。
 *
 * 生命周期清理：**无需清理**。工厂只持有 Context 与不可变数值，不建协程、不开 fd。
 * 资源配置归 `ImageEngine`，而它按第 5/6 批的决定不 `close()`（进程级单例，
 * 随 OS 回收）。`AppViewModel.onCleared()` 不为此新增调用。
 */
interface ImageRequestFactory {

    /** 当前该显示哪张 URL：受「原图」开关控制。 */
    fun photoUrl(target: SharedPhoto?, original: Boolean): String?

    /** 全屏图的请求。显示和预加载都必须走这一个函数。 */
    fun photoRequest(target: SharedPhoto?, original: Boolean): ImageRequest?

    /** 卡片图。尺寸写死 384，跨屏共享内存缓存 key。 */
    fun cardRequest(url: String?): ImageRequest?

    /** 毛玻璃背景。尺寸锁死 1280×720。 */
    fun backdropRequest(url: String?): ImageRequest?
}
