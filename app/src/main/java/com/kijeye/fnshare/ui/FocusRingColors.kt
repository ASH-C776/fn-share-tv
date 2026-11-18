package com.kijeye.fnshare.ui

import androidx.compose.ui.graphics.Color
import android.graphics.Color as AndroidColor

/**
 * 焦点框（卡片获得焦点时内侧那一圈描边）的可选颜色。
 *
 * 做 5 种：默认纯白，加 4 个在深色底上既够亮、又能从照片里「跳」出来的彩色。
 * 之前还考虑过红 / 粉 / 绿，但红、粉在照片里太常见、绿又和青绿容易混，按需求砍掉了。
 * 名字统一两个字——设置页里这行排下来、和别的两字值胶囊对齐才好看。
 */
data class FocusRingColor(
    /** 落盘用的十六进制，带 alpha（`#AARRGGBB`），和 [parseRingColor] 对应。 */
    val hex: String,
    /** 设置页显示的两字名。 */
    val label: String,
    val color: Color,
)

val FOCUS_RING_COLORS = listOf(
    FocusRingColor("#FFFFFFFF", "纯白", Color(0xFFFFFFFF)),
    FocusRingColor("#FF38BDF8", "天蓝", Color(0xFF38BDF8)),
    FocusRingColor("#FF2DD4BF", "青绿", Color(0xFF2DD4BF)),
    FocusRingColor("#FFFB923C", "暖橙", Color(0xFFFB923C)),
    FocusRingColor("#FFA78BFA", "雅紫", Color(0xFFA78BFA)),
)

/** 把落盘的十六进制还原成 Compose 颜色；格式不对就回退纯白，绝不崩。 */
fun parseRingColor(hex: String): Color =
    runCatching { Color(AndroidColor.parseColor(hex)) }.getOrDefault(Color.White)
