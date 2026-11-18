@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.kijeye.fnshare.engine.impl

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.kijeye.fnshare.engine.api.VideoActivity
import com.kijeye.fnshare.engine.api.VideoController
import com.kijeye.fnshare.engine.api.VideoEngine
import com.kijeye.fnshare.engine.api.VideoFrameHandoff
import com.kijeye.fnshare.engine.api.VideoFrameHandoffAction
import com.kijeye.fnshare.engine.api.VideoStartAction
import com.kijeye.fnshare.engine.api.VideoStartGate
import com.kijeye.fnshare.engine.api.VideoUiState
import com.kijeye.fnshare.engine.api.videoIsPlaying

/**
 * Media3 ExoPlayer 实现。由 `di/AppContainer.videoEngine()` 产出，UI 只拿到 [VideoEngine] 接口。
 *
 * 开局（0.24.10 方案 A）：`playWhenReady=false` → 首次 READY 主动 `seekTo(0)` 把 00:00 帧
 * 推上 surface → `onRenderedFirstFrame` 才 `play()`。加载层盖着期间不出声、时间轴不走。
 * 3 秒还没首帧时 UI 调 `VideoController.forceStart()` 静音推进，**不撤层**；首帧真到了
 * 才恢复音量并转发给 UI 撤层（0.24.12 修法，见 `VideoController.forceStart`）。
 *
 * 0.25.2：`onRenderedFirstFrame` 只代表 buffer 交给了 surface，不代表像素已经被
 * SurfaceFlinger 合成上屏（电视还有升频 / 画质后处理）。旧写法在这一刻同步
 * play + unmute + 撤封面，于是「缩略图 → 黑帧 → 声音先到 → 画面后到」。现在整个
 * 交接挪到**下一次 vsync**，由 [VideoFrameHandoff] 统一放行：封面一直盖到像素上屏，
 * 开播、恢复音量、撤封面同刻发生。渲染壳同时换成 TextureView（见 `player_view.xml`）。
 *
 * 停止（0.24.11 / 0.24.12）：`stop()` 立刻取消门控、断开全部回调、静音 + `p.stop()`；
 * `release()` 再解绑 PlayerView、摘监听、release。两条路径幂等。
 * [VideoActivity] 一旦停止，`report()` 与 `needsFastPoll()` 都不再产生任何状态回流——
 * 旧屏淡出的 160ms 里不会再闪出「暂停」牌（0.24.12 M-1）。
 */
