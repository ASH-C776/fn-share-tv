package com.kijeye.fnshare.presentation.state

/**
 * 从设置页返回时该回到哪一屏（0.28.0）。
 *
 * 设置页现在有两个入口：相册墙的菜单键和相册网格的菜单键。从网格进去的，
 * 返回时应该回到那本相册（网格的滚动位置按相册 id 存在 Activity 层，能原样恢复），
 * 而不是把人丢回相册墙重新找一遍。
 *
 * 只认这两种来源：万一以后有别的入口（比如从看图页直接跳设置），一律回相册墙，
 * 免得返回到一个「当时停在第几张」早已过期的快照上。
 */
internal fun settingsReturnTarget(from: Screen?): Screen = when (from) {
    is Screen.Wall -> Screen.Wall
    is Screen.Grid -> from
    else -> Screen.Wall
}
