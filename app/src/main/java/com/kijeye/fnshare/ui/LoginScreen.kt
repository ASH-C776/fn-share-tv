package com.kijeye.fnshare.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.kijeye.fnshare.R

/** 表单里的统一间距：标题、输入框和「连接」之间只有这一个距离值。 */
private val FormGap = 32.dp

/**
 * 只在这台电视第一次开机时出现。
 *
 * 左右分栏，比例取黄金分割（左 0.382 / 右 0.618），左短右长，中间不画分割线——
 * 两个色块之间那条线在深色页面上只会显得像表单工具。左边只放应用名和一句话，
 * 右边是表单：视线从左到右正好是「这是什么 → 怎么进去」。
 */
@Composable
fun LoginScreen(
    state: AppState,
    onSignIn: (host: String, user: String, password: String) -> Unit,
    onSubmitAccessCode: (String) -> Unit,
    onDismissAccessCode: () -> Unit,
) {
    // 地址、账号、密码都回填上次的：电视遥控器上一个字符一个字符敲 IP，
    // 是这个应用最难受的一步，能免则免。密码同样默认保存——这台电视就是家里那台。
    var host by remember { mutableStateOf(state.lastHost) }
    var user by remember { mutableStateOf(state.lastUser) }
    var password by remember { mutableStateOf(state.lastPassword) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 88.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 左：品牌区。只有现有的名字和副标题，不放别的。
            Column(
                modifier = Modifier.weight(0.382f),
                horizontalAlignment = Alignment.Start,
            ) {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = stringResource(R.string.login_subtitle),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 20.dp),
                )
            }

            // 右：登录表单。0.618 的长边正够一行放下副标题同宽的输入框。
            Column(
                modifier = Modifier
                    .weight(0.618f)
                    .padding(start = 72.dp),
                verticalArrangement = Arrangement.spacedBy(FormGap),
            ) {
                Field(
                    value = host,
                    onValueChange = { host = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = stringResource(R.string.login_host_hint),
                )
                Field(
                    value = user,
                    onValueChange = { user = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = stringResource(R.string.login_user),
                )
                Field(
                    value = password,
                    onValueChange = { password = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = stringResource(R.string.login_password),
                    password = true,
                )

                if (state.error != null) {
                    Text(
                        text = state.error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                // 靠右、不占满：占满一行会让它看起来像是「和输入框同一类东西」。
                // 间距和上面的输入框走同一个 FormGap，不再额外加。
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    ConnectButton(
                        text = if (state.albumsLoading) {
                            stringResource(R.string.login_connecting)
                        } else {
                            stringResource(R.string.login_connect)
                        },
                        enabled = !state.albumsLoading,
                        onClick = { onSignIn(host, user, password) },
                    )
                }
            }
        }
    }

    // 访问码不占表单一格：NAS 开了这道门才弹出来，绝大多数家庭一辈子见不到它。
    if (state.needAccessCode) {
        AccessCodeDialog(
            loading = state.albumsLoading,
            error = state.error,
            onConfirm = onSubmitAccessCode,
            onDismiss = onDismissAccessCode,
        )
    }
}

/**
 * 访问码弹窗。
 *
 * 参考旧项目的做法：登录信息照常填，连不上是因为门开着时，才把码要一次。
 * 确认后带着码重新登；码错了弹窗留着让人改，别的错（断网之类）收起弹窗回登录页。
 */
@Composable
private fun AccessCodeDialog(
    loading: Boolean,
    error: String?,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var code by remember { mutableStateOf("") }
    // 一开窗就把焦点放进输入框：遥控器上按一下就能输，不用先导航到框里。
    val codeFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { codeFocus.requestFocus() }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier
                    .width(440.dp)
                    .padding(horizontal = 36.dp, vertical = 32.dp),
                verticalArrangement = Arrangement.spacedBy(FormGap / 2),
            ) {
                Text(
                    text = stringResource(R.string.login_code_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.login_code_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Field(
                    value = code,
                    onValueChange = { code = it },
                    modifier = Modifier.fillMaxWidth().focusRequester(codeFocus),
                    placeholder = stringResource(R.string.login_code_hint),
                    keyboardType = KeyboardType.Number,
                    password = true,
                )
                if (error != null) {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    DialogButton(
                        text = stringResource(R.string.wall_cancel),
                        onClick = onDismiss,
                        modifier = Modifier.padding(end = 16.dp),
                    )
                    DialogButton(
                        text = stringResource(R.string.login_code_confirm),
                        onClick = { onConfirm(code) },
                        enabled = !loading,
                    )
                }
            }
        }
    }
}

/**
 * 连接按钮。
 *
 * 浅色实心，跟一排深色输入框拉开距离——它是这一屏唯一会「往下走」的动作。
 * 焦点态再加一圈深色描边：白底上加深色边，比在深色底上加白边显眼得多。
 */
@Composable
private fun ConnectButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, label = "connect")
    // 颜色一律从主题的语义槽里取，不写死色值：换配色时这一屏不会漏掉。
    // 按钮是「深色页上的一块浅色」，所以用 primary 铺底、background 写字（深浅对调）。
    val colors = MaterialTheme.colorScheme

    Box(
        modifier = Modifier
            .scale(scale)
            .width(176.dp)
            .height(60.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                when {
                    !enabled -> colors.surfaceVariant
                    focused -> Color.White
                    else -> colors.primary
                },
            )
            .border(
                if (focused) 3.dp else 0.dp,
                colors.onBackground,
                RoundedCornerShape(12.dp),
            )
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            color = if (enabled) colors.background else colors.onSurfaceVariant,
        )
    }
}
