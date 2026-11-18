package com.kijeye.fnshare.ui

import android.os.Build
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.kijeye.fnshare.R
import com.kijeye.fnshare.engine.api.ImageRequestFactory
import kotlinx.coroutines.delay

/**
 * 焦点跟随的沉浸式动态背景。
 *
 * 遥控器在网格上移动时，当前停着的那张照片会在 300ms 后淡入成为整屏背景：
 * 先高斯模糊成色块，再压一层半透明材质和底部渐变，保证前景文字依然可读。
 *
 * 三层职责（对应玻璃拟态）：
 *   1. 背景层   —— 双图层交叉淡化加载 NAS 图片（尺寸锁死 1280×720，防 OOM）；
 *   2. 毛玻璃层 —— blur 模糊 + 材质色 + 底部暗色渐变；
 *   3. 前景层   —— 调用方的内容（网格、玻璃卡片）画在本组件之上。
 *
 * blur 依赖 RenderEffect，Android 12（API 31）以上才生效；更低版本自动降级为
 * 「不模糊 + 更重的暗色压层」，观感仍是沉浸的，只是没有磨砂感。本项目目标设备
 * Android 13，走的是正常分支。
 */
object Backdrop {
    /** 防抖：焦点停留这么久才真正换背景。按住方向键扫一排时不会连环打 NAS。 */
    const val DEBOUNCE_MS = 300L

    /** 双图层交叉淡化的时长。 */
    const val CROSSFADE_MS = 500

    /** 背景图解码尺寸。背景只是色块氛围，绝不按屏幕原生分辨率拉原图。 */
    const val WIDTH = 1280
    const val HEIGHT = 720

    /**
     * 模糊半径。玻璃后面要能隐约看出「有张照片」，但认不出内容——太糊就只剩色块，
     * 看成一张糊掉的壁纸而不是磨砂玻璃。
     * 0.29.5：18dp → 12dp（电视约 21px）。模糊和噪点两层叠加后背景图太不明显，
     * 收一档让焦点那张照片的轮廓能读出来，仍不抢前景文字。
     */
    val BLUR = 12.dp

    /**
     * 材质层浓度。玻璃靠一层**带灰的半透明材质**读出厚度，不是靠把照片压黑。
     * 颜色取 surface（#1A1A1A）而不是 background（#0F0F0F）：后者太黑，压完是黑板不是玻璃。
     */
    const val SCRIM_ALPHA = 0.35f

    /** 低版本没有 blur，把材质层加厚来兜底对比度。 */
    const val FALLBACK_SCRIM_ALPHA = 0.72f

    /** 玻璃上沿那道高光的透明度：太亮会像一条白线，太淡看不见。 */
    const val EDGE_ALPHA = 0.10f
}

/**
 * 焦点背景入口。内部自带 300ms 防抖——[imageUrl] 快速变化时，
 * [LaunchedEffect] 会自动取消上一次还没落地的切换。
 *
 * [factory] 产背景请求（`backdropRequest`，1280×720 锁死在工厂里，构造表达式
 * 在 `engine/impl/CoilImageRequestFactory`，第 7 批接线）。
 */
@Composable
fun FocusBackdrop(
    imageUrl: String?,
    modifier: Modifier = Modifier,
    factory: ImageRequestFactory,
) {
    var settled by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(imageUrl) {
        delay(Backdrop.DEBOUNCE_MS)
        settled = imageUrl
    }
    CrossfadeBackdrop(settled, modifier, factory)
}

/**
 * 双图层交叉淡化。
 *
 * 两个图层轮流当「前面那层」：新图写进背面那层并翻到前面，两层各自做
 * 500ms 的透明度动画，旧图在新图淡入的同时淡出——任何一帧都是两层叠着，
 * 看不到黑场或闪烁。写回同一个 URL 时只翻层、不重新加载（Coil 内存缓存兜底）。
 */
