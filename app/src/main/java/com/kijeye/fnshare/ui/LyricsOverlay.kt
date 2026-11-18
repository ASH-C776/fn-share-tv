package com.kijeye.fnshare.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kijeye.fnshare.R
import com.kijeye.fnshare.data.lyrics.LyricsDoc

/**
 * 幻灯片背景乐的歌词浮层：屏幕右侧、垂直居中，两列**竖排**文字（字从上往下读）。
 *
 * 排列（从左到右）：当前句 · 下一句。当前句最大最亮，下一句小一号、半透明。
 * - 两列**各自**在屏幕垂直方向居中（不是逐级下移）；
 * - 列与列之间的空隙完全相等（[COLUMN_GAP]），最右列到屏幕右边缘的距离由调用方给的
 *   `padding(end = 40.dp)` 决定——比列距略大 12dp，留给电视 overscan 的裁切余量；
 * - 父级是 CenterEnd 右对齐、最右列位置固定，所以去掉原来的「上一句」列之后，
 *   剩下两列在屏幕上的位置与三列时完全一致，只是左侧留白变大；别补回第三列。
 * - 每列的盒宽固定为一个字宽（`fontSize.toDp()`），所以某列只有半角字符、或整列缺席时，
 *   列距都不会被挤动。
 *
 * 竖排的实现：Compose 没有 CSS 的 `writing-mode: vertical-rl`，这里用「一个字一行」
 * 的 [Text]（字符间插 `\n`）凑出竖排。相比 Canvas 逐字 drawText，它保留了 Compose 的
 * 字体/字重/阴影/抗锯齿，也不用自己算基线；代价是每个字独占一个行高，所以行高按
 * [LINE_HEIGHT] 压到 1.15 倍字号。字数多到会顶穿屏幕时，行距再按
 * [MAX_COLUMN_FRACTION] 收窄（只压行距，字号和盒宽一动不动）。
 *
 * 换行动效是**整列淡入**（曾试过逐字点亮 + 错峰，实测节奏赶不上换句速度，
 * 回归简单方案）：换句时旧字消失、新字淡入——当前句 [CURRENT_ANIM_MS] 内淡到全亮，
 * 下一句 [NEXT_ANIM_MS] 内淡到 0.7f，都只压 [graphicsLayer] 的 alpha。
 *
 * 设计铁律（全部来自项目的既有约定）：
 * - **只动 [graphicsLayer] 的 alpha**——纯合成变换，零 requestLayout，任何动画都绝不
 *   触碰测量/布局，幻灯片那边的 Ken Burns 不受牵连。竖排下也不做位移：整列沿自身轴
 *   滑动会和邻列的静止位置对不上，看着像抽搐；
 * - 本组件是**纯渲染器**：没有轮询、没有播放器依赖，doc 和 index 都是外部（ViewModel）
 *   算好的低频值——100ms 的轮询只写 ViewModel 里那个「句下标」，这里只在换行时重组；
 * - 没词（doc 为 null）或还没唱到第一句（index < 0）时**整个不挂载**，不画任何东西——
 *   没有歌词文件的歌安静退场，不占屏也不闪。
 *
 * 文字不用背景框：三米外的电视上，半透明底色块比文字本身还抢眼，改用阴影保证
 * 亮背景下的可读性（Shadow 只挂在当前句上，下一句本来就半透明、无需再描）。
 */

