package com.kijeye.fnshare.domain.usecase

/**
 * 幻灯片随机「次序表发完这一刻」该怎么办。
 *
 * 纯 Kotlin 决策，不碰 Android、不碰 ViewModel 状态，方便单测把优先级钉死。
 * ViewModel 只负责照着结果去调续页 / 重洗 / 关幻灯片。
 *
 * 关键优先级：**相册还有没取回来的页时，一律先续页**，哪怕循环开着也不许
 * 提前重洗——否则「梦里水乡」3099 项只播了头一页 28 张照片就又从这 28 张
 * 里重来，后半本永远轮不到，一轮之内「不重不漏」就成了空话。
 */
enum class DeckExhaustion {
    /** 还有页没取回来：取下一页，把新照片补进表尾接着发。 */
    LOAD_MORE,

    /** 整本都取完了、循环开着、表里确实有照片：重洗一轮。 */
    RESTART_ROUND,

    /** 整本都取完了、循环关着（或一张照片都没有）：图片和背景乐一起收尾。 */
    FINISH,
}

internal fun decideDeckExhaustion(
    hasMorePhotos: Boolean,
    loopSlideshow: Boolean,
    canRestart: Boolean,
): DeckExhaustion = when {
    hasMorePhotos -> DeckExhaustion.LOAD_MORE
    loopSlideshow && canRestart -> DeckExhaustion.RESTART_ROUND
    else -> DeckExhaustion.FINISH
}

/** 表里剩几张时提前把下一页取回来，免得发完那一刻干等网络。 */
internal const val DECK_PREFETCH_MARGIN = 3

/**
 * 只在「表里还剩 1~[margin] 张、相册确实还有页、且当前没在取页」时提前续页。
 *
 * 已经发完（remaining == 0）时不归这里管，走 [decideDeckExhaustion] 的 LOAD_MORE，
 * 那边会挂待办等新页回来再兑现，避免同一页被取两次。
 */
internal fun shouldPrefetchDeck(
    remaining: Int,
    hasMorePhotos: Boolean,
    photosLoading: Boolean,
    margin: Int = DECK_PREFETCH_MARGIN,
): Boolean = hasMorePhotos && !photosLoading && remaining in 1..margin
