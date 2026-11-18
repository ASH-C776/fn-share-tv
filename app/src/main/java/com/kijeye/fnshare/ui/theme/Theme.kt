package com.kijeye.fnshare.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp

/*
 * 只有一套配色：电视在客厅的暗环境里，浅色主题在 3 米外看是块发光板。
 * 五个灰阶 + 一个错误色就够，彩色留给照片本身。
 */
private val DarkColors = darkColorScheme(
    primary = Color(0xFFE6E6E6),
    onPrimary = Color(0xFF101010),
    background = Color(0xFF0F0F0F),
    onBackground = Color(0xFFEDEDED),
    surface = Color(0xFF1A1A1A),
    onSurface = Color(0xFFEDEDED),
    surfaceVariant = Color(0xFF2A2A2A),
    // 次要文字。9A9A9A 对背景约 6.7:1，配大字够用；但说明小字在客厅暗环境里偏灰。
    // 提到 B0B0B0（约 8.9:1）拉开一档，又不至于和正文混淆层次。
    onSurfaceVariant = Color(0xFFB0B0B0),
    outline = Color(0xFF3D3D3D),
    error = Color(0xFFFFB4B4),
)

/** 边框色在 Material 的配色槽里没有位置，单独给一个名字，免得各处硬编码。 */
val BorderColor = Color(0xFF3D3D3D)

/**
 * 电视专用字号。**不能沿用 Material 的手机默认值**。
 *
 * 手机默认的 bodySmall 是 12sp、bodyMedium 14sp，那是按「手持 30cm」定的。
 * 电视在客厅里通常 3 米外看，同样的 12sp 落在视网膜上只有手机的 1/10 大小，
 * 说明文字几乎读不出来。这里把每一档整体放大一档：
 *
 *   名称            默认   电视   典型用途
 *   displaySmall    36 →   44    登录页大标题
 *   headlineSmall   24 →   30    页面标题、相册名
 *   titleLarge      22 →   26    弹窗里的强调行
 *   titleMedium     16 →   22    设置行标题、按钮文字、值胶囊
 *   titleSmall      14 →   18    日期/年龄头、播放小按钮
 *   bodyLarge       16 →   20    登录副标题
 *   bodyMedium      14 →   18    正文、错误提示
 *   bodySmall       12 →   16    说明小字、账号行、已加载张数
 *   labelLarge      14 →   18    分组标签、封面上的张数
 *   labelMedium     12 →   18    音乐弹窗工具条的标签 / 状态行
 *   labelSmall      11 →   16    音乐弹窗副标题、曲目时长、面包屑、错误提示
 *
 * labelSmall / labelMedium 这两档以前漏了：音乐弹窗恰好全用它们，结果整个弹窗的
 * 说明文字退回 Material 的手机默认值 11/12sp，比同屏标题小一大截，客厅里读不出。
 * 行高按字号的约 1.3~1.35 倍给，避免大字号挤在一起。
 */
private val TvTypography = Typography(
    displaySmall = TextStyle(fontSize = 44.sp, lineHeight = 58.sp),
    headlineSmall = TextStyle(fontSize = 30.sp, lineHeight = 40.sp),
    titleLarge = TextStyle(fontSize = 26.sp, lineHeight = 34.sp),
    titleMedium = TextStyle(fontSize = 22.sp, lineHeight = 30.sp),
    titleSmall = TextStyle(fontSize = 18.sp, lineHeight = 26.sp),
    bodyLarge = TextStyle(fontSize = 20.sp, lineHeight = 28.sp),
    bodyMedium = TextStyle(fontSize = 18.sp, lineHeight = 26.sp),
    bodySmall = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    labelLarge = TextStyle(fontSize = 18.sp, lineHeight = 26.sp),
    labelMedium = TextStyle(fontSize = 18.sp, lineHeight = 26.sp),
    labelSmall = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
)

/**
 * 全 App 的强调色（primary）从这里注入。
 *
 * 之前 primary 写死成近白 `#E6E6E6`，所有「聚焦 / 选中高亮」都长一个样。
 * 现在设置页「焦点框颜色」那一项会被当成 primary 喂进来——焦点框、设置页左竖条、
 * 搜索光标、看大图选中点，全部统一成用户选的那一色。
 * 没传时回落近白，和旧版观感一致。
 *
 * 注意只动 primary，背景/表面/文字那套深色灰阶不动；onPrimary 仍是近黑，
 * 因为 5 种可选色都很亮，深色内容压在亮底上对比足够。
 */
@Composable
fun FnShareTheme(primaryColor: Color = Color(0xFFE6E6E6), content: @Composable () -> Unit) {
    val colors = DarkColors.copy(primary = primaryColor)
    MaterialTheme(colorScheme = colors, typography = TvTypography, content = content)
}