@Composable
private fun CrossfadeBackdrop(
    imageUrl: String?,
    modifier: Modifier = Modifier,
    factory: ImageRequestFactory,
) {
    var urls by remember { mutableStateOf(listOf<String?>(null, null)) }
    var front by remember { mutableIntStateOf(0) }
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(imageUrl) {
        if (imageUrl == null) {
            // 焦点离开网格（切屏、清空）时整体淡出，而不是僵住最后一张。
            visible = false
            return@LaunchedEffect
        }
        visible = true
        val already = urls.indexOf(imageUrl)
        if (already == front) return@LaunchedEffect
        front = if (already >= 0) already else 1 - front
        if (already < 0) {
            urls = urls.toMutableList().also { it[front] = imageUrl }
        }
    }

    Box(modifier) {
        // 两层图片先合成、再统一模糊一次。
        // 高斯模糊是线性算子：blur(α·A + β·B) = α·blur(A) + β·blur(B)，
        // 观感与「两层各自 blur 后再按 alpha 叠加」等价。但交叉淡化的 500ms 里
        // 原来 GPU 要跑两次全屏 28dp RenderEffect，现在只跑一次——网格/相册墙上
        // 每次焦点停留 300ms 就换一次背景，这是浏览链路上最贵的一段重复合成。
        // 材质层与上下渐变留在这个 Box 之外，不受模糊影响。
        Box(
            Modifier
                .fillMaxSize()
                .then(
                    if (Build.VERSION.SDK_INT >= 31) {
                        Modifier.blur(Backdrop.BLUR)
                    } else {
                        Modifier
                    },
                ),
        ) {
            repeat(2) { i ->
                val url = urls[i]
                val alpha by animateFloatAsState(
                    targetValue = if (visible && i == front && url != null) 1f else 0f,
                    animationSpec = tween(Backdrop.CROSSFADE_MS, easing = LinearEasing),
                    label = "backdrop$i",
                )
                if (url != null) {
                    AsyncImage(
                        // size 锁死 1280×720：Coil 按这个目标下采样解码，不碰原图分辨率。
                        // 请求由工厂构造（第 7 批接线），表达式与原来在 UI 侧逐字一致。
                        model = remember(url, factory) { factory.backdropRequest(url) },
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        // 0.29.6：不再降饱和——背景图保持 NAS 里那张照片原本的色彩。
                        // 0.29.3 曾加过饱和度 0.6 的滤镜（说是不抢前景），用户要的是原色，删掉。
                        modifier = Modifier
                            .fillMaxSize()
                            // 完全透明就不画：原来 `alpha > 0f` 是组合期条件，交叉淡化的
                            // 500ms 里每帧重组一层 AsyncImage。挪到绘制期（读进 draw 回调）
                            // 之后动画只重绘图层，不再重组。
                            .drawWithContent { if (alpha > 0f) drawContent() }
                            // 放大 1.12 倍把模糊贴边的发虚黑边推出屏幕外。
                            .graphicsLayer {
                                this.alpha = alpha
                                scaleX = 1.12f
                                scaleY = 1.12f
                            },
                    )
                }
            }
        }

        // 毛玻璃材质层：半透明灰面（surface 而不是更黑的 background），
        // 盖在模糊图上读成一层有厚度的磨砂面，而不是把照片压成黑板。
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    MaterialTheme.colorScheme.surface.copy(
                        alpha = if (Build.VERSION.SDK_INT >= 31) {
                            Backdrop.SCRIM_ALPHA
                        } else {
                            Backdrop.FALLBACK_SCRIM_ALPHA
                        },
                    ),
                ),
        )

        // 磨砂颗粒层：静态噪点图平铺整屏。玻璃的「砂」就来自这层——没有它，
        // 高斯模糊只是一张糊掉的壁纸。噪点 PNG 自带 2%~7% 的黑白点（0.29.5 从
        // 4%~12% 收淡），直接 SrcOver 叠加即可，不用 SoftLight：Compose 兄弟节点间的
        // blend mode 行为不稳定，而低 alpha 的黑白点在任何底色上都只浮出细颗粒，不改整体色调。
        // shader 与 brush 只创建一次，逐帧只是一次全屏 drawRect，不重组、不解码。
        val noiseBitmap = ImageBitmap.imageResource(R.drawable.bg_noise)
        val noiseBrush = remember(noiseBitmap) {
            ShaderBrush(ImageShader(noiseBitmap, TileMode.Repeated, TileMode.Repeated))
        }
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind { drawRect(brush = noiseBrush) },
        )

        // 玻璃上沿高光：1dp 的水平亮线，两端淡出。玻璃之所以读成「一片」而不是
        // 「一张壁纸」，靠的就是这道边缘反光；不做描边是因为整屏背景没有四边可见。
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            Color.Transparent,
                            Color(0xFFFFFFFF).copy(alpha = Backdrop.EDGE_ALPHA),
                            Color.Transparent,
                        ),
                    ),
                ),
        )

        // 顶部暗角：相册名、已加载张数直接压在背景上（不放框），遇到白墙那种亮照片时
        // 纯白标题会糊掉。0~42% 高度内淡出，正好覆盖标题那一条，又看不出边界。
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color(0x4D000000),
                        0.14f to Color(0x33000000),
                        0.42f to Color.Transparent,
                    ),
                ),
        )

        // 底部渐变加深：前景的日期头、相册名都落在下半区，这里保住对比度。
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.55f to Color(0x26000000),
                        1f to Color(0x80000000),
                    ),
                ),
        )
    }
}
