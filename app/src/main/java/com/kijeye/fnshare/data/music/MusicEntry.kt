package com.kijeye.fnshare.data.music

/**
 * 兼容转发层：全部声明已整体移到 `domain/model/MusicEntry.kt`
 * （结构化重构第 1 批，逐字搬移，函数体与常量值以 domain 那份为准）。
 *
 * 保留本文件是因为 `MusicScanner` / `SlideshowMusicPlayer` / `AppViewModel` /
 * `MusicDialog` 都 import 了 `com.kijeye.fnshare.data.music.*`；改包名会牵动十几处
 * 调用点。下面每一项都是单行转发，没有任何新分支、新判断或新默认值。
 */
typealias MusicEntry = com.kijeye.fnshare.domain.model.MusicEntry

val MUSIC_FILE_PREFIX: String
    get() = com.kijeye.fnshare.domain.model.MUSIC_FILE_PREFIX

fun musicIdFromPath(path: String): String =
    com.kijeye.fnshare.domain.model.musicIdFromPath(path)

fun musicPathFromId(id: String): String? =
    com.kijeye.fnshare.domain.model.musicPathFromId(id)

fun musicTitleFromFileName(name: String): String =
    com.kijeye.fnshare.domain.model.musicTitleFromFileName(name)

fun isPlayableAudioName(name: String): Boolean =
    com.kijeye.fnshare.domain.model.isPlayableAudioName(name)
