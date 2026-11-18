package com.kijeye.fnshare.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.kijeye.fnshare.data.fn.SharedPhoto
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 一个图层槽：一张照片 + 它的透明度 + 它的 Ken Burns 缩放。
 *
 * 槽是**常驻**的：照片没轮到它时它不会被拆掉，模型也不会变。这一点是全屏叠化能
 * 流畅的唯一前提——见 [PhotoStage] 的说明。
 */
class PhotoSlot internal constructor(initialAlpha: Float) {
    var photo by mutableStateOf<SharedPhoto?>(null)
    /** 这个槽有东西可以揭幕了（预览层或原图任一到手都算）。 */
    var ready by mutableStateOf(false)

    /** 预览层（mUrl）的结果。非双层时永远是 false。 */
    var previewDone by mutableStateOf(false)
    var previewLoaded by mutableStateOf(false)
    /** 原图层（可能等于预览层）的结果。 */
    var originalDone by mutableStateOf(false)
    var originalLoaded by mutableStateOf(false)

    /** 这个槽是不是分了两层（预览 + 原图）。由 [StageLayer] 每次组合时写，不是状态。 */
    var dualLayer: Boolean = false

    /**
     * 这一层真的画出内容了（不是失败后露出的黑底）。
     * 它决定垫底缩略图还要不要画——见 [PhotoStage] 里那一层。
     *
     * 派生自两层的结果而不是自己存一个字段：两层任一成功就算有内容，
     * 这样「切原图档时把 loaded 复位」不会牵连还在显示着的预览层。
     * 单层时预览层的残留标记不算数（`dualLayer` 是 [StageLayer] 每次组合时写的）。
     */
    val loaded: Boolean get() = originalLoaded || (dualLayer && previewLoaded)

    /** 上一次按哪个照片归过零，用来让 [StageLayer] 在换图时同步复位一次。 */
    var marksFor: Long? = null

    /** 换照片时把这一槽的所有标志归零（`alpha` / `scale` 由调用方决定怎么处理）。 */
    fun resetMarks() {
        ready = false
        previewDone = false
        previewLoaded = false
        originalDone = false
        originalLoaded = false
    }

    fun onPreviewResult(ok: Boolean) {
        previewDone = true
        previewLoaded = ok
        // 预览先到就先揭幕：这正是「快速连按时也不卡」的关键。
        ready = true
    }

    fun onOriginalResult(ok: Boolean) {
        originalDone = true
        originalLoaded = ok
        ready = true
    }

    val alpha = Animatable(initialAlpha)

    /**
     * Ken Burns 当前的缩放。
     *
     * 它每帧被推镜协程写一次：用 [Animatable].snapTo 的话每次写都要起一个协程再挂起等待，
     * 帧帧如此纯属白费调度。改成 [mutableFloatStateOf] 直写，读端又只在 graphicsLayer
     * 的绘制回调里读——写只让这一层重绘，不触发任何重组。
     */
    var scale by mutableFloatStateOf(1f)
}

/**
 * 全屏照片舞台：两个槽轮流上场，换图时只动「空着的那个槽」。
 *
 * ## 为什么不是「一张当前图 + 一层旧图」
 *
 * 老写法每次换图都新建一个画旧图的 `AsyncImage`。Coil 的 model 一变就把上一张的
 * painter 清掉重新 load——**哪怕是内存缓存命中也得等一个异步回调**。于是换图那一帧
 * 三个图层同时是空的（新图在解、旧图层刚重挂、缩略图也刚换了 URL），录屏里就是一帧
 * 纯黑（YAVG 16），紧接着要么闪一下新图再退回旧图、要么直接硬切。
 *
 * 两个槽都一直挂在组合里，槽里的照片不轮到它就一个字节都不动。换图只发生在
 * **alpha = 0、完全看不见的那个槽**里，所以任何一帧都至少有一张图是满屏不透明的，
 * 不可能出现空白帧。
 *
 * ## 谁淡谁
 *
 * 两个槽在布局里的上下关系固定（槽 0 在下、槽 1 在上，交换等于重建，绝对不换），
 * 所以淡化方向按「新图落在哪个槽」分两种，但两种的合成权重都恒等于 1：
 *
 * - 新图落在**上层**：上层 0 → 1，下层保持 1。画面 = t·新 + (1-t)·旧。
 * - 新图落在**下层**：先让它不透明（被上层那张完全盖着，看不见），再把上层 1 → 0。
 *   画面 = (1-t)·旧 + t·新。同一件事，只是从另一边做。
 *
 * 新图**预览档**解出就开闸（不是等原图），所以快速连按时画面永远有反应；原图随后在
 * 槽内原子替换掉预览（同一张照片、同一取景，只换清晰度）。旧图在开闸前一直满屏
 * 亮着，因此也不会「淡到一半黑一下」。
 */
