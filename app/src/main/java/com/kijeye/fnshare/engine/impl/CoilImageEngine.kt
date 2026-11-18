package com.kijeye.fnshare.engine.impl

import android.app.ActivityManager
import android.content.Context
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.ImageRequest
import com.kijeye.fnshare.core.ImageBudget
import com.kijeye.fnshare.core.ImageSizes
import com.kijeye.fnshare.engine.api.ImageEngine
import com.kijeye.fnshare.engine.api.ImageRequestFactory
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.File

/**
 * 该代码是从 `ui/AppViewModel.kt:68-84`（ImageLoader 构造）与
 * `MainActivity.kt:96-98`（`SingletonImageLoader.setSafe`）逐字提取的（结构化重构第 4 批）。
 *
 * 构造参数、缓存目录、组件注册顺序全部原样：
 *  - `OkHttpNetworkFetcherFactory(callFactory = { repo.client })` → `{ client }`（0.23.0 起
 *    再派生一个只多一个 Content-Length 拦截器的子客户端，见 [contentLengthInterceptor]）
 *  - 内存缓存按整机 RAM 算，见 [ImageBudget.memoryCacheBytes]
 *  - 磁盘缓存目录 0.24.4 起为 `filesDir/coil3_disk_cache`（旧目录启动时整目录搬过来），
 *    上限按可用空间算，见 [ImageBudget.diskCacheBytes]
 * 没有给 OkHttp 再加一层 Cache（Coil 自带磁盘缓存，那层永远不会被创建）。
 *
 * ⚠️ 全局只能有**一个**实例：同目录两个 ImageLoader 会损坏 Coil 的 journal。
 * 第 5 批起由 `di/AppContainer` 单点构造本类，`MainActivity` 经
 * `AppViewModel.installImageEngine()` 调 `install()`；第 6 批把 UI 侧最后的
 * `ImageLoader` 过渡引用（`rawLoader`）删掉，本类现在是实现内部唯一的持有者。
 * 改上限常量不需要清缓存迁移：journal 头不落盘 maxSize，每次构造时传入即生效。
 *
 * 生命周期清理：`close()`（转发 `ImageLoader.shutdown()`）**保留但按第 5 批决定不调用**：
 * 容器是进程级单例，在 `AppViewModel.onCleared()` 里 close 会让 VM 重建后全局仍指向
 * 已 shutdown 的 loader（满屏灰块）。`onCleared()` 只 release 音频与歌词节拍器。
 */
