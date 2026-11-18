package com.kijeye.fnshare.domain.usecase

import com.kijeye.fnshare.domain.model.SharedPhoto

/**
 * 幻灯片随机次序表：一轮之内「不重不漏」。
 *
 * 纯 Kotlin，只认照片 id，不认识 Android、界面、播放器，也不认识「视频」这个概念
 * （能不能进表由 [slideshowDeckIds] 先过滤好）。
 *
 * 开播时按当时已知的照片排一次序，自动前进只从这张表里按顺序取；取完一轮由
 * 调用方决定重排（循环开）还是收尾（循环关）。续页取回的新照片洗过后接到表尾，
 * 因此整本相册的照片最终都能轮到。
 *
 * 手动翻页不经过这里：它始终按相册顺序 ±1，不消耗本表，也不改变本表顺序。
 */
class SlideshowDeck(
    private val shuffle: (List<Long>) -> List<Long> = { it.shuffled() },
) {

    private var albumId: Long? = null
    private var order: MutableList<Long> = mutableListOf()
    private var cursor = 0
    private var known: LinkedHashSet<Long> = linkedSetOf()

    /** 本轮还没发出的张数。 */
    val remaining: Int get() = (order.size - cursor).coerceAtLeast(0)

    /** 表里有没有照片可以重洗一轮。给 [decideDeckExhaustion] 用，避免「重洗一个空表」。 */
    val canRestart: Boolean get() = known.isNotEmpty()

    /** 开播 / 换相册 / 改随机开关：用当时已知的照片重排一次，之前播过的一律不算数。 */
    fun start(albumId: Long, photoIds: List<Long>) {
        this.albumId = albumId
        val ids = photoIds.distinct()
        known = LinkedHashSet(ids)
        order = shuffle(ids).toMutableList()
        cursor = 0
    }

    /**
     * 相册清单变了（续页追加、缓存整本灌入、后台重建）：只把没见过的 id 洗过接到队尾，
     * 已发出的前缀和还没发的顺序都不动，正在预取的下一张也就不会失效。
     * 换相册等同于 [start]。返回本次新增的张数。
     */
    fun sync(albumId: Long, photoIds: List<Long>): Int {
        if (this.albumId != albumId) {
            start(albumId, photoIds)
            return photoIds.distinct().size
        }
        val added = photoIds.distinct().filter { it !in known }
        if (added.isEmpty()) return 0
        known.addAll(added)
        order.addAll(shuffle(added))
        return added.size
    }

    /** 看一眼下一张但不推进，供界面提前解码。 */
    fun peek(): Long? = order.getOrNull(cursor)

    /** 取下一张并推进；一轮发完返回 null。 */
    fun next(): Long? = order.getOrNull(cursor)?.also { cursor++ }

    /** 循环开着、一轮发完时重排再来一轮。一张照片都没有时返回 false。 */
    fun restartRound(): Boolean {
        if (known.isEmpty()) return false
        order = shuffle(known.toList()).toMutableList()
        cursor = 0
        return true
    }

    fun clear() {
        albumId = null
        order = mutableListOf()
        known = linkedSetOf()
        cursor = 0
    }
}

/** 能进随机表的照片：视频整段排除，id 去重，保持相册顺序。 */
internal fun slideshowDeckIds(photos: List<SharedPhoto>): List<Long> =
    photos.filter { !it.isVideo }.map { it.id }.distinct()
