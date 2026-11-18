package com.kijeye.fnshare.engine.impl

import android.content.Context
import coil3.ImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.crossfade
import coil3.size.Precision
import coil3.size.Size
import com.kijeye.fnshare.domain.model.SharedPhoto
import com.kijeye.fnshare.engine.api.ImageRequestFactory

/**
 * 该代码是从以下三处**逐字提取**的（结构化重构第 4 批，参数值一个都没改）：
 *  - `ui/ViewerScreen.kt:277-325`（photoUrl / photoRequest，含 `.size()` 解码框、
 *    `Precision.INEXACT`、`crossfade(false)`、磁盘字节数 listener）
 *  - `ui/FocusFrame.kt:94-115`（卡片请求 `CARD_PX = 384` + `crossfade(200)`）
 *  - `ui/Backdrop.kt:122-129`（背景请求 1280×720）
 *
 * 三处原本都是 Compose 组合期局部函数/`remember`，这里改成按 viewport 构造的普通类。
 * 请求构造表达式、尺寸算式、精度、淡入开关、缓存 key 完全照抄——尤其：
 *  - `.size()` 决定解码框而不是「提示」，原图必须乘 `ORIGINAL_DECODE_SCALE`，
 *    否则磁盘里躺着 4.6MB 原图，屏幕上还是 1080p（0.18.x 修过）；
 *  - `Precision.INEXACT` 防止小图被放大到框白占内存；
 *  - `crossfade(false)` 防止 Coil 淡一次、幻灯片再叠化一次。
 *
 * 唯一的改写：`ImageSizes.map` 的写入目标从 `ui` 包换成 `core` 包（同一张表，
 * `ui/ImageSizes.kt` 现在是委托），避免内层反向引用 UI 层。
 *
 * 0.23.0 起多两条策略（都不改档位选择）：
 *  - `original = true` 的请求带 `CachePolicy.DISABLED`：原图只进内存，不写磁盘；
 *  - [photoUrl] 依旧完全由「原图」开关决定，没有按屏幕宽度分档——4K 电视上想用原图
 *    仍然要用户自己开那个开关。
 *
 * 第 6 批已接线：`ui/ViewerScreen.kt` 的 `photoUrl` / `photoRequest` 局部函数改成
 * 同名同签名的委托，`ViewerContent` 持 [ImageRequestFactory] 而不是 `ImageLoader`。
 * 第 7 批已接线：`ui/FocusFrame.kt` 的 `rememberCardImageRequest` / `CardImage` 与
 * `ui/Backdrop.kt` 的 `FocusBackdrop` 改收工厂参数（`WallScreen` / `GridScreen` 从
 * `MainActivity` 顶层 remember 的同一个工厂逐层传入），UI 侧不再用 `LocalContext`
 * 自建请求——全部四个构造点收敛到本类，缓存 key 与原先逐字一致。
 *
 * 生命周期清理：**无需清理**。无协程、无定时器、无 fd；listener 由 Coil 在请求结束后回收。
 * 资源配置归 `CoilImageEngine`，按第 5/6 批决定不 `close()`；`AppViewModel.onCleared()`
 * 不为此新增调用。
 */