/**
 * 歌词字体：**FN Lyrics Kai** —— 霞鹜文楷 Lite（LXGW WenKai Lite Regular, v1.530）的
 * GB2312 子集，SIL OFL 1.1 授权，许可证全文见 `assets/licenses/OFL-FN-Lyrics-Kai.txt`。
 *
 * 打包的是**子集**：原文件 25694 个码点 / 14.0MB，这里只留 GB2312 全集（6763 汉字）
 * + ASCII + 中文标点共 7553 字符，压到 3.46MB（APK 内 deflate 后约 2.0MB）。歌词是
 * 流行歌曲用字，GB2312 覆盖现代汉语 99% 以上；万一撞上生僻字，Android 的 Typeface
 * 会带着 fallback 链自动回落到系统字体渲染那一个字——不会出现豆腐块，只是字形略有差异。
 *
 * 为什么叫「FN Lyrics Kai」而不叫原名：OFL 的 Reserved Font Name 条款规定修改版
 * （子集化属于修改）不得继续使用保留名，上游声明保留的是 `霞鹜 / 霞鶩 / 落霞孤鹜 /
 * 落霞孤鶩 / LXGW`。字体内部 family/full/PostScript 名已一并改掉，copyright 与
 * OFL 声明原样留在 name 表里。
 *
 * 为什么是楷体而不是系统黑体：竖排单列长句在楷体下笔画有粗细起收，字与字的呼吸感
 * 更好；黑体竖排容易糊成一根等宽黑柱（尤其 3 米外看电视）。
 */
private val LyricsFontFamily = FontFamily(Font(R.font.fn_lyrics_kai, FontWeight.Normal))

/** 当前行：暖白。 */
private val LyricsCurrentColor = Color(0xFFFFF3E0)

/** 下一句：同样色系，最终 alpha 由 graphicsLayer 压到 [NEXT_ALPHA]。 */
private val LyricsDimColor = Color(0xFFFFF6E6)

/** 列间距。28dp ≈ 1.2 个字宽：比一个字宽稍松，两列才像一组，又不至于挤成一片。 */
private val COLUMN_GAP = 28.dp

/** 单列最多几个字（含末位的省略号）。再长就把整列拉满一屏高，喧宾夺主。 */
private const val MAX_CELLS = 14

/** 行高 = 字号 × 该系数。竖排下字形本身就是一个满 em 的方块，1.15 已足够不粘连。 */
private const val LINE_HEIGHT = 1.15f

/** 单列最多占屏高的比例。超了只压行距（不压字号），见 [LyricColumn]。 */
private const val MAX_COLUMN_FRACTION = 0.84f

/** 当前句字号。 */
private val CURRENT_SIZE = 34.sp

/** 下一句字号。 */
private val SIDE_SIZE = 22.sp

/** 当前句淡入：0.18.3 定稿的 280ms / 最终全亮。这次改两列**一点没动**，别顺手统一。 */
private const val CURRENT_ANIM_MS = 280
private const val CURRENT_ALPHA = 1f

/**
 * 下一句淡入：0.22.0 按用户要求「放慢 + 提亮」，从 280ms / 0.5f 改为 420ms / 0.7f。
 * 仍是整列淡入、只压 alpha；不放慢当前句，也不加位移/错峰。
 */
private const val NEXT_ANIM_MS = 420
private const val NEXT_ALPHA = 0.7f

@Composable
fun LyricsOverlay(
    doc: LyricsDoc?,
    index: Int,
    modifier: Modifier = Modifier,
) {
    if (doc == null || index < 0 || index >= doc.lines.size) return
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(COLUMN_GAP),
        // 两列在 Row 里各按自己的高度居中 → 都居中于同一条屏幕中线。
        // 右对齐(CenterEnd) + 固定 40dp 下最右列位置固定 → 当前句列位置固定；
        // 删掉原「上一句」列只是让左侧留白变大，两列屏幕坐标与三列时完全一致。
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LyricColumn(doc, index, current = true)
        LyricColumn(doc, index + 1, current = false)
    }
}

