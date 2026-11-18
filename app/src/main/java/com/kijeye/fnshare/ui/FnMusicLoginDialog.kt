package com.kijeye.fnshare.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.kijeye.fnshare.R
import com.kijeye.fnshare.domain.model.FnMusicSignInOutcome

/**
 * 该代码是从哪个原文件提取的：
 * 【新增界面，无函数体被搬动】。表单外壳沿用 `ui/LoginScreen.kt` 的
 * `AccessCodeDialog`（Dialog + Surface + Field + DialogButton + FormGap），
 * 只把字段扩成三个；不复用相册登录页，两者账号体系与失败形态都不同。
 *
 * ⚠️ 2026-11-06 起**不再让用户填服务地址**：同一台 NAS 的相册与飞牛音乐只差
 * 端口/路径，地址由 `defaultFnMusicBaseUrl(settings.host)` 内部推导，弹窗里只读展示。
 * 用户只填「账号 / 密码 / 访问码（可选）」三格。
 *
 * 按钮只留「退出登录」（已登录才有）与「重新登录」（未登录时显示「登录」）两个：
 * 取消按钮去掉，返回键经 [Dialog] 的 `onDismissRequest` 直接收起弹窗。
 * 两枚按钮都是四字文案，配 `DialogButton` 的单行约束，不会折行。
 *
 * 对应的生命周期清理应该在原文件的哪个位置调用：
 * 【无需清理】。弹窗是组合期局部状态（`remember`），关闭即释放；
 * 验证协程归 `AppViewModel.viewModelScope`，由 `onCleared()` 统一取消。
 * 弹窗开关刻意留在 `SettingsScreen` 的局部 `remember`，不进 AppState。
 *
 * 线程约定：全部在主线程组合；网络验证走 `AppEvent.FnMusicSignIn` 由 VM 分派。
 *
 * ⚠️ 访问码是**第 3 个可选字段**而不是二段弹窗：服务端不会「提示需要访问码」，
 * 它直接回一整页 HTML。二段式只会让遥控器多走一轮，且第一段永远显示「连接失败」。
 */
