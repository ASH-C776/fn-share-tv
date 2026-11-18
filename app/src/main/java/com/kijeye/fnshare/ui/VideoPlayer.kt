@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.kijeye.fnshare.ui

import android.view.LayoutInflater
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.datasource.DataSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.kijeye.fnshare.R
import com.kijeye.fnshare.engine.api.VideoEngine
import kotlinx.coroutines.delay

/**
 * 兼容层：`ViewerScreen` 里用的状态 / 控制句柄别名，指向 engine/api 的真实类型。
 */
typealias VideoUiState = com.kijeye.fnshare.engine.api.VideoUiState

typealias VideoController = com.kijeye.fnshare.engine.api.VideoController

/**
 * 全屏播一个视频。用 PlayerView 渲染（关掉自带控件、RESIZE_MODE_FIT 自动做黑边），
 * 取流带 AccessToken 头，交给引擎处理。
 *
 * 0.24.12：
 *  - 引擎由 [engineFactory] 注入（来自 `di/AppContainer`），本文件不再 import 实现层，
 *    播放时序可以换假实现在 JVM 上验证（M-6）；
 *  - 轮询与 report 都过 `engine.isActive` 闸门，stop 之后旧屏淡出期间不再有状态回流（M-1）；
 *  - 监听 Activity 的 ON_STOP 让引擎静音暂停，按 Home / 切 HDMI 不再后台续播（M-5）；
 *  - [onError] 把取流 / 解码失败转给外层显示提示，不再黑屏无反馈（M-2）。
 * 0.25.2：渲染壳改从 `res/layout/player_view.xml` inflate，固定 `texture_view`。
 * 默认的 SurfaceView 在独立 window 上渲染、创建时是一块空黑 surface，电视上会顶掉
 * Compose 封面层；TextureView 是普通 View，Z 序确定，封面必然盖得住。
 *
 * 渲染壳提前在 remember 里建好：`AndroidView.factory` 在 layout 阶段才跑、晚于
 * DisposableEffect，提前建 view 才能让 attach 拿到同一个实例。
 * key 只认引擎实例，切视频由外层 `key(video.videoUrl)` 整块重挂；**切勿**把 url /
 * dataSourceFactory 放进 key，否则每次重组都会 dispose + 重新 prepare。
 */
@Composable
fun VideoPlayer(
    url: String,
    dataSourceFactory: DataSource.Factory,
    /** 0.24.12：由组装层注入，UI 不认识具体实现。 */
    engineFactory: () -> VideoEngine,
    controller: androidx.compose.runtime.MutableState<VideoController?>,
    onState: (VideoUiState) -> Unit,
    /** 首帧真的画到 surface。引擎先用它恢复音量 / 开播，UI 再用它撤加载转圈。 */
    onFirstFrame: () -> Unit,
    /** 取流 / 解码失败：参数是 PlaybackException.errorCodeName。 */
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestOnState by rememberUpdatedState(onState)
    val latestOnFirstFrame by rememberUpdatedState(onFirstFrame)
    val latestOnError by rememberUpdatedState(onError)

    // 0.25.2：从 XML inflate 而不是 PlayerView(context)。XML 里把 surface_type 钉成
    // texture_view：默认 SurfaceView 是独立 window 的空黑 surface，电视上会顶掉
    // Compose 封面层，按 OK 挂播放器那一瞬露出黑帧。TextureView 是普通 View，
    // Z 序确定、未出帧时透明。其余配置沿用代码，避免 XML 属性被版本差异改写。
    val playerView = remember {
        // 传一个空 parent 只为让根节点解析出 LayoutParams（避免 inflate(..., null) 的
        // InflateParams 警告）；attachToRoot=false，返回的就是 XML 里的那个 PlayerView。
        val paramsRoot = FrameLayout(context)
        (LayoutInflater.from(context)
            .inflate(R.layout.player_view, paramsRoot, false) as PlayerView).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            isFocusable = false
            isClickable = false
        }
    }
    val engine: VideoEngine = remember { engineFactory() }

    fun report() {
        if (!engine.isActive) return
        latestOnState(engine.snapshot())
    }

    DisposableEffect(engine) {
        engine.attach(
            view = playerView,
            url = url,
            dataSourceFactory = dataSourceFactory,
            onState = { latestOnState(it) },
            onFirstFrame = { latestOnFirstFrame() },
            onError = { latestOnError(it) },
        )
        controller.value = engine.controller
        onDispose {
            controller.value = null
            engine.release()
        }
    }

    // 0.24.12 M-5：Activity 进后台就停，别让视频在桌面 / 别的 App 后面继续出声。
    // 释放仍只走上面那个 onDispose，避免双重 release。
    DisposableEffect(engine, lifecycleOwner) {
        val observer = object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) { engine.onHostStop() }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 播放 / 缓冲中 250ms 报一次进度，停着时降为 1s 打盹。判断由引擎的
    // needsFastPoll() 提供；isActive 闸门保证 stop 之后连轮询也不再回流状态（M-1）。
    LaunchedEffect(engine) {
        while (true) {
            if (engine.isActive && engine.needsFastPoll()) {
                report()
                delay(VIDEO_POLL_MS)
            } else {
                delay(VIDEO_IDLE_POLL_MS)
            }
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { playerView },
    )
}

private const val VIDEO_POLL_MS = 250L

private const val VIDEO_IDLE_POLL_MS = 1_000L
