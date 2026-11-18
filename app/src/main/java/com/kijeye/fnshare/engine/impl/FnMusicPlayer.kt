package com.kijeye.fnshare.engine.impl

import android.content.Context
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.kijeye.fnshare.data.music.fn.FnMusicApi
import com.kijeye.fnshare.data.music.fn.FnMusicStreamGateException
import com.kijeye.fnshare.domain.model.MusicEntry
import com.kijeye.fnshare.domain.model.fnTrackGuidOrNull
import com.kijeye.fnshare.engine.api.AudioEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient

/**
 * 该代码是从哪个原文件提取的：
 * 【新增实现，无函数体被搬动】。逐条镜像 `engine/impl/ExoAudioEngine.kt` 所转发的
 * `data/music/SlideshowMusicPlayer.kt` 语义（播放器配置、失败跳曲、skip 语义、
 * playing/failed 两个 StateFlow），**唯一不同**是曲目地址从本地 `content://` / `file:`
 * 换成 `FnMusicApi.streamUrl()` 的 HTTP 流。本地播放器一行未改。
 *
 * 为什么另起一个播放器而不是给本地播放器加参数：本地那套 `uriFor()` 里有 MediaStore
 * 兼容逻辑，改它等于动已经验证过的链路；两个播放器互斥起停（切源时由 AppViewModel
 * 先 stop 另一个），任何时刻只有一个出声。
 *
 * 对应的生命周期清理应该在原文件的哪个位置调用：
 * `ui/AppViewModel.kt` 的 `onCleared()`——与本地播放器同处，走 [AudioEngine.release]。
 * 每个 ViewModel 一个实例（ExoPlayer 绑调用线程的 Looper，release 后不可复用），
 * 由 `di/AppContainer.audioEngine(kind)` 工厂产出，不放进容器字段。
 * 切源时若提前丢弃本实例，调用方必须先 `stop()` 再 `release()`。
 *
 * 线程约束照抄本地播放器：所有方法必须在主线程调用。
 *
 * ⚠️ `streamClient` 必须是带 `FnMusicStreamInterceptor` 的**独立** OkHttpClient：
 * 相册的 client 挂着 `AccessToken` 拦截器和相册 CookieJar，且 ExoPlayer 的
 * DataSource 不会自己带飞牛的三个 header。
 */
class FnMusicPlayer(
    private val context: Context,
    private val api: FnMusicApi,
    private val streamClient: OkHttpClient,
) : AudioEngine {

    private var player: ExoPlayer? = null

    private val _playing = MutableStateFlow(false)
    override val playing: StateFlow<Boolean> = _playing.asStateFlow()

    private val _failed = MutableStateFlow(false)
    override val failed: StateFlow<Boolean> = _failed.asStateFlow()

    /** 与本地播放器同义：当前载入的曲目，用来避免每次重组都重新 prepare 把歌打回开头。 */
    private var loaded: List<MusicEntry> = emptyList()

    /** 连续解不开的曲目数；整套都失败时停手，别在坏流之间无限跳。 */
    private var skipped = 0

    override fun play(tracks: List<MusicEntry>) {
        // 本地 id 混进来时直接忽略：网络播放器打不开 `file:` / `content://`。
        val playable = tracks.filter { fnTrackGuidOrNull(it.id) != null }
        Log.d(TAG, "play(): in=${tracks.size}, playable=${playable.size}")
        if (playable.isEmpty()) {
            Log.w(TAG, "play(): no fn: track id, stop without creating player")
            stop()
            return
        }
        val exo = player ?: newPlayer().also { player = it }
        if (playable != loaded) {
            val items = playable.mapNotNull { entry ->
                val guid = fnTrackGuidOrNull(entry.id) ?: return@mapNotNull null
                runCatching {
                    MediaItem.Builder()
                        .setUri(api.streamUrl(guid))
                        .setMediaId(entry.id)
                        .build()
                }.getOrNull()
            }
            if (items.isEmpty()) {
                Log.w(TAG, "play(): every streamUrl() failed, stop without creating player")
                stop()
                return
            }
            Log.d(TAG, "play(): setMediaItems(${items.size})")
            exo.setMediaItems(items)
            exo.prepare()
            loaded = playable
            _failed.value = false
            skipped = 0
        }
        exo.play()
    }

    override fun stop() {
        val exo = player ?: return
        exo.stop()
        exo.clearMediaItems()
        loaded = emptyList()
        skipped = 0
    }

    override fun pause() {
        val exo = player ?: return
        if (exo.isPlaying) exo.pause()
    }

    override fun resume() {
        val exo = player ?: return
        if (loaded.isEmpty()) return
        exo.play()
    }

    /** 与本地同义：用 seekToNextMediaItem / seekToPreviousMediaItem，不用 seekToNext。 */
    override fun skip(delta: Int): Boolean {
        val exo = player ?: return false
        if (loaded.isEmpty()) return false
        if (delta >= 0) exo.seekToNextMediaItem() else exo.seekToPreviousMediaItem()
        exo.play()
        skipped = 0
        return true
    }

    override fun positionMs(): Long = player?.currentPosition ?: 0L

    override fun currentMediaIdOrNull(): String? = player?.currentMediaItem?.mediaId

    override fun release() {
        player?.release()
        player = null
        loaded = emptyList()
        skipped = 0
        _playing.value = false
        _failed.value = false
    }

    private fun newPlayer(): ExoPlayer {
        val dataSourceFactory = OkHttpDataSource.Factory(streamClient)
        val exo = ExoPlayer.Builder(context)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(context).setDataSourceFactory(dataSourceFactory),
            )
            .build()
        exo.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            /* handleAudioFocus = */ true,
        )
        exo.repeatMode = Player.REPEAT_MODE_ALL
        exo.addListener(
            object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    _playing.value = isPlaying
                    if (isPlaying) skipped = 0
                }

                override fun onPlayerError(error: PlaybackException) {
                    _playing.value = false
                    _failed.value = true
                    skipped++
                    Log.w(
                        TAG,
                        "飞牛音乐播放失败（第 $skipped 次）：" +
                            "${exo.currentMediaItem?.mediaId} · ${error.errorCodeName} · " +
                            (gateReasonOf(error) ?: "无更具体原因"),
                        error,
                    )
                    if (exo.hasNextMediaItem() && skipped < loaded.size) {
                        exo.seekToNextMediaItem()
                        exo.prepare()
                        exo.play()
                    } else {
                        exo.stop()
                        _playing.value = false
                    }
                }
            },
        )
        return exo
    }

    /** 拦截器抛的 IOException 会被 DataSource / ExoPlayer 包几层，沿 cause 链找回来。 */
    private fun gateReasonOf(error: Throwable): String? {
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth++ < 8) {
            if (current is FnMusicStreamGateException) return current.message
            current = current.cause
        }
        return null
    }

    private companion object {
        const val TAG = "FnMusicPlayer"
    }
}
