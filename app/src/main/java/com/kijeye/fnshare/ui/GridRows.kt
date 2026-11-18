package com.kijeye.fnshare.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kijeye.fnshare.data.fn.SharedAlbum
import com.kijeye.fnshare.data.fn.SharedPhoto
import com.kijeye.fnshare.domain.usecase.BuildGridRowsUseCase

/**
 * 定义已移到 `domain/model/GridRow.kt`（结构化重构第 1 批，逐字搬移）。
 *
 * Kotlin 的 `typealias` 不能用于访问嵌套类：写 `别名.子类` 编译器找不到子类。
 * 所以额外给两个子类各起一个顶层别名；调用点只把类型名换掉，
 * 判断表达式、`isRowEnd` 焦点列计算、`copy()` 参数逐字不变——
 * 编译期展开后仍是原来那两个 data class。
 */
internal typealias GridRow = com.kijeye.fnshare.domain.model.GridRow
internal typealias GridRowDay = com.kijeye.fnshare.domain.model.GridRow.Day
internal typealias GridRowPic = com.kijeye.fnshare.domain.model.GridRow.Pic
internal const val GRID_COLUMNS = com.kijeye.fnshare.domain.model.GRID_COLUMNS

/**
 * 计算已移到 `domain/usecase/BuildGridRowsUseCase.kt`（结构化重构第 2 批，逐字搬移）。
 * 这里只留同名同签名的转发，让 `GridScreen.kt` 的调用点零改动。
 * 下面的 `DayHeaderCell` 是 Compose 渲染，不属于计算，继续留在 UI 层。
 */
internal fun buildGridRows(
    photos: List<SharedPhoto>,
    album: SharedAlbum,
    todayLabel: String,
    yesterdayLabel: String,
    ageFormat: String,
): List<GridRow> = BuildGridRowsUseCase(photos, album, todayLabel, yesterdayLabel, ageFormat)


/** 网格里占据整行的一天标题。宝宝相册显示年龄，普通相册显示日期。 */
@Composable
internal fun DayHeaderCell(label: String) {
    if (label.isBlank()) return
    Text(
        text = label,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp),
    )
}
