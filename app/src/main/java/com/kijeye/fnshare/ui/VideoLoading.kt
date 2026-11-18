package com.kijeye.fnshare.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** 3 秒还没首帧：静音强制推进播放逼出首帧，**不撤转圈**。取的是电视冷启动拉流量级。 */
internal const val VIDEO_LOADING_TIMEOUT_MS = 3_000L

/** 强制推进后再等这么久仍无首帧：判定片源失败，撤层并给出错误提示。 */
internal const val VIDEO_FORCE_GIVE_UP_MS = 5_000L

/**
 * 视频加载层的门闩（0.24.12 由「按 URL 记」改成「按播放器实例记」）。
 *
 * ## 为什么不能用 URL 当钥匙
 *
 * 旧版 `readyFor: String?` 只记「哪个视频就绪过」。从视频 A 按 ← 到照片、再按 → 回到 A，
 * URL 没变，`readyFor` 还是 A → 不画转圈；但 `key(videoKey)` 已经把旧播放器 dispose、
 * 新建了一个待播态实例，新首帧到来前是**纯黑无提示**，加载期按 OK 还会绕过开局门控
 * 直接出声（M-4）。
 *
 * 现在每次播放器实例重挂都同步 `beginSession()` 推进一个 token，就绪标记绑 token 不绑 URL。
 * 复位必须在 `remember(videoKey, photoId)` 里**同步**完成——0.23.3 的教训：事后用
 * SideEffect 比对会把内存缓存命中时的 `onFirstFrame` 抹掉，变成永不绘制。
 * 旧 token 的首帧回调也会被挡住，不会误撤新实例的加载层。
 *
 * 撤除仍是单个 snapshot state 的条件组合：整块满不透明盖着 / 整块撤掉，无 alpha、无淡入。
 *
 * ## 0.24.12 M-3：超时不再等于「就绪」
 *
 * 旧版 `onTimeout()` 直接 markReady + `controller.play()`：还在缓冲就撤层并出声，
 * 而且撤层那一刻看到的不是 00:00 首帧。现在超时只做「静音强制推进」，
 * 首帧真的画上来才 `onFirstFrame()` 撤层；实在等不到就 `onGiveUp()` 交错误提示接管。
 */
internal class VideoLoadingState {
    private var currentToken = -1

    /** 已经可以给画面的播放器实例 token；-1 = 当前实例还没就绪。 */
    var readyToken by mutableStateOf(-1)
        private set

    /** 已经做过静音强制推进的 token，防止重复 forceStart()。 */
    private var forcedToken = -1

    /** 播放器实例重挂：同步推进 token 并复位，返回本次 token。 */
    fun beginSession(): Int {
        currentToken += 1
        readyToken = -1
        forcedToken = -1
        return currentToken
    }

    /** 首帧真的画上来了：恢复音量 + 撤层由引擎 / UI 各自完成，这里只记事实。 */
    fun onFirstFrame(token: Int) {
        if (token == currentToken) readyToken = token
    }

    /** 超时：只记「已强制推进」，不撤层。返回是否真的该调 forceStart()。 */
    fun onForceStart(token: Int): Boolean {
        if (token != currentToken || forcedToken == token || readyToken == token) return false
        forcedToken = token
        return true
    }

    /** 报错 / 二次超时：放弃等待，撤层交给错误提示。 */
    fun onGiveUp(token: Int) {
        if (token == currentToken) readyToken = token
    }

    fun loadingVisible(token: Int): Boolean = videoLoadingVisible(readyToken, token)
}

/** 该不该画加载层——纯函数，单测直接钉死。 */
internal fun videoLoadingVisible(readyToken: Int, token: Int): Boolean = readyToken != token

/** 超时判定——纯函数，边界值单测钉死。 */
internal fun videoLoadingTimedOut(
    elapsedMs: Long,
    timeoutMs: Long = VIDEO_LOADING_TIMEOUT_MS,
): Boolean = elapsedMs >= timeoutMs

/**
 * 视频首帧没到时的加载层：全屏黑底 + 居中转圈，盖在播放器之上、状态牌和控制栏之下。
 *
 * 3 米观看距离，72dp / 6dp 描边才看得清；不写任何文字，转圈本身就是「在加载」。
 * 这里不放任何 `AsyncImage` / `BitmapPainter`——那正是 0.24.0~0.24.8 被删掉的黑窗来源。
 */
@Composable
internal fun VideoLoadingOverlay(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(Color.Black),
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
