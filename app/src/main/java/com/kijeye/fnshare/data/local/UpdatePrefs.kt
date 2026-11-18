package com.kijeye.fnshare.data.local

import android.content.Context
import android.content.SharedPreferences

/**
 * 更新检测的时间戳。刻意**独立一个 prefs 文件**（update_prefs.xml），
 * 不碰 fn_share.xml —— 后者是登录配置，出包流程要整份备份与 diff，
 * 更新检测这种无关数据混进去会污染校验。
 */
class UpdatePrefs(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("update_prefs", Context.MODE_PRIVATE)

    /** 上次检测成功的时刻（epoch millis），0 = 从没查过。 */
    var lastCheckMs: Long
        get() = prefs.getLong(KEY_LAST_CHECK, 0L)
        set(value) { prefs.edit().putLong(KEY_LAST_CHECK, value).apply() }

    /** 用户点「取消」时记住当时版本，同一版本不再弹（除非又隔了一天重新查到更高版）。 */
    var dismissedCode: Int
        get() = prefs.getInt(KEY_DISMISSED, 0)
        set(value) { prefs.edit().putInt(KEY_DISMISSED, value).apply() }

    private companion object {
        const val KEY_LAST_CHECK = "last_check_ms"
        const val KEY_DISMISSED = "dismissed_code"
    }
}