@Composable
fun FnMusicLoginDialog(
    state: AppState,
    onSubmit: (user: String, password: String, code: String) -> Unit,
    onSignOut: () -> Unit,
    onDismissError: () -> Unit,
    onDismiss: () -> Unit,
) {
    var user by remember { mutableStateOf(state.fnMusicSavedUser) }
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf(false) }

    /**
     * 一维焦点位：0~2 是三个输入框（账号 / 密码 / 访问码），3 是「退出登录」
     * （已登录才有），4 是「重新登录」。未登录时 3 没有对应组件，下键要直接跳到 4。
     *
     * ⚠️ 必须自己管：BasicTextField 会把上下键当文本光标移动**吞掉**，
     * Compose 的默认焦点导航根本走不到下一个框（MuMu 实测：连按三次下键焦点
     * 仍在第一个输入框，账号密码访问码全被拼进了地址）。登录页那三个 Field 是既有
     * 实现，这里不动它，只让新弹窗自己可操作。
     */
    val focusTargets = remember { List(FOCUS_COUNT) { FocusRequester() } }
    var cursor by remember { mutableStateOf(0) }
    val loggedIn = state.fnMusicLoggedIn
    fun moveTo(index: Int) {
        cursor = index
        runCatching { focusTargets[index].requestFocus() }
    }

    /** 焦点可能被系统用别的方式挪走，所以每次真实获焦都把一维位同步回来。 */
    fun Modifier.focusAt(index: Int): Modifier = this
        .focusRequester(focusTargets[index])
        .onFocusChanged { if (it.isFocused) cursor = index }

    // 开窗即落焦点到账号框：电视上不用先按方向键找输入框。
    // 同时清掉上一次失败遗留的错误提示，否则重开弹窗会一直挂着旧红字。
    LaunchedEffect(Unit) {
        onDismissError()
        runCatching { focusTargets[0].requestFocus() }
    }

    // 验证成功自动收起。只在「提交过且刚从 pending 回落」时关，
    // 否则「已登录后想重新验证」一开窗就会被立刻关掉。
    LaunchedEffect(state.fnMusicLoginPending) {
        if (submitted && !state.fnMusicLoginPending && state.fnMusicLoginError == null) {
            onDismiss()
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier
                    .width(520.dp)
                    // 出错时多出一行提示，原先会把底部按钮挤出屏幕（MuMu 实测裁掉半截）。
                    // 压缩间距是主修，限高 + 滚动是保险，正常情况下用不到滚动。
                    .heightIn(max = 580.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 36.dp, vertical = 24.dp)
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        val next = when (event.key) {
                            Key.DirectionDown -> when (cursor) {
                                in 0..1 -> cursor + 1
                                // 最后一个输入框下移到按钮行；未登录时没有「退出登录」。
                                2 -> if (loggedIn) FOCUS_SIGN_OUT else FOCUS_SUBMIT
                                else -> null
                            }
                            Key.DirectionUp -> when (cursor) {
                                in 1..2 -> cursor - 1
                                FOCUS_SIGN_OUT, FOCUS_SUBMIT -> 2
                                else -> null
                            }
                            Key.DirectionRight -> when (cursor) {
                                FOCUS_SIGN_OUT -> if (loggedIn) FOCUS_SUBMIT else null
                                else -> null
                            }
                            Key.DirectionLeft -> when (cursor) {
                                FOCUS_SUBMIT -> if (loggedIn) FOCUS_SIGN_OUT else null
                                else -> null
                            }
                            else -> null
                        }
                        // 输入框里的左右键留给文本光标，按钮行的 OK 留给 clickable，都不拦。
                        if (next == null) return@onPreviewKeyEvent false
                        moveTo(next)
                        true
                    },
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.fnm_login_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.fnm_login_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // 地址不再由用户输入，内部用相册 host 推导（同一台 NAS 自动对应）。
                // 这里只读展示，便于排查当前连接目标；推导为空时提示先登录相册。
                val fnBase = state.fnMusicBaseUrl
                if (fnBase.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.fnm_url_hint, fnBase),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.fnm_url_missing),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Field(
                    value = user,
                    onValueChange = { user = it },
                    modifier = Modifier.fillMaxWidth().focusAt(0),
                    placeholder = stringResource(R.string.fnm_user_hint),
                )
                Field(
                    value = password,
                    onValueChange = { password = it },
                    modifier = Modifier.fillMaxWidth().focusAt(1),
                    placeholder = stringResource(R.string.fnm_password_hint),
                    keyboardType = KeyboardType.Password,
                    password = true,
                )
                Field(
                    value = code,
                    onValueChange = { code = it },
                    modifier = Modifier.fillMaxWidth().focusAt(2),
                    placeholder = stringResource(R.string.fnm_code_hint),
                    keyboardType = KeyboardType.Number,
                    password = true,
                )

                val errorText = when (val outcome = state.fnMusicLoginError) {
                    FnMusicSignInOutcome.NeedAccessCode -> stringResource(R.string.fnm_err_code)
                    FnMusicSignInOutcome.BadCredential -> stringResource(R.string.fnm_err_credential)
                    FnMusicSignInOutcome.Network -> stringResource(R.string.fnm_err_network)
                    is FnMusicSignInOutcome.Other -> outcome.detail
                    is FnMusicSignInOutcome.Success, null -> ""
                }
                if (errorText.isNotEmpty()) {
                    Text(
                        text = errorText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    if (loggedIn) {
                        DialogButton(
                            text = stringResource(R.string.fnm_sign_out),
                            danger = true,
                            enabled = !state.fnMusicLoginPending,
                            onClick = {
                                submitted = false
                                onDismissError()
                                onSignOut()
                            },
                            modifier = Modifier.padding(end = 12.dp).focusAt(FOCUS_SIGN_OUT),
                        )
                    }
                    DialogButton(
                        text = stringResource(
                            when {
                                state.fnMusicLoginPending -> R.string.fnm_connecting
                                loggedIn -> R.string.fnm_connect
                                else -> R.string.fnm_connect_first
                            },
                        ),
                        enabled = !state.fnMusicLoginPending && state.fnMusicBaseUrl.isNotEmpty(),
                        onClick = {
                            submitted = true
                            onDismissError()
                            onSubmit(user, password, code)
                        },
                        modifier = Modifier.focusAt(FOCUS_SUBMIT),
                    )
                }
            }
        }
    }
}

private const val FOCUS_COUNT = 5
private const val FOCUS_SIGN_OUT = 3
private const val FOCUS_SUBMIT = 4
