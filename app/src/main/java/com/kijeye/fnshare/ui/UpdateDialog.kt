package com.kijeye.fnshare.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.kijeye.fnshare.domain.model.UpdateInfo

/**
 * 新版本弹窗（0.31.0）。三态：
 * - 确认态：「软件最新版本为 vX」+ 当前版本 + 取消/更新；
 * - 下载态：进度条 + 百分比，按钮隐藏（下载中不接受操作）；
 * - 失败态：「下载失败」，重试/取消。
 *
 * 焦点默认落「取消」——按错代价是不小心下载 18MB，宁可让用户主动按到「更新」。
 * 两枚按钮的左右切换交给 Compose 默认焦点搜索，和设置页的确认弹窗同一套路。
 */
@Composable
fun UpdateDialog(
    info: UpdateInfo,
    currentVersion: String,
    progress: Int?,
    failed: Boolean,
    onDownload: () -> Unit,
    onDismiss: () -> Unit,
) {
    val cancelFocus = remember { FocusRequester() }
    val actionFocus = remember { FocusRequester() }
    val downloading = progress != null && !failed

    LaunchedEffect(Unit) {
        runCatching { cancelFocus.requestFocus() }
    }
    // 失败态回来时把焦点移到「重试」，免得停在刚消失的按钮上。
    LaunchedEffect(failed) {
        if (failed) runCatching { actionFocus.requestFocus() }
    }

    Dialog(onDismissRequest = { if (!downloading) onDismiss() }) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier
                    .width(520.dp)
                    .padding(horizontal = 36.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "发现新版本",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "软件最新版本为 v${info.versionName}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 20.dp),
                )
                Text(
                    text = "当前版本 v$currentVersion",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )

                if (downloading) {
                    Text(
                        text = "正在下载… $progress%",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 24.dp),
                    )
                    LinearProgressIndicator(
                        progress = { (progress ?: 0) / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                    )
                } else if (failed) {
                    Text(
                        text = "下载失败，检查网络后再试",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 24.dp),
                    )
                }

                if (!downloading) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 32.dp),
                        horizontalArrangement = Arrangement.spacedBy(
                            20.dp,
                            Alignment.CenterHorizontally,
                        ),
                    ) {
                        Box(modifier = Modifier.focusRequester(cancelFocus)) {
                            DialogButton(text = "取消", onClick = onDismiss)
                        }
                        Box(modifier = Modifier.focusRequester(actionFocus)) {
                            DialogButton(
                                text = if (failed) "重试" else "更新",
                                onClick = onDownload,
                            )
                        }
                    }
                }
            }
        }
    }
}
