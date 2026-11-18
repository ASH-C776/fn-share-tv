package com.kijeye.fnshare.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp

/**
 * 0.25.0：看图页内翻到的视频，静止 / 起播这两种态的**叠层**。
 *
 * 和 [VideoLoadingOverlay] 的区别只有一条，但这条是全部设计的核心：
 * 这里**没有全屏黑底**。封面（[PhotoStage] 里的 m 档缩略图）始终满不透明地铺在下面，
 * 本文件只往它上面加一个中央图标或一圈转圈。一旦整屏铺黑，按 OK 那一下就是
 * 「封面 → 黑 → 首帧」，正是 0.24.0~0.24.8 反复出现、0.24.9 才删掉的黑帧。
 *
 * 网格直接点开视频不走这里，仍旧用 [VideoLoadingOverlay] 的黑底转圈。
 */

/**
 * 静止态：只留中央播放图标，**不写字**。
 * 0.25.3 按用户要求去掉「按 OK 播放」文案：封面本身已经是「这是一条视频」的提示，
 * 电视三米外那行小字反而和下方状态牌抢注意力。除了图标衬底，屏幕上没有额外暗层。
 */
@Composable
internal fun VideoStillPlayBadge(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(Color(0x99000000)),
            contentAlignment = Alignment.Center,
        ) {
            PlayTriangle(Modifier.size(40.dp))
        }
    }
}

/**
 * 按 OK 之后、首帧没到之前：转圈叠在封面上。
 *
 * 只给转圈围一圈半透明衬底，保证亮封面上也看得清；绝不铺满屏幕。
 * 首帧真到时由 [VideoLoadingState] 的同一个 snapshot state 把封面和转圈一起撤掉，
 * 露出来的直接是播放器的 00:00 画面。
 */
@Composable
internal fun VideoStillSpinner(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(104.dp)
                .clip(CircleShape)
                .background(Color(0x99000000)),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(72.dp),
                color = Color(0xFFE6E6E6),
                trackColor = Color(0x33FFFFFF),
                strokeWidth = 6.dp,
            )
        }
    }
}

/** 不引图标库，直接画一个实心三角；3 米观看距离下比矢量图标更实、更清楚。 */
@Composable
private fun PlayTriangle(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        drawPath(
            path = Path().apply {
                moveTo(w * 0.34f, h * 0.16f)
                lineTo(w * 0.84f, h * 0.5f)
                lineTo(w * 0.34f, h * 0.84f)
                close()
            },
            color = Color.White,
        )
    }
}