class CoilImageRequestFactory(
    private val context: Context,
    private val imageLoader: ImageLoader,
    private val screenWpx: Int,
    private val screenHpx: Int,
    private val previewOriginal: () -> Boolean,
) : ImageRequestFactory {

    /** 当前该显示哪张 URL：受「原图」开关控制——关时 mUrl(1080p 预览)，开时 originalUrl。 */
    override fun photoUrl(target: SharedPhoto?, original: Boolean): String? =
        if (target == null) null
        else if (original) target.originalUrl ?: target.fullUrl ?: target.thumbUrl
        else target.fullUrl ?: target.thumbUrl

    override fun photoRequest(target: SharedPhoto?, original: Boolean): ImageRequest? {
        val url = photoUrl(target, original) ?: return null
        // 解码尺寸必须跟着原图开关走，否则原图等于没开。
        val scale = if (original) ORIGINAL_DECODE_SCALE else 1f
        val builder = ImageRequest.Builder(context)
            .data(url)
            .size(
                Size(
                    (screenWpx * KB_DECODE_SLACK * scale).toInt(),
                    (screenHpx * KB_DECODE_SLACK * scale).toInt(),
                ),
            )
            // 只降不升：源图比框小的时候按源图原样解。
            .precision(Precision.INEXACT)
            // crossfade 关死：Coil 自己淡一次、幻灯片再叠化一次，就成了两次淡化。
            .crossfade(false)

        // 0.23.0：**原图只进内存，不写磁盘**。
        //
        // 一张原图 5~6MB，十张就是 60MB、一百张 600MB，而它是「看过一次就基本不再看」
        // 的内容；磁盘池真正该装的是缩略图（16KB）和 mUrl 预览（250KB），全库两样加起来
        // 也就 2~4GB。原图继续落盘会把这两样按 LRU 挤出去，网格反而要重新拉。
        //
        // DISABLED 同时意味着不读：磁盘里原有版本留下的原图条目不再命中，会被正常淘汰；
        // 重新看同一张要现下（局域网 0.3~1 秒），换来磁盘恒定。
        // 只对 `original = true` 生效——缩略图、背景、mUrl 预览仍照常进磁盘。
        if (original) builder.diskCachePolicy(CachePolicy.DISABLED)

        return builder
            // 加载成功就把这份图在 Coil 磁盘缓存里的真实字节数记下来，供信息栏显示。
            // ⚠️ 原图不落盘，这条分支对它永远拿不到快照；原图的大小由
            // `CoilImageEngine` 里的 Content-Length 拦截器写入同一张表。
            .listener(
                object : ImageRequest.Listener {
                    override fun onSuccess(request: ImageRequest, result: SuccessResult) {
                        val key = request.diskCacheKey
                        if (!key.isNullOrBlank()) {
                            val snapshot = imageLoader.diskCache?.openSnapshot(key)
                            if (snapshot != null) {
                                // okio.Path.toString() 即磁盘上的真实路径，直接当 File 取大小（字节）。
                                val bytes = runCatching { java.io.File(snapshot.data.toString()).length() }
                                    .getOrDefault(0L)
                                snapshot.close()
                                if (bytes > 0) {
                                    com.kijeye.fnshare.core.ImageSizes.map[request.data.toString()] = bytes
                                }
                            }
                        }
                    }
                },
            )
            .build()
    }

    /** 尺寸写死 384：同一张图在网格和相册墙里落在不同约束下，写死才对得上内存缓存 key。 */
    override fun cardRequest(url: String?): ImageRequest? = url?.let {
        ImageRequest.Builder(context)
            .data(it)
            .size(CARD_PX, CARD_PX)
            // 图到了淡入 200ms 盖上占位底色。内存缓存命中时 Coil 不播 crossfade，这是对的。
            .crossfade(200)
            // 0.24.9：显式 memoryCacheKey 是给已删除的视频封面同步读位图用的，
            // 回到 Coil 默认 key；网格与相册墙仍共享同一份解码结果。
            .build()
    }

    /** size 锁死 1280×720：Coil 按这个目标下采样解码，不碰原图分辨率。 */
    override fun backdropRequest(url: String?): ImageRequest? = url?.let {
        ImageRequest.Builder(context)
            .data(it)
            .size(BACKDROP_WIDTH, BACKDROP_HEIGHT)
            .build()
    }

    private companion object {
        /**
         * 全屏图解码尺寸相对屏幕的余量。原 `ui/ViewerScreen.kt:1098`。
         *
         * 屏幕像素只够 1.0 倍铺满，而 Ken Burns 末端要放大到 1.10（8 秒档），按屏幕尺寸
         * 解码再放大就是拿 1.0 的像素去铺 1.1 的画面，推镜最后两秒会明显发虚。留 12%
         * 余量（1080p 一张约多 2MB）换来全程清晰，电视上这点内存花得起。
         */
        const val KB_DECODE_SLACK = 1.12f

        /**
         * 开「原图」时解码框相对屏幕的倍数。原 `ui/ViewerScreen.kt:1115`。
         *
         * 这是「原图开关到底有没有用」的分水岭：Coil 会把任何尺寸的源图降采样进
         * `.size()` 那个框里，框设成屏幕大小，一张 3072×4096 的原图解出来也就跟 mUrl
         * 那档 1080p 预览一个量级——用户按了「原图」，画面一个像素都没变，还白等了十几 MB。
         *
         * 取 2.0 是两头夹出来的：
         *  - 下限：手动放大档最大是 2.5，按屏幕解 = 拿 1 像素铺 2.5 像素，必糊。
         *    2.0 倍 = 4304×2420 的框，配 1920 宽的屏，放到 2.5 倍时只欠 11% 像素，肉眼无感。
         *  - 上限：这张位图是常驻的（`PhotoStage` 两个槽各存一张），2.0 倍 12MP 约 48MB，
         *    两槽 + 一次解码峰值约 100MB，在 `largeHeap` 的 512MB 里有余量；再往上就是拿
         *    内存换电视三米外看不出的锐度。
         * 不担心小图被拉大：请求都带 `Precision.INEXACT`，源图比框小就按源图原样解。
         */
        const val ORIGINAL_DECODE_SCALE = 2.0f

        /** 原 `ui/FocusFrame.kt:94`，不要给 512 以上。 */
        const val CARD_PX = 384

        /** 原 `ui/Backdrop.kt:54-55`。 */
        const val BACKDROP_WIDTH = 1280
        const val BACKDROP_HEIGHT = 720
    }
}
