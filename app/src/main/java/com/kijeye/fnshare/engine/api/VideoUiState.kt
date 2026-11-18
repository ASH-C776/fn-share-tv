package com.kijeye.fnshare.engine.api

/**
 * 一帧播放器状态。纯不可变 data class，无定时器、协程或句柄，无需清理。
 */
data class VideoUiState(
    /** 当前播放位置。duration 尚未取到（还没 prepare 完）时为 0。 */
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val isPlaying: Boolean = false,
    /** 播放器到了 STATE_ENDED。播完态的控制栏常驻、左右键切片，都看它。 */
    val ended: Boolean = false,
    /**
     * 0.24.12：播放器在 STATE_BUFFERING。
     *
     * UI 据此显示加载态；更重要的是别在缓冲卡死时靠乐观上报把 `isPlaying` 谎报成 true，
     * 否则闲置保护会一直把「有人在忙」当真，画面卡住也永不退出。
     */
    val buffering: Boolean = false,
    val speed: Float = 1f,
)