@Composable
fun PhotoStage(
    photo: SharedPhoto?,
    photos: List<SharedPhoto>,
    /** 上一张真正显示过的照片。是视频就清场——视频不走这个舞台，回来后不能把更早那张亮出来。 */
    previousPhoto: SharedPhoto?,
    slideshow: Boolean,
    slideCfg: SlideShowConfig,
    /** 幻灯片是否满屏铺满（Crop）。false = 原比例完整显示（Fit），代价是裁切消失、转场在留白区会有硬切。 */
    fill: Boolean,
    request: (SharedPhoto?) -> ImageRequest?,
    /**
     * 「预览档」的请求（不受原图开关影响）。给垫底层和每个槽的第二层用。
     *
     * 它必须恒为 mUrl：切到原图之后，原图那十几 MB 下载期间靠它顶住画面；跟着 [request]
     * 一起变成原图就等于没垫。传 [ImageRequest] 而不是 URL，是为了和主层用**同一条缓存
     * key**——原图关着的时候两边是同一个请求，直接命中内存缓存，不多解一次码。
     */
    previewRequest: (SharedPhoto?) -> ImageRequest?,
    /**
     * 构成请求的所有要素（解码尺寸、原图开关…）。它变了才重建 model——
     * model 一变 Coil 就把 painter 清掉重新走一遍加载，白白多一次解码。
     */
    requestKey: Any? = null,
    modifier: Modifier = Modifier,
) {
    val slots = remember { listOf(PhotoSlot(1f), PhotoSlot(0f)) }
    // 已经为哪张图揭过幕。上面那个 effect 会因为 ready 翻转等无关变化重启，
    // 靠它挡住重复揭幕（重复 = 动画从半路重新起跑）。
    var fadedFor by remember { mutableStateOf<Long?>(null) }

    // ── 1) 换图：把新照片放进那个看不见的槽 ───────────────────────────────
    LaunchedEffect(photo?.id) {
        val target = photo ?: return@LaunchedEffect
        val existing = slots.firstOrNull { it.photo?.id == target.id }
        if (existing != null) {
            // 循环播放回到一张还在槽里的照片：槽一直挂着，图早就解好了，不用重新加载。
            existing.ready = true
            fadedFor = null
            return@LaunchedEffect
        }
        // 空槽 = alpha 归零的那个（正常静置时必有且只有一个）。兜底用「没装图的槽」。
        var vacant = slots.firstOrNull { it.alpha.value <= 0.001f }
            ?: slots.firstOrNull { it.photo == null }
        if (vacant == null) {
            // 两个槽都在半路：叠化没走完又来了一张（叠化中手动连按，或上一张解码太慢
            // 撞上下一次换图）。这时不能随便抓一个槽 snapTo(0)——它可能正显示着一半，
            // 一帧内抹掉就露一帧黑底。正确做法是就地完成被打断的转场：可见度高的那张
            // 补成完全不透明接住画面，再征用可见度低的那个槽。
            vacant = if (slots[0].alpha.value >= slots[1].alpha.value) slots[1] else slots[0]
            val keep = slots.first { it !== vacant }
            keep.alpha.stop()
            keep.alpha.snapTo(1f)
        }
        if (previousPhoto?.isVideo == true) {
            // 从视频回来：另一个槽里是「视频之前那张照片」，留着就会在解码的几百毫秒里
            // 顶在前头，看着像倒回去了。直接清空，让缩略图垫底，回来就是一次干净硬切。
            slots.forEach {
                if (it !== vacant) {
                    it.alpha.stop()
                    it.alpha.snapTo(0f)
                    it.photo = null
                    it.resetMarks()
                }
            }
        }
        vacant.alpha.stop()
        vacant.alpha.snapTo(0f)
        vacant.resetMarks()
        vacant.photo = target
        fadedFor = null
    }

    // ── 2) 揭幕：新图解码完，按它落在哪个槽决定淡谁 ─────────────────────────
    //
    // ready **不能**当 key。它会在「塞图置 false → 解码完置 true」之间翻一次，而这次翻转
    // 恰好落在揭幕协程刚起跑的那一帧上：effect 被重启，正在跑的 animateTo 被取消——alpha
    // 停在 0.0x，而 [fadedFor] 这个幂等锁又让重启后的协程直接 return，于是再没人把这张图
    // 推上去。屏幕上就是「放到第二张就不动了」：下面那张 alpha 卡在 1.0 永远不撤，
    // 之后每张新图都只能进上层且几乎是透明的。日志实测两次重启只隔 10ms（动画长 800ms）。
    //
    // 改成 effect 内部用 snapshotFlow 等 ready：effect 的生命周期只跟「换没换照片」有关，
    // 动画一旦开跑就不会被无关的状态抖动掐断。
    LaunchedEffect(photo?.id, slideshow, slideCfg.crossfadeDuration) {
        val target = photo ?: return@LaunchedEffect
        val idx = slots.indexOfFirst { it.photo?.id == target.id }
        if (idx < 0) return@LaunchedEffect
        val incoming = slots[idx]
        // 等它真的解出来：提前开淡只会把一张空图淡进来。
        snapshotFlow { incoming.ready }.first { it }
        // 双层槽里「ready」可能只是预览层到了，原图还在路上。这时别急着揭幕——
        // 给它 100ms 追上来：
        //  - 停留较久（预取已生效）时原图几乎与预览同时命中，这一等让屏幕上**只有原图**，
        //    不会有「先 1080p 再换 4K」的多余一帧，慢速切换完全无感；
        //  - 快速连按时原图要几百毫秒，超时后照常用预览揭幕，屏幕立刻有反应。
        if (incoming.previewDone && !incoming.originalDone) {
            withTimeoutOrNull(ORIGINAL_GRACE_MS) {
                snapshotFlow { incoming.originalDone }.first { it }
            }
        }
        val outgoing = slots[1 - idx]
        if (outgoing.photo == null) {
            // 没有前一张（刚进大图 / 刚从视频回来）：直接亮，淡无可淡。
            incoming.alpha.snapTo(1f)
            fadedFor = target.id
            return@LaunchedEffect
        }
        val fadeMs = if (slideshow) slideCfg.crossfadeDuration else 0
        // 已经为这张图揭完幕了就别再动一次（重复 = 动画从半路重新起跑，看着像「顿一下」）。
        // 注意这个标记是**动画跑完才置位**：中途被打断的话下次还能重跑补上，
        // 不会再出现「锁住了但画面停在半路」的死角。
        if (fadedFor == target.id) return@LaunchedEffect
        if (idx == 1) {
            // 新图在上层：0 → 1。下层那张必须不透明，否则淡到一半会透出黑底。
            if (outgoing.alpha.value < 1f) outgoing.alpha.snapTo(1f)
            incoming.alpha.snapTo(0f)
            if (fadeMs > 0) {
                incoming.alpha.animateTo(1f, tween(fadeMs, easing = LinearEasing))
            } else {
                incoming.alpha.snapTo(1f)
            }
            // 已经被完全盖住了，归零腾出来当下一轮的空槽。
            outgoing.alpha.snapTo(0f)
        } else {
            // 新图在下层：先让它不透明（此刻被上层那张完全盖着，屏幕上看不见任何变化），
            // 再把上层淡掉。这样淡化期间任意时刻权重和都是 1。
            incoming.alpha.snapTo(1f)
            if (fadeMs > 0) {
                outgoing.alpha.animateTo(0f, tween(fadeMs, easing = LinearEasing))
            } else {
                outgoing.alpha.snapTo(0f)
            }
        }
        fadedFor = target.id
    }

    // 幻灯片态默认 Crop 铺满：两层永远盖住整块屏幕，叠化才没有「独占区域」——
    // 否则宽高比不同的两张图各有对方盖不住的边，揭幕/撤层的那一帧在那些边上就是硬切
    // （新图突然出现、旧图突然消失）。铺满 + 推拉镜头也是幻灯片的常规影调。
    // 竖图被裁得不舒服时可在二级菜单把「比例」改成原比例：完整显示。
    // 手动看图恒为原比例（Fit），硬切不受影响。
    val contentScale = if (slideshow && fill) ContentScale.Crop else ContentScale.Fit

    // 每层自带不透明黑底 —— 原比例也能无缝叠化的关键。
    //
    // Fit 的 AsyncImage 只在不透明的地方画图，四周留白是**透明的**。两层都透明就出两种
    // 硬切：旧图淡出时它的留白区本来就透明，底下的新图第一帧就整块露出来（「旧图还没开始
    // 消失，新图先出现了」）；新图落了下层时 snapTo(1f) 在对方盖不住的那些边上同样一帧满亮。
    //
    // 补上黑底后每一层都是「整块屏幕」（黑底 + 居中图），把屏幕切成四类区域，逐个看都是连续的：
    //   R1 两图都覆盖：t·旧 + (1-t)·新，权重和恒为 1；
    //   R2 只有旧图：旧图 alpha 1→0 淡向自己的黑底，渐隐；
    //   R3 只有新图：旧图的黑底 alpha 1→0 让开，新图渐显；
    //   R4 都没有：黑底对黑底，不动。
    // 于是「独占区域」从跳变变成了一场局部淡入淡出，黑边本身也参与转场。
    // 黑底必须画在 graphicsLayer **里面**（alpha 才能把它一起调制），所以放在它之后。

    Box(modifier.fillMaxSize()) {
        // 垫底图：进图第一帧、全屏图迟迟不到、或者切「原图」档正在等那十几 MB 的时候，
        // 屏幕上至少有个像样的东西。
        //
        // 用 mUrl（预览档）而不是 sUrl 缩略图：后者只有 427×240，铺满
        // 1080p 就是一团糊，等几秒原图时糊着比黑着更难看。
        // 只在「两个槽都没画出东西」时才真画，所以正常浏览时它一分钱不花。
        // 用同一个 contentScale：视频回来清场后露出的是它，取景跟全屏层一致才不会跳。
        val fallback = previewRequest(photo)
        if (fallback != null) {
            AsyncImage(
                model = fallback,
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier
                    .fillMaxSize()
                    // 垫底只在「上面两层都还没画出东西」时才有意义。
                    //
                    // 两层各自带不透明黑底、透明度之和恒为 1，所以只要有一层真的解出了图，
                    // 整块屏幕就被它盖满了——缩略图再画一遍纯属白给（它也是 1080p 的
                    // 绘制面积）。只有刚进图、全屏图还没到的那几百毫秒里它才露得出来。
                    .drawWithContent {
                        if (slots.none { it.loaded && it.alpha.value > VISIBLE_EPS }) drawContent()
                    },
            )
        }
        StageLayer(slots[0], photos, slideshow, slideCfg, contentScale, request, previewRequest, requestKey)
        StageLayer(slots[1], photos, slideshow, slideCfg, contentScale, request, previewRequest, requestKey)
    }
}

