package com.kijeye.fnshare.presentation.state

/**
 * 一页照片回来时，判断它还能不能落到当前界面上。
 *
 * 请求是「按相册」发出去的：发出去的时候人在 A 相册，等网络回来时人可能已经退到
 * 相册墙、去了设置页，或者干脆按开了 B 相册。这时 A 的那一页既不能追加到 B 的列表里，
 * 也不能拿 B 的列表去写 A 的相册缓存。
 *
 * 网格页和看图页是同一本相册的两个屏，两边都放行（0.26.1 起看图页也会续页）。
 * 纯函数，不认识 ViewModel / 协程 / 网络，方便单测把「换了相册」钉死。
 */
internal fun photoPageBelongsTo(screen: Screen, albumId: Long): Boolean = when (screen) {
    is Screen.Grid -> screen.album.id == albumId
    is Screen.Viewer -> screen.album.id == albumId
    else -> false
}