@Composable
private fun LyricColumn(doc: LyricsDoc, lineIndex: Int, current: Boolean) {
    val size = if (current) CURRENT_SIZE else SIDE_SIZE
    val density = LocalDensity.current
    // 盒宽 = 一个字的宽度。sp→dp 走 Density，fontScale 变大时盒宽跟着变，不会裁字。
    val cell = with(density) { size.toDp() }
    val line = doc.lines.getOrNull(lineIndex)
    if (line == null) {
        // 最后一句之后没有「下一句」：占位不画字。
        // 必须留同宽——否则唱到最后一句时当前句会横移一列（右对齐下贴回右边缘），
        // 破坏「两列位置不变」。Spacer + 下面这个 return 一个字都别删。
        Spacer(Modifier.width(cell))
        return
    }
    val cells = splitCells(line.text)
    // 行高默认 1.15 倍字号；但一列字数一多就会顶穿屏幕——同一份代码要能落在
    // 720dp（240dpi 的 1080p）和 540dp（320dpi 的 1080p）两种电视上，
    // 后者光是 14 个字 × 1.15 × 34sp 就已经 547dp，直接出血。所以这里按屏高收一道：
    // 最多占 [MAX_COLUMN_FRACTION] 的屏高，超了只压行距、不压字号。
    // **绝不能改字号**——盒宽跟着字号走，一改宽度邻列就会左右平移，看着像抽动。
    val screenH = LocalConfiguration.current.screenHeightDp.dp
    // 字数兜个底：空串会除出 Infinity，行高变 NaN 后 Text 什么都不画。
    val fitLine = screenH * MAX_COLUMN_FRACTION / cells.size.coerceAtLeast(1)
    // 换算在 dp 上做：TextUnit 没有实现 Comparable，minOf 只认 Dp。
    val lineHeight = with(density) {
        minOf((size * LINE_HEIGHT).toDp(), fitLine).toSp()
    }
    // key 是句下标：列不变不重启（零重组代价），换行时重新淡入。
    val progress = remember(lineIndex, current) { Animatable(0f) }
    LaunchedEffect(lineIndex, current) {
        progress.animateTo(
            1f,
            tween(if (current) CURRENT_ANIM_MS else NEXT_ANIM_MS, easing = FastOutSlowInEasing),
        )
    }
    Text(
        text = cells.joinToString("\n"),
        textAlign = TextAlign.Center,
        style = baseLine(current, size, lineHeight),
        // 全部动效只落在这一层：alpha 淡入，GPU 合成，不碰布局。
        modifier = Modifier
            .width(cell)
            .graphicsLayer {
                alpha = (if (current) CURRENT_ALPHA else NEXT_ALPHA) * progress.value
            },
    )
}

/**
 * 一句词 → 一个字一格的列表。超长句截到 [MAX_CELLS] 个字并以省略号收尾：
 * 真实的 KTV 歌词里有「作词:… 作曲:… 编曲:…」这种塞满一行的元信息行（解析层已拦，
 * 这里是第二道闸），也有把一整段塞进一行的文件——不截的话一列能顶到屏幕上下边缘。
 *
 * 按码点切分而不是 `toCharArray()`：后者会把 emoji 这类代理对劈成两半，屏幕上就是两个
 * 方块问号。
 */
private fun splitCells(text: String): List<String> {
    val cells = ArrayList<String>(text.length)
    var i = 0
    while (i < text.length) {
        val cp = text.codePointAt(i)
        val n = Character.charCount(cp)
        cells += text.substring(i, i + n)
        i += n
    }
    return if (cells.size > MAX_CELLS) cells.take(MAX_CELLS - 1) + "…" else cells
}

private fun baseLine(current: Boolean, size: TextUnit, lineHeight: TextUnit): TextStyle {
    val style = TextStyle(
        fontFamily = LyricsFontFamily,
        fontSize = size,
        lineHeight = lineHeight,
        color = if (current) LyricsCurrentColor else LyricsDimColor,
        textAlign = TextAlign.Center,
    )
    if (!current) return style
    // 当前行加落影：三米外亮背景（雪景、白墙照片）上纯文字会糊掉，阴影替代底色框。
    return style.copy(
        shadow = Shadow(
            color = Color.Black.copy(alpha = 0.6f),
            offset = androidx.compose.ui.geometry.Offset(0f, 2f),
            blurRadius = 8f,
        ),
    )
}
