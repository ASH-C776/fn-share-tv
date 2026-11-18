package com.kijeye.fnshare.domain.usecase

import com.kijeye.fnshare.domain.model.MusicEntry

/**
 * 飞牛歌单「展开成播放队列」的纯逻辑。
 *
 * 从 `AppViewModel.ensureFnPlaylistMusic()` 抽出来只为一件事：让「拉取失败」成为
 * 可单测的一等状态。0.21.2 里整条链路是
 * `runCatching { listTracks(id) }.getOrNull().orEmpty()` —— 失败和「歌单本来就是空的」
 * 长得一模一样，最后 `merged.isEmpty()` 直接 `stop()`，用户只看到一声不响，
 * 连日志都没有。把结果合成收进这里之后，失败组合可以离线穷举，不用靠 NAS 复现。
 *
 * 纯函数：不碰协程、网络、Android，也不持有状态；[AppViewModel] 负责发请求、
 * 按 [FnPlayQueue.allFailed] 决定是否给用户提示、按随机开关洗牌后交给播放器。
 */
sealed interface FnGroupTracks {
    /** 成功拿到曲目；空列表也是合法成功（歌单里确实没歌）。 */
    data class Ok(val groupId: String, val tracks: List<MusicEntry>) : FnGroupTracks

    /** 失败必须带 cause，不许用空列表冒充成功。 */
    data class Failed(val groupId: String, val cause: Throwable) : FnGroupTracks
}

data class FnPlayQueue(
    /** 按勾选顺序合并、按 id 去重、剔掉不可播之后的最终队列。 */
    val tracks: List<MusicEntry>,
    /** 至少一张歌单没拉到：调用方要写日志。 */
    val hasFailure: Boolean,
    /** 一张都没拉到、且没有任何可用曲目：调用方必须给可见提示，不能只是 stop。 */
    val allFailed: Boolean,
)

/**
 * [results] 的顺序 = 用户勾选 / 目录里的顺序，播放顺序就照它来，不依赖 Set 迭代顺序。
 *
 * [looseTracks] 是 0.20.0 落盘的旧版 `fn:` 曲目 id 的兼容兜底：**只在主队列为空时**
 * 补进来，某张歌单成功了就不把来路不明的旧曲目混进当前选择。
 */
fun buildFnPlayQueue(
    results: List<FnGroupTracks>,
    looseTracks: List<MusicEntry> = emptyList(),
): FnPlayQueue {
    val merged = mutableListOf<MusicEntry>()
    results.forEach { result ->
        if (result is FnGroupTracks.Ok) merged += result.tracks
    }
    val failures = results.count { it is FnGroupTracks.Failed }
    if (merged.isEmpty()) merged += looseTracks
    val tracks = merged.filter { it.playable }.distinctBy { it.id }
    return FnPlayQueue(
        tracks = tracks,
        hasFailure = failures > 0,
        allFailed = results.isNotEmpty() && failures == results.size && tracks.isEmpty(),
    )
}

/**
 * 服务端短暂抖动的退避：飞牛音乐后端重启时 nginx 会回 502 / 100001，往往十几秒后
 * 自己就活了。以前只在「开幻灯片」那一刻拉一次，失败就永久静音，用户得手动关掉
 * 再开一次幻灯片。现在最多补两次（3s、6s），服务恢复即自动接着播。
 *
 * 上限刻意压得很低：服务真挂了不能在后台无限轮询，而且每轮都要发 N 个请求。
 */
fun fnQueueRetryDelayMs(attempt: Int): Long? = when (attempt) {
    1 -> 3_000L
    2 -> 6_000L
    else -> null
}

/**
 * 只有「一张歌单都没拉到」才重试：部分成功照常播，不为了补齐再发请求；
 * [stillWaiting] 为 false（关了幻灯片 / 切回本地 / 改了勾选）时立刻停手。
 * [attempt] 从 1 开始计数。
 */
fun shouldRetryFnQueue(
    queue: FnPlayQueue,
    attempt: Int,
    stillWaiting: Boolean,
): Boolean = stillWaiting && queue.allFailed && fnQueueRetryDelayMs(attempt) != null