/**
 * 一个槽的画面。模型只在「这个槽换照片」时才重算，所以正在显示的那张永远不会
 * 因为别的地方重组而重新解码。
 *
 * ## 槽里为什么有两层
 *
 * 原图一张 5~6MB，解码要几百毫秒；快速连按时每次都等它就等于「按下去没反应」。
 * 所以槽里先挂一层预览（mUrl，磁盘命中，几十毫秒就能画）把画面接住，原图到了再
 * **整块替换**掉它——同一张照片、同一取景，只换清晰度，看不出接缝。
 *
 * 不做淡入：任何「两层同时按 alpha 合成」的中间态，都会露出槽自己的黑底（表现为
 * 快速连按时「暗一下」），而且 NAS 转码的 mUrl 与相机原图色调未必一致，亮度混合
 * 本身就会闪。这正是浏览器 LQIP / Coil placeholder 的语义：占位图一直画到成品图
 * 能画为止，然后原子换掉。
 *
 * 「原图」开关关着的时候两层是同一个 URL，这时不画预览层（[dualLayer] = false），
 * 行为与单层完全一致。
 */
@Composable
private fun StageLayer(
    slot: PhotoSlot,
    photos: List<SharedPhoto>,
    slideshow: Boolean,
    slideCfg: SlideShowConfig,
    contentScale: ContentScale,
    request: (SharedPhoto?) -> ImageRequest?,
    previewRequest: (SharedPhoto?) -> ImageRequest?,
    requestKey: Any? = null,
) {
    val photo = slot.photo ?: return
    // 预览层恒为 mUrl，与「原图」开关无关，所以 key 里不带 requestKey——切档时不必重发。
    val previewModel = remember(photo.id) { previewRequest(photo) }
    // model 重建 = 这一层的内容换人了（换图，或用户切了「原图」档）。
    //
    // 「已画出内容」的标记必须**在这里**同步复位，不能像之前那样放到 SideEffect 里事后补：
    // Coil 命中内存缓存时 onSuccess 是同步回调的，实测比 SideEffect 还早——
    //   37.708 onSuccess → loaded = true
    //   37.709 SideEffect 发现「第一次见到这个 model」→ loaded = false
    // 而 onSuccess 一辈子只回调一次，于是这一层从此再也不会画（drawWithContent 要求 loaded），
    // 屏幕上剩下的是垫底那张 mUrl 预览图：不推镜、换图硬切。这就是「幻灯片放大和过渡
    // 都不生效」的全部原因——Ken Burns 一直在跑，只是画在了一个永远不绘制的图层里。
    val model = remember(photo.id, requestKey) {
        // 照片换了：整槽标志归零。只换档位（照片没变）时不动预览层的标记，
        // 否则正在顶着的预览会跟着消失，屏幕黑一下。
        if (slot.marksFor != photo.id) {
            slot.marksFor = photo.id
            slot.resetMarks()
        }
        slot.originalDone = false
        slot.originalLoaded = false
        request(photo)
    }
    if (model == null) {
        // 没有可用地址（极少见）：别让揭幕一直等下去，当成就绪，露出缩略图。
        SideEffect { slot.ready = true }
        return
    }

    // 两层指向同一张图（「原图」关着）就没必要画两遍，否则多一层 1080p 的合成。
    val dual = previewModel != null && previewModel.data.toString() != model.data.toString()
    slot.dualLayer = dual

    // 这里**没有**「原图淡入」动画，是刻意的。
    //
    // 低清占位 → 高清成品的成熟做法（浏览器 LQIP、Coil 的 placeholder 语义）就是
    // 由同一个状态原子替换：占位图一直画到成品图真正能画为止，成品一到就整块换掉。
    // 之前给原图套了个 250ms 的 alpha 淡入，于是必然存在一段「预览已经不画、
    // 原图还是半透明」的中间态；两层之间露出的就是这一层的黑底，快速连按时表现为
    // 「暗一下」。而且 NAS 转码的 mUrl 与相机直出原图色调未必完全一致，做亮度混合
    // 本身就会让画面忽明忽暗。
    //
    // 现在预览层与原图层共用同一个 [PhotoSlot.originalLoaded] 快照状态、条件严格互补：
    // 同一帧里一层停止绘制、另一层开始绘制，任何时刻都恰有一层是满不透明的。
    // 两者取景、contentScale、Ken Burns 缩放完全一致，替换只是「变清楚」，看不出接缝。

    // Ken Burns：这一张自己的推镜。必须 Linear——缓入缓出会看出「起范儿收范儿」，就成了动画片。
    //
    // 不用 tween + animateTo，改成「按帧时钟自算进度」的循环，原因有二：
    //   1. 起跑点要锚在**揭幕**（ready 翻真那一刻）而不是塞图：解码的几百毫秒里偷跑的话，
    //      新图出场时已不在起点，旧图会在淡出结束前先跑到终点「定住」再消失。
    //   2. 用户在菜单里改时长/比例时，配置经 rememberUpdatedState 下一帧就能读到，
    //      进度是 0..1 的无量纲数，按新旧时长折算位置连续——画面不会跳回起点重来。
    val photoIndex = remember(photo.id, photos) { photos.indexOfFirst { it.id == photo.id } }
    val pushIn = (if (photoIndex >= 0) photoIndex else 0) % 2 == 0
    val cfgState = rememberUpdatedState(slideCfg)
    LaunchedEffect(photo.id, slot.ready, slideshow) {
        if (!slideshow) {
            slot.scale = 1f
            return@LaunchedEffect
        }
        // 还没揭幕就不动：换图期间这个槽 alpha=0 看不见，scale 留着旧值无妨。
        if (!slot.ready) return@LaunchedEffect
        var progress = 0f
        var lastNanos = 0L
        while (isActive) {
            // 这一层看不见就一个字都别写：两个槽里永远有一个 alpha=0，
            // 让它继续每帧推进 Ken Burns，等于白烧一路帧回调（电视上是实打实的算力）。
            // 等它重新可见再接着跑，progress 是 0..1 的无量纲数，接得上。
            if (slot.alpha.value <= VISIBLE_EPS) {
                snapshotFlow { slot.alpha.value }.first { it > VISIBLE_EPS }
                lastNanos = 0L
            }
            withFrameNanos { now ->
                if (lastNanos != 0L) {
                    val c = cfgState.value
                    if (c.kenBurns && !photo.isVideo) {
                        val dtMs = (now - lastNanos) / 1_000_000f
                        progress = (progress + dtMs / c.animDuration).coerceIn(0f, 1f)
                    }
                }
                lastNanos = now
            }
            val c = cfgState.value
            if (!c.kenBurns || photo.isVideo) {
                slot.scale = 1f
            } else if (progress < 1f) {
                val (from, to) = c.scaleFor(pushIn)
                slot.scale = from + (to - from) * progress
            } else {
                // 推镜跑完了：这一层现在是静止画面，别再每帧写状态（写了值也一样，
                // 但仍要过一遍失效流程）。等它被下一张顶下去，或者用户改了配置再说。
                // progress **不**重置——它是无量纲进度，改时长档位后按新时长接着走，
                // 重置会让画面弹回起点（这正是之前修过的那个跳变）。
                snapshotFlow {
                    Triple(slot.alpha.value, cfgState.value.animDuration, cfgState.value.kenBurns)
                }.first { (alpha, _, kenBurns) -> alpha <= VISIBLE_EPS || !kenBurns }
                lastNanos = 0L
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // 完全透明时直接不画这一层。**没解出内容时也不画**——那圈黑底盖住的正是
            // 底下的垫底图，切「原图」档的那几百毫秒会变成一整块黑。
            //
            // 两个槽里永远有一个 alpha=0，而它照样占着一层 1080p 的合成：GPU 要把它
            // 的 layer 调出来、按 0 混合、再贴到屏幕上。电视的 GPU 比手机弱一截，
            // 这一层白画的开销在转场那几秒里就是掉帧的来源之一。
            // 跳过的是 drawContent，合成这一步彻底不参与——不是画了再透明。
            .drawWithContent { if (slot.alpha.value > VISIBLE_EPS && slot.loaded) drawContent() }
            .graphicsLayer {
                scaleX = slot.scale
                scaleY = slot.scale
                alpha = slot.alpha.value
            }
            // 黑底跟着这一层的 alpha 一起进退：原比例下它才是「留白区也参与叠化」的主角。
            .background(Color.Black),
    ) {
        val preview: ImageRequest? = if (dual) previewModel else null
        if (preview != null) {
            AsyncImage(
                model = preview,
                contentDescription = null,
                contentScale = contentScale,
                onSuccess = { if (slot.photo?.id == photo.id) slot.onPreviewResult(true) },
                // 预览失败不放行揭幕：再等等原图，别让一张空的预览把画面提前开出来。
                onError = { if (slot.photo?.id == photo.id) slot.onPreviewResult(false) },
                modifier = Modifier
                    .fillMaxSize()
                    // 与下面原图层的条件严格互补，且读的是**同一个**快照状态。
                    // originalLoaded 翻真 → 这一帧预览停止绘制、原图开始绘制；
                    // 不存在「两层都不画」或「两层按 alpha 混合」的中间帧。
                    // 原图加载失败时它继续顶着，画面不会退回黑底。
                    .drawWithContent { if (!slot.originalLoaded) drawContent() },
            )
        }
        AsyncImage(
            model = model,
            contentDescription = null,
            contentScale = contentScale,
            onSuccess = {
                // 回调可能是上一张的（模型刚换过），认一下人。
                if (slot.photo?.id == photo.id) slot.onOriginalResult(true)
            },
            // 失败也放行：撤掉这张，露出（同样失败的）缩略图，总比一直停在上一张强。
            onError = {
                if (slot.photo?.id == photo.id) slot.onOriginalResult(false)
            },
            modifier = Modifier
                .fillMaxSize()
                // 单层：这一层就是唯一的图，直接画。
                // 双层：只有原图真正就绪（onSuccess 已把位图交给 painter）才画，
                // 未就绪时连 drawContent 都不做——由下面那张预览层满不透明地顶住。
                .drawWithContent { if (!dual || slot.originalLoaded) drawContent() },
        )
    }
}

/** 「这一层算不算看得见」的阈值。低于它既不画（见 StageLayer）也不跑推镜。 */
private const val VISIBLE_EPS = 0.001f

/**
 * 揭幕前给原图的宽限期。
 *
 * 预览到了、原图还在路上时先别揭幕，等它这么久：停留够久（预取已生效）的场景下
 * 原图几乎与预览同时命中，这一等能省掉「先 1080p 再换 4K」的中间态，慢速切换完全无感。
 * 快速连按时原图要几百毫秒，超时后照常用预览揭幕，屏幕立刻有反应。
 */
private const val ORIGINAL_GRACE_MS = 100L