class CoilImageEngine(
    private val context: Context,
    private val client: OkHttpClient,
) : ImageEngine {

    /**
     * 只给图片用的一份客户端：共享客户端的证书 pin、AccessToken 拦截器全部继承，
     * 只多挂一个记录 `Content-Length` 的拦截器。
     *
     * 为什么不直接改 `client`：那是 API 请求、视频取流共用的实例，往里塞一个只服务
     * 信息栏的拦截器不合适。`newBuilder()` 派生成本几乎为零（连接池、线程池共用）。
     */
    private val imageClient: OkHttpClient =
        client.newBuilder().addInterceptor(contentLengthInterceptor()).build()

    /**
     * 单进程能拿到的总内存。Android 8 之后位图像素在 native heap，不受 Java 堆
     * `heapgrowthlimit`（这台电视是 192MB）的限制，但吃掉的是整机 PSS——
     * 所以按总 RAM 取固定比例，而不是按 Java 堆。
     */
    private val totalRamBytes: Long =
        (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
            ?.let { am ->
                ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.totalMem
            } ?: 0L

    /**
     * 必须在 [imageLoader] 之前初始化：目录要定下来、旧缓存要搬完，才能让 Coil 打开 journal。
     * 迁移只发生在首次启动，且是整目录 rename，不复制几 GB 数据。细节见 [resolveImageCacheDir]。
     */
    private val diskCacheDir: File = resolveImageCacheDir(context.filesDir, context.cacheDir)

    private val imageLoader: ImageLoader = ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { imageClient })) }
        .memoryCache {
            MemoryCache.Builder()
                .maxSizeBytes(ImageBudget.memoryCacheBytes(totalRamBytes))
                .build()
        }
        // 上限给死值（默认「剩余磁盘 × 2%」会随占用自己漂，设置页就没法显示
        // 「离满还差多少」）。10GB 是给「全库缩略图 + 全库 1080p 预览」留的余量，
        // 再用可用空间 1/3 兜底低容量设备。filesDir 与 cacheDir 同一分区，取它的
        // 可用空间就是新缓存目录真正能用的空间。
        // 原图不进这个池（请求带 CachePolicy.DISABLED），稳态只占 2~4GB。
        .diskCache {
            DiskCache.Builder()
                .directory(diskCacheDir)
                .maxSizeBytes(ImageBudget.diskCacheBytes(context.filesDir.usableSpace))
                .build()
        }
        .build()

    /** 必须在 setContent 之前：组合里才设置会让第一批 AsyncImage 用默认加载器发出灰块请求。 */
    override fun install() {
        // 容器是进程级单例，Activity 每次 onCreate 都会走到这里。setSafe 本身对同一实例
        // 是安全的，但显式幂等更省心：绝不重复注册、绝不重建 loader。
        if (installed) return
        installed = true
        SingletonImageLoader.setSafe { imageLoader }
    }

    @Volatile
    private var installed = false

    override fun requestFactory(
        screenWpx: Int,
        screenHpx: Int,
        previewOriginal: () -> Boolean,
    ): ImageRequestFactory = CoilImageRequestFactory(
        context = context,
        imageLoader = imageLoader,
        screenWpx = screenWpx,
        screenHpx = screenHpx,
        previewOriginal = previewOriginal,
    )

    /** 原 `ui/ViewerScreen.kt:498` 的 `imageLoader.enqueue(it)`。 */
    override fun enqueue(request: ImageRequest) {
        imageLoader.enqueue(request)
    }

    /** Coil 3.5.0 的 DiskCache.size 是同步的，不是 suspend。原 `ui/AppViewModel.kt:719`。 */
    override fun diskBytes(): Long = imageLoader.diskCache?.size ?: 0L

    /** 原 `ui/AppViewModel.kt:725` 的 `disk?.maxSize ?: 0L`。 */
    override fun diskMaxBytes(): Long = imageLoader.diskCache?.maxSize ?: 0L

    /** 原 `ui/AppViewModel.kt:741` 的 `imageLoader.diskCache?.clear()`。 */
    override fun clearDisk() {
        imageLoader.diskCache?.clear()
    }

    /** ⚠️ 第 5 批接线后不调用，原因见 `engine/api/ImageEngine.kt` 的 close() 注释。 */
    override fun close() {
        imageLoader.shutdown()
    }

    private companion object {
        /**
         * 把每个图片响应的 `Content-Length` 记进 [ImageSizes]，供信息栏显示文件大小。
         *
         * 为什么不再只靠磁盘快照：0.23.0 起原图请求带 `CachePolicy.DISABLED`，磁盘里
         * 根本没有那份文件，`openSnapshot` 永远返回 null，信息栏会退化成只剩文件名。
         * 响应头是唯一既准确又不依赖缓存的来源（OkHttp 对图片不做 gzip，
         * Content-Length 就是源文件字节数）。
         *
         * 命中磁盘缓存时不会走到这里（压根没发请求），但 [ImageSizes] 是进程级表，
         * 第一次真正下载时就记下了，之后一直有效。
         */
        fun contentLengthInterceptor(): Interceptor = Interceptor { chain ->
            val response: Response = chain.proceed(chain.request())
            response.header("Content-Length")?.toLongOrNull()?.takeIf { it > 0 }?.let { bytes ->
                ImageSizes.map[chain.request().url.toString()] = bytes
            }
            response
        }
    }
}

/**
 * 图片磁盘缓存该放哪（0.24.4 起）。
 *
 * 原来放 `cacheDir/coil3_disk_cache`：Android 在空间紧张时会**不打招呼**清掉整个
 * cacheDir，下一次冷启动全库缩略图就得重下，相册墙只剩占位图——用户报的「启动后
 * 相册封面是空的」有一部分就是这么来的。清单缓存早就放在 filesDir，图片漏了这一处。
 *
 * 直接换目录会让已经攒下的几 GB 缓存作废，所以首次启动把旧目录整目录搬过来：
 * cacheDir 与 filesDir 是同一分区下的兄弟目录，renameTo 原子完成、不复制数据，
 * Coil 的 journal 记的是相对路径，搬完照常命中。搬不动（极少数 ROM）就直接用新目录，
 * 旧目录留给系统自己清，不会比现状更差。
 *
 * 只搬目录、不碰单个文件：不做逐文件拷贝，也不主动删旧目录，避免半途失败留下半份缓存。
 */
internal fun resolveImageCacheDir(filesDir: File, cacheDir: File): File {
    val target = File(filesDir, IMAGE_CACHE_DIR)
    if (target.exists()) return target
    val legacy = File(cacheDir, IMAGE_CACHE_DIR)
    if (legacy.isDirectory) {
        filesDir.mkdirs()
        // renameTo 返回 false 也不回滚：新目录交给 Coil 自己建，旧目录留给系统清理。
        legacy.renameTo(target)
    }
    return target
}

internal const val IMAGE_CACHE_DIR = "coil3_disk_cache"
