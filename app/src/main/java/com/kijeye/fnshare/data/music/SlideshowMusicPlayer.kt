package com.kijeye.fnshare.data.music

import android.content.Context
import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * 幻灯片底下那层背景音乐。
 *
 * 整个进程只用一个播放器：第一次要放音乐时建起来，之后一直复用。每场幻灯片都新建一个
 * `ExoPlayer` 会把整条音频栈重建一遍，还要重新申请音频焦点。
 *
 * 曲目按传入顺序循环（[Player.REPEAT_MODE_ALL]），也就是界面上勾选的顺序。曲子都是电视
 * 本地文件，直接读，没有下载和缓冲。
 *
 * 所有方法都必须在主线程调用：`ExoPlayer` 绑在调用线程的 `Looper` 上，而调用它的组合副作用
 * 本来就是主线程的。
 *
 * 音频焦点交给播放器处理，电视上别的应用开始出声——或者回音壁接管——时，音乐会暂停而不是两边一起响。
 */
class SlideshowMusicPlayer(private val context: Context) {

    private var player: ExoPlayer? = null

    private val _playing = MutableStateFlow(false)

    /**
     * 播放器**真的在出声**（对齐 ExoPlayer 的 isPlaying，由监听器实时维护）。
     * UI 的「播放中」徽标认这个——以前靠 `slideshow && selected.isNotEmpty()` 推断，
     * 勾选项全是找不到的幽灵 id 时也会谎报「播放中」。
     */
    val playing: StateFlow<Boolean> = _playing.asStateFlow()

    private val _failed = MutableStateFlow(false)

    /**
     * 这一轮里有曲子**根本解不开**（格式不被支持，比如 ape / dsf / wma，或者文件坏了）。
     *
     * 电视上「勾了歌却一声不响」最常见的原因就是这个：扫描只看扩展名，ExoPlayer 到解封装
     * 才发现不认。没有这条信号时，用户能看到的只有沉默——连「选错了歌」都无从判断。
     * 换一套曲目重新放时清零。
     */
    val failed: StateFlow<Boolean> = _failed.asStateFlow()

    /**
     * 当前播放位置（毫秒）。播放器还没建（没放过任何歌）时 0。
     *
     * 供歌词浮层按时间轴取进度——歌词只需要「现在放到哪了」这一个数，为此再开一条
     * StateFlow 让全 App 跟着 100ms 抖一次不值；轮询方在主线程按需读这一个字段。
     * 主线程调用（`ExoPlayer` 绑主 Looper）。
     */
    fun positionMs(): Long = player?.currentPosition ?: 0L

    /**
     * 当前曲目的 mediaId；没有载入曲目（stop 过、或播放器还没建）时 null。
     *
     * 轮询方拿它和上次读到的值比对来发现「换曲了」——包括 stop 清空、坏曲跳下一首、
     * 列表循环环绕这几种路径，一次轮询全部覆盖，不需要另挂过渡监听。
     * 主线程调用。
     */
    fun currentMediaIdOrNull(): String? = player?.currentMediaItem?.mediaId

    /**
     * 播放器当前载入的曲目列表。
     *
     * 留着它，是为了让「要放的还是这一套」不变成重启：设置会在每次相关重组时被重新求值，
     * 每次都重新 prepare 一遍同一套，会让曲子每次跳回第一小节。`MusicEntry` 是 data class，
     * 同一套曲子扫两遍逐字段相等，不会误判成「换曲目了」。
     */
    private var loaded: List<MusicEntry> = emptyList()

    /** 连续解不开的曲目数。整套都放不出来时必须停手，否则会在坏曲子上无限跳。 */
    private var skipped = 0

    /** 开始——或继续——播放 [tracks]，按给定顺序循环。空列表等于停止。 */
    fun play(tracks: List<MusicEntry>) {
        if (tracks.isEmpty()) {
            stop()
            return
        }
        val exo = player ?: newPlayer().also { player = it }
        if (tracks != loaded) {
            exo.setMediaItems(
                tracks.map { entry ->
                    MediaItem.Builder()
                        .setUri(uriFor(entry))
                        .setMediaId(entry.id)
                        .build()
                },
            )
            exo.prepare()
            loaded = tracks
            // 换了一套曲目＝新的一轮，上一轮的失败提示不该继续挂着。
            _failed.value = false
            skipped = 0
        }
        exo.play()
    }

