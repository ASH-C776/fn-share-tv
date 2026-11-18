package com.kijeye.fnshare.ui

import com.kijeye.fnshare.data.fn.SharedAlbum
import com.kijeye.fnshare.data.fn.SharedPhoto
import com.kijeye.fnshare.domain.usecase.BuildTimelineUseCase

/**
 * 定义已移到 `domain/model/TimelineModels.kt`（结构化重构第 1 批，逐字搬移）。
 * 保留 typealias 是为了让下面的 `buildTimeline()` 及其所有调用点零改动。
 */
internal typealias TimelineBucket = com.kijeye.fnshare.domain.model.TimelineBucket
internal typealias TimelineGroup = com.kijeye.fnshare.domain.model.TimelineGroup
internal typealias Timeline = com.kijeye.fnshare.domain.model.Timeline

/**
 * 计算已移到 `domain/usecase/BuildTimelineUseCase.kt`（结构化重构第 2 批，逐字搬移）。
 * 这里只留同名同签名的转发，让 `GridScreen.kt:123` 的 `remember { buildTimeline(...) }`
 * 及其所有调用点零改动。数据类的 typealias 见上方第 1 批说明。
 */
internal fun buildTimeline(photos: List<SharedPhoto>, album: SharedAlbum?): Timeline =
    BuildTimelineUseCase(photos, album)
