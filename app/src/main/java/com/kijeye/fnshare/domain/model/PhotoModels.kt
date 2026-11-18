package com.kijeye.fnshare.domain.model

import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.util.Calendar
import java.util.Locale

/**
 * 该代码是从哪个原文件提取的：
 * `data/fn/FnApi.kt:13-113`（`SharedAlbum` / `SharedPhoto` / `PhotoPage` 三个 data class，
 * 含 `SharedAlbum.ageOn()`、`ageMonthsOn()`、`bornDate`、`isBabyAlbum` 的全部函数体）。
 * **逐字搬移，未改动任何一行逻辑、字段名、默认值或注释**。
 * 原文件 `data/fn/FnApi.kt` 同一位置改为三行 `typealias`，保证同包及所有
 * `import com.kijeye.fnshare.data.fn.SharedAlbum` 的旧调用点零改动、行为零变化。
 *
 * 对应的生命周期清理应该在原文件的哪个位置调用：
 * 【无需清理】。三个类都是不可变数据类，`bornDate` 是无状态 `lazy`，不持有线程、
 * 定时器、文件句柄或 Context。原 `FnApi.kt` 没有清理点，`MainActivity.onDestroy()` /
 * `AppViewModel.onCleared():1198` 也不需要新增调用。
 *
 * 之所以移进 `domain/model`：这三个类型是 UI、data、engine 三层的公共语言，
 * 放在 `data.fn` 里会让 domain 反向依赖 data 包。`domain` 不 import 任何 Android 类。
 */

/** 别人共享给这个账号的一个相册。`ownerName` 是分组依据。 */
data class SharedAlbum(
    val id: Long,
    val name: String,
    val ownerName: String,
    val photoCount: Int,
    val posterUrl: String?,
    /** 1 = 宝宝相册（带出生日期），0 = 普通相册。 */
    val category: Int = 0,
    /** 出生日期 `YYYY-MM-DD`；空 = 普通相册。 */
    val birthday: String? = null,
    /** 0 = 按年龄显示，1 = 普通显示。 */
    val displayType: Int = 0,
) {
    /** 有出生日期的相册即宝宝相册：网格里每天的照片头改用年龄显示。 */
    val isBabyAlbum: Boolean get() = !birthday.isNullOrBlank()

    /**
     * 宝宝相册在某一天的年龄：(年, 月, 天)。
     *
     * [onDate] 传 "yyyy-MM-dd"（照片拍摄日）返回那天宝宝的年龄；不传则按今天计算。
     * 出生日期缺失、日期无法解析、或目标日期早于出生日期（异常数据）时返回 null。
     * 天数借位取上个月的实际天数，跨月 / 跨年都正确。
     */
    fun ageOn(onDate: String? = null): Triple<Int, Int, Int>? {
        val b = birthday ?: return null
        val born = try {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(b)
        } catch (e: Exception) {
            null
        } ?: return null
        val ref = onDate?.let { d ->
            try {
                SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(d)
            } catch (e: Exception) {
                null
            }
        } ?: Calendar.getInstance().time
        val now = Calendar.getInstance().apply { time = ref }
        val birth = Calendar.getInstance().apply { time = born }

        var years = now.get(Calendar.YEAR) - birth.get(Calendar.YEAR)
        var months = now.get(Calendar.MONTH) - birth.get(Calendar.MONTH)
        var days = now.get(Calendar.DAY_OF_MONTH) - birth.get(Calendar.DAY_OF_MONTH)
        if (days < 0) {
            val prevMonth = Calendar.getInstance().apply {
                time = now.time
                add(Calendar.MONTH, -1)
            }
            days += prevMonth.getActualMaximum(Calendar.DAY_OF_MONTH)
            months--
        }
        if (months < 0) {
            months += 12
            years--
        }
        if (years < 0) return null
        return Triple(years, months, days)
    }

    /**
     * 只取「几个月大」这一级：返回 (岁, 月龄)，天数丢掉。
     *
     * 时间轴按月龄分组用。和 [ageOn] 的 (岁, 月) 口径一致，但走 java.time，不走
     * SimpleDateFormat —— 建索引时要给每一张照片算一次，863 张各自 parse 两遍日期
     * 是白白浪费。拍照日早于生日（脏数据）返回 null，由调用方归到「无日期」。
     */
    fun ageMonthsOn(date: java.time.LocalDate): Pair<Int, Int>? {
        val born = bornDate ?: return null
        if (date.isBefore(born)) return null
        val p = java.time.Period.between(born, date)
        return p.years to p.months
    }

    /** 解析好的出生日期，只解析一次。 */
    private val bornDate: java.time.LocalDate? by lazy {
        birthday?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
    }
}

/** 相册里的一张照片。本版只要缩略图和原图两种地址。 */
data class SharedPhoto(
    val id: Long,
    val thumbUrl: String?,
    val fullUrl: String?,
    /** 真原图（originalUrl）。只在「原图」开关打开时由全屏使用，平时不加载。 */
    val originalUrl: String? = null,
    val isVideo: Boolean,
    val videoUrl: String?,
    /** 拍摄时间。fnOS 给的是 EXIF 风格「2026:10:28 18:25:41」，按拍摄地本地时间，不做时区换算。 */
    val takenAt: LocalDateTime? = null,
    /**
     * 拍摄地点：服务端 `geo` 去掉国家后的「省 市 区 路 镇」，没 GPS 就是 null。
     *
     * 服务端已经做完经纬度反查，这里不做任何地理判断，只删第一段国家。
     */
    val location: String? = null,
    /** 原文件名（如 `2024-01-02 18.25.41.jpg`）。fnOS 不返回时为空——信息栏据此只显示大小。 */
    val name: String? = null,
    /**
     * 服务端给的像素尺寸。竖版就是高 > 宽（服务端已按实际朝向给值，rotation 恒 0，
     * 不需要再旋转）。0 表示未知，信息栏据此不显示尺寸这一行。
     *
     * 0.27.0 起从 `/album/photos` 的 `width` / `height` 解析并随清单缓存落盘。
     */
    val width: Int = 0,
    val height: Int = 0,
    /** 视频时长（秒），来自 `mediaDuration`。照片恒 0。 */
    val durationSeconds: Int = 0,
    /** 文件真实字节数，来自 `fileSize`。0 = 未知（0.26.x 及更早的清单缓存里没有这个值）。 */
    val byteSize: Long = 0,
)

data class PhotoPage(val items: List<SharedPhoto>, val hasMore: Boolean)