    /**
     * 一首曲子的字节在哪里。
     *
     * 媒体库索引过的歌必须走 `content://`：个别电视 ROM 的 FUSE 把底层属主位透出来，
     * 别的应用拷进来的歌按路径 open 会被挡成 EACCES；MediaProvider 自己开 fd 的
     * content 路线不受影响。没索引过的歌（U 盘新拷的）才回落到 `file:` 路径。
     *
     * `file:` 的 id 去掉前缀交给 `Uri.fromFile`——路径开头的冒号会被当成 scheme，
     * 永远打不开，所以必须先把 `file:` 剥掉。
     */
    private fun uriFor(entry: MusicEntry): Uri {
        if (entry.mediaStoreId > 0L) {
            return ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, entry.mediaStoreId)
        }
        val path = musicPathFromId(entry.id)
        if (path != null) return Uri.fromFile(File(path))
        return Uri.parse(entry.id)
    }

    /** 这一场结束了：停止播放，下一次从头开始。 */
    fun stop() {
        val exo = player ?: return
        exo.stop()
        exo.clearMediaItems()
        loaded = emptyList()
        skipped = 0
    }

    /**
     * 暂停：停在当前位置，进度不丢，下次 [resume] 从同一处接着放。
     * 没有任何曲子载入（幻灯片已结束）时安静空操作。
     *
     * 幻灯片播放中按 OK 的「暂停/继续」就是它——图片翻页和背景乐要一起停、一起续，
     * 不能只拦翻页（那样画面停了声音还走着，正是之前的 bug）。
     */
    fun pause() {
        val exo = player ?: return
        if (exo.isPlaying) exo.pause()
    }

    /** 从 [pause] 处继续。没有载入曲子（比如幻灯片已结束）时安静空操作。 */
    fun resume() {
        val exo = player ?: return
        if (loaded.isEmpty()) return
        exo.play()
    }

    /**
     * 切到下一首（[delta] > 0）或上一首（[delta] < 0）。
     *
     * 返回 true 表示**真的切了**，false 表示没有可切的曲目（没勾歌、播放器还没建、
     * 幻灯片已经结束）。调用方靠这个返回值决定按键归谁——看图页就是「切到了才吃掉
     * 左右键，否则照旧翻照片」。
     *
     * 用 `seekToNextMediaItem` / `seekToPreviousMediaItem`，**不是** `seekToNext` /
     * `seekToPrevious`：后者在播放超过 3 秒时会把当前这首从头再放一遍，按「上一首」
     * 却重放当前曲，用户只会以为按键坏了。前者永远跳到相邻曲目，配合 [REPEAT_MODE_ALL]
     * 在首尾自动环绕，一首也不会漏。
     *
     * 结尾补一次 `play()`：切曲瞬间播放器会进 BUFFERING，个别 ROM 上 playWhenReady
     * 会跟着掉成 false，不补这一下就是「画面换了歌却不出声」。调用方保证只在
     * 幻灯片正在播放时进来，所以这里接管播放状态是对的。
     */
    fun skip(delta: Int): Boolean {
        val exo = player ?: return false
        if (loaded.isEmpty()) return false
        if (delta >= 0) exo.seekToNextMediaItem() else exo.seekToPreviousMediaItem()
        exo.play()
        // 换曲＝用户主动操作，把「连续解不开」的计数放掉，别让上一首坏歌的账算到这一首头上。
        skipped = 0
        return true
    }

    /** 释放底层资源。ViewModel 销毁时调用。 */
    fun release() {
        player?.release()
        player = null
        loaded = emptyList()
        skipped = 0
        _playing.value = false
        _failed.value = false
    }

    private fun newPlayer(): ExoPlayer {
        val exo = ExoPlayer.Builder(context).build()
        exo.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            /* handleAudioFocus = */ true,
        )
        exo.repeatMode = Player.REPEAT_MODE_ALL
        // 真实播放状态对外只有这一条路：监听器跟着播放器的每次起停走，UI 直接读。
        exo.addListener(
            object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    _playing.value = isPlaying
                    // 有曲子真的出声了就说明播放链路是通的，把连续失败计数放掉。
                    if (isPlaying) skipped = 0
                }

                override fun onPlayerError(error: PlaybackException) {
                    _playing.value = false
                    _failed.value = true
                    skipped++
                    Log.w(
                        TAG,
                        "背景乐播放失败（第 $skipped 次）：" +
                            "${exo.currentMediaItem?.mediaId} · ${error.errorCodeName}",
                        error,
                    )
                    // 一首坏歌不该让整场静音：跳到下一首接着放。整轮都失败就彻底停——
                    // 否则会在一堆打不开的文件之间无限跳，比沉默还费电。
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

    private companion object {
        const val TAG = "SlideshowMusic"
    }
}
