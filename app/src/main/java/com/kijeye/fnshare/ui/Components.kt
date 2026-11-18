package com.kijeye.fnshare.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kijeye.fnshare.ui.theme.BorderColor

/**
 * 全站共用的圆角。缩略图、相册封面、看图页里那些半透明浮层框都用这一支，
 * 跨页面看过去才是同一种形状语言。要调整就改这一处。
 */
val CardCorner = 12.dp

/*
 * 电视上的焦点规则：焦点靠「一圈亮边 + 轻微放大」表达，不靠背景填色。
 * 3 米外看，填色变化几乎察觉不到，边框和放大才认得出。
 */

/** 焦点边框 + 放大。电视遥控器的唯一反馈就是这个。 */
@Composable
fun focusRing(focused: Boolean): Pair<Float, androidx.compose.ui.graphics.Color> {
    val scale by animateFloatAsState(if (focused) 1.04f else 1f, label = "scale")
    return scale to if (focused) MaterialTheme.colorScheme.primary else BorderColor
}

/**
 * 一个输入框。
 *
 * 标题只在空框时显示，当占位用——点进去开始输，它就消失。电视遥控器上逐字输入本来
 * 就慢，框里再常驻一行标签（或者框外再挂一行标题）只会让人分不清「这是提示还是内容」。
 *
 * 高度和圆角是固定值，不自适应：四个框必须长得一模一样，错开 2dp 在 3 米外就是歪的。
 */
@Composable
fun Field(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    keyboardType: KeyboardType = KeyboardType.Text,
    password: Boolean = false,
    height: Dp = 64.dp,
    radius: Dp = 12.dp,
) {
    var focused by remember { mutableStateOf(false) }
    val (scale, ring) = focusRing(focused)

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.titleMedium.copy(
            color = MaterialTheme.colorScheme.onSurface,
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        interactionSource = remember { MutableInteractionSource() },
        modifier = modifier.scale(scale).onFocusChanged { focused = it.isFocused },
        decorationBox = { inner ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(height)
                    .clip(RoundedCornerShape(radius))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(
                        if (focused) 2.dp else 1.dp,
                        ring,
                        RoundedCornerShape(radius),
                    )
                    .padding(horizontal = 22.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty() && placeholder.isNotEmpty()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                inner()
            }
        },
    )
}

/**
 * 弹层里的按钮。
 *
 * 不用 Material 的 TextButton：它表达焦点只在文字底下加一层 surfaceVariant 底，深色主题里
 * 那层灰几乎和背景同色，遥控器停在哪个按钮上根本看不出来——而弹层里恰恰只有两个按钮，
 * 按错一下就是「退出登录」。
 *
 * 焦点态改成实心亮底 + 深色字，未聚焦态只描边。危险操作用 error 色描边，按到之前就知道。
 */
@Composable
fun DialogButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, label = "dialogButton")

    Box(
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(10.dp))
            .background(
                when {
                    !enabled -> MaterialTheme.colorScheme.surface
                    focused -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.surface
                },
            )
            .border(
                if (focused) 3.dp else 2.dp,
                when {
                    focused -> MaterialTheme.colorScheme.primary
                    danger -> MaterialTheme.colorScheme.error
                    else -> BorderColor
                },
                RoundedCornerShape(10.dp),
            )
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 32.dp, vertical = 14.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = when {
                !enabled -> MaterialTheme.colorScheme.onSurfaceVariant
                focused -> MaterialTheme.colorScheme.onPrimary
                danger -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurface
            },
            // 按钮文案一律单行：电视上两枚按钮并排时，折行会把高度撑得参差。
            maxLines = 1,
            softWrap = false,
        )
    }
}