class ExoVideoEngine(
    private val context: Context,
) : VideoEngine {

    private var player: ExoPlayer? = null
    private var listener: Player.Listener? = null
    /** 渲染壳要留到 release 时解绑：只 release 播放器，PlayerView 仍握着已释放的实例。 */
    private var attachedView: PlayerView? = null
    private var latestOnState: ((VideoUiState) -> Unit)? = null
    private var latestOnFirstFrame: (() -> Unit)? = null
    private var latestOnError: ((String) -> Unit)? = null

    /** 开局门控：每个播放器实例一份，切视频时由外层 key(videoUrl) 整块重挂。 */
    private var startGate = VideoStartGate()

    private var _controller: VideoController? = null
    override val controller: VideoController? get() = _controller

    private val activity = VideoActivity()
    override val isActive: Boolean get() = activity.isActive

    /** 任何静音（强制推进 / 进后台 / 退出）都记在这里，恢复时统一置回 1f。 */
    private var muted = false

    /** 取流 / 解码失败：之后 play、forceStart、首帧回调都不许再把播放器点着。 */
    private var failed = false

    /** 0.25.2：首帧交接栅栏，每个播放器实例一份；停止 / 失败时连 vsync 回调一起丢弃。 */
    private val frameHandoff = VideoFrameHandoff()
    private var frameCallback: Choreographer.FrameCallback? = null
    /** ON_STOP 之后为 true：栅栏只撤层、不点播；人按 OK 时复位。 */
    private var hostStopped = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun mute() {
        player?.setVolume(0f)
        muted = true
    }

    private fun unmute() {
        if (!muted) return
        player?.setVolume(1f)
        muted = false
    }

    /** ExoPlayer 事件本就在构建 player 的主线程；兜一层，保证 Choreographer 拿主线程 Looper。 */
    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(Runnable { block() })
        }
    }

    private fun cancelFrameCallback() {
        val cb = frameCallback ?: return
        frameCallback = null
        onMain { Choreographer.getInstance().removeFrameCallback(cb) }
    }

    /** stop() / release() / 解码失败：彻底丢弃待交接，退出后不再补一次开播或撤层。 */
    private fun discardHandoff() {
        cancelFrameCallback()
        frameHandoff.discard()
    }

    /** 首帧只登记一次，投递下一帧 vsync；绝不在这里 play / unmute / 通知 UI。 */
    private fun noteRenderedFirstFrame() {
        when (frameHandoff.noteFirstFrame(isActive, failed, startGate.isCancelled)) {
            VideoFrameHandoffAction.SCHEDULE_VSYNC -> {
                cancelFrameCallback()
                val cb = Choreographer.FrameCallback { deliverFrameHandoff() }
                frameCallback = cb
                Choreographer.getInstance().postFrameCallback(cb)
            }
            else -> Unit
        }
    }

    /** 统一交接点：已经过一次合成，play / 恢复音量 / 上报 / 撤封面同刻发生。 */
    private fun deliverFrameHandoff() {
        frameCallback = null
        // 闸门在交接这一刻重新查：首帧登记后可能已经 stop / 报错 / 进后台。
        when (frameHandoff.onVsync(isActive, failed, startGate.isCancelled, hostStopped)) {
            VideoFrameHandoffAction.HANDOFF_PLAY -> {
                if (startGate.onFirstFrame() == VideoStartAction.START_PLAYBACK) player?.play()
                unmute()
                report()
                latestOnFirstFrame?.invoke()
            }
            VideoFrameHandoffAction.HANDOFF_PAUSED -> {
                // 进后台：只把封面换成已经渲染的静止首帧，绝不点播 / 恢复音量。
                // 不整块丢弃待交接，否则首帧事件不会重来，回前台封面会永远撤不掉。
                report()
                latestOnFirstFrame?.invoke()
            }
            else -> Unit
        }
    }

    override fun attach(
        view: PlayerView,
        url: String,
        dataSourceFactory: DataSource.Factory,
        onState: (VideoUiState) -> Unit,
        onFirstFrame: () -> Unit,
        onError: (String) -> Unit,
    ) {
        // 0.24.12：同一实例二次 attach 会泄漏上一个 ExoPlayer。当前靠
        // DisposableEffect(engine) 保证只调一次，这里把契约本身也守住。
        check(player == null) { "VideoEngine.attach() 只能调用一次；release 后请换一个新实例" }

        latestOnState = onState
        latestOnFirstFrame = onFirstFrame
        latestOnError = onError
        startGate = VideoStartGate()
        failed = false
        muted = false
        frameHandoff.reset()
        hostStopped = false
        cancelFrameCallback()
        activity.onAttach()

        val p = ExoPlayer.Builder(context).build()
        player = p
        attachedView = view
        view.player = p

        // 0.24.12 M-5：补音频焦点。别的 App / 系统出声时能自动让路，按 Home 后
        // 不再独占音频继续播。此前只有音乐播放器设了，视频这一路漏了。
        p.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            true,
        )

        val l = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                // 0.26.4：真实播放一上来就记下，之后乐观上报功成身退。少这一句，
                // 按 OK 暂停时 UI 仍会以为「在播」，暂停牌不亮、闲置保护也不启动。
                if (isPlaying) startGate.onActuallyPlaying()
                report()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                // 待播态的首帧不一定自己来：首次 READY 主动 seek(0) 把 00:00 帧推上
                // surface。播放中缓冲结束也会报 READY，门控保证只做这一次、开播后不再做。
                if (playbackState == Player.STATE_READY &&
                    startGate.onPlaybackReady(p.playWhenReady) == VideoStartAction.SEEK_TO_START
                ) {
                    p.seekTo(0)
                }
                report()
            }

            override fun onPlaybackParametersChanged(params: PlaybackParameters) { report() }

            /**
             * 0.25.2：首帧只登记，真正的交接统一交给下一次 vsync 的 [deliverFrameHandoff]。
             * 这里一旦同步 play / unmute / 通知 UI，电视上封面会先撤、声音会先出，
             * 而视频层还没被合成上屏 —— 就是「缩略图 → 黑帧 → 先声后画」。
             * seek / 渲染器重启后重复上报由 [startGate] 和栅栏一起挡住，UI 门闩也是幂等的。
             */
            override fun onRenderedFirstFrame() {
                onMain { noteRenderedFirstFrame() }
            }

            /** 0.24.12 M-2：坏片源不再黑屏无提示，也不让超时兜底盲目 play()。 */
            override fun onPlayerError(error: PlaybackException) {
                failed = true
                startGate.cancel()
                discardHandoff()
                latestOnError?.invoke(error.errorCodeName)
                report()
            }
        }
        listener = l
        p.addListener(l)

        // dataSourceFactory 来自 FnTrust 信任自签证书的 OkHttp 客户端，证书和 AccessToken 头都带着。
        val mediaSource = ProgressiveMediaSource.Factory(dataSourceFactory)
            .createMediaSource(MediaItem.fromUri(url))
        p.setMediaSource(mediaSource)
        p.playWhenReady = false
        p.prepare()

        _controller = object : VideoController {
            override fun play() {
                if (!isActive || failed) return
                // 人按 OK 回到前台：解除后台标记，待交接若还没到就按正常前台放行。
                hostStopped = false
                // 人按 OK / 播完重播：此后不再做开局 seek，首帧再来也只撤层。
                startGate.startNow()
                unmute()
                if (p.playbackState == Player.STATE_ENDED) p.seekTo(0)
                p.play()
                report()
            }

            override fun pause() {
                if (!isActive) return
                p.pause()
                report()
            }

            override fun playPause() {
                if (p.isPlaying) pause() else play()
            }

            override fun seekBy(seconds: Int) {
                if (!isActive) return
                // 0.24.12 m-1：duration 还没取到时是 C.TIME_UNSET，旧写法
                // coerceAtLeast(0) 会把它变 0，coerceIn(0, 0) 把位置拉回 00:00。
                val duration = p.duration
                if (duration == C.TIME_UNSET || duration <= 0) return
                val target = (p.currentPosition + seconds * 1000L).coerceIn(0, duration)
                p.seekTo(target)
                // 停着的时候轮询不跑，seek 完的位置得自己报，进度条才跟手。
                report()
            }

            override fun setSpeed(speed: Float) {
                if (isActive) p.setPlaybackSpeed(speed)
            }

            override fun stop() {
                // 淡出 / 退出：待交接连同 vsync 回调一起丢，绝不在人走后补一次开播。
                discardHandoff()
                startGate.cancel()
                activity.onStop()
                latestOnState = null
                latestOnFirstFrame = null
                latestOnError = null
                _controller = null
                // setVolume(0) 先把 AudioTrack 里已写入的 PCM 压掉，p.stop() 再 flush 缓冲。
                mute()
                p.stop()
            }

            override fun forceStart() {
                if (!isActive || failed || startGate.isCancelled) return
                startGate.startNow()
                // 静音推进：播放一跑起来首帧必然渲染，但加载层还盖着、也不出声。
                // 恢复音量与撤层都留给 onRenderedFirstFrame。
                mute()
                p.play()
                report()
            }
        }
    }

    override fun snapshot(): VideoUiState {
        val p = player ?: return VideoUiState()
        val buffering = p.playbackState == Player.STATE_BUFFERING
        return VideoUiState(
            positionMs = p.currentPosition.coerceAtLeast(0),
            durationMs = if (p.duration == C.TIME_UNSET) 0 else p.duration.coerceAtLeast(0),
            isPlaying = videoIsPlaying(
                actualIsPlaying = p.isPlaying,
                gateStarted = startGate.isStarted,
                cancelled = startGate.isCancelled,
                buffering = buffering,
                hasActuallyPlayed = startGate.hasActuallyPlayed,
            ),
            ended = p.playbackState == Player.STATE_ENDED,
            buffering = buffering,
            speed = p.playbackParameters.speed,
        )
    }

    override fun needsFastPoll(): Boolean {
        if (!isActive) return false
        val p = player ?: return false
        return p.isPlaying || p.playbackState == Player.STATE_BUFFERING
    }

    /** 0.24.12 M-5：Activity 进后台时静音 + 暂停，回前台不自动续播（人按 OK）。 */
    override fun onHostStop() {
        val p = player ?: return
        if (!isActive || failed) return
        // 0.25.2：不整块丢弃待交接（首帧事件不会重来，回来封面会永远撤不掉），
        // 而是让下一次 vsync 收口成 HANDOFF_PAUSED：只撤层、不点播、不恢复音量。
        hostStopped = true
        mute()
        if (p.playWhenReady || p.isPlaying) p.pause()
        report()
    }

    /**
     * 释放顺序：摘监听 → 取消门控 → 断全部回调 → 解绑渲染壳 → 静音 → stop（flush 音频缓冲）
     * → release。取消门控是必须的：淡出期间若来一次 onRenderedFirstFrame，旧逻辑会在这时 play()。
     */
    override fun release() {
        discardHandoff()
        val p = player
        listener?.let { p?.removeListener(it) }
        listener = null
        _controller = null
        latestOnState = null
        latestOnFirstFrame = null
        latestOnError = null
        startGate.cancel()
        activity.onRelease()
        attachedView?.player = null
        attachedView = null
        if (p != null) {
            mute()
            p.stop()
            p.release()
        }
        player = null
        muted = false
    }

    /** 0.24.12 M-1：停止之后一律不再回流，UI 的轮询闸门也用 isActive 关掉。 */
    private fun report() {
        if (!activity.allowsStateFlow()) return
        latestOnState?.invoke(snapshot())
    }
}
