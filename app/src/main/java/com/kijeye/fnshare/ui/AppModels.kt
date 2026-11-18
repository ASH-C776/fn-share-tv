package com.kijeye.fnshare.ui

/**
 * 兼容层（结构化重构第 6 批）。
 *
 * 四个声明已**逐字**搬进 `presentation/state/AppState.kt`，本文件只留转发：
 *  - `AppState` / `OwnerGroup`：typealias。data class 没有嵌套类型，别名可用于构造、
 *    `copy()`、字段访问，ui 包下二十几处引用一行不改。
 *  - `CACHE_LIMIT_BYTES`：Kotlin 不支持给常量做 typealias，用一行 `val` getter 转发。
 *    三个调用点（`AppViewModel` 的两次 `albumCache.trim()`、`SettingsScreen` 的分母显示）
 *    都是运行时取值，从 `const val` 变成 `val` 不改变任何结果。
 *  - `Screen`：**故意不在这里留别名**。它是 sealed interface，Kotlin 不允许通过
 *    typealias 访问嵌套类（`Screen.Wall` / `Screen.Grid(album)` 这类限定名会编译失败，
 *    第 1 批的 `GridRow.Day` 已验证）。使用它的两个文件改为直接 import
 *    `com.kijeye.fnshare.presentation.state.Screen`，写法与原来完全一致。
 *
 * 依赖方向：`ui → presentation/state → domain/model`，单向，无跨层反向引用。
 *
 * 对应的生命周期清理应该在原文件的哪个位置调用：
 * 【无需清理】。typealias 是编译期概念，getter 不持有任何状态；
 * `MainActivity.onDestroy()` 与 `AppViewModel.onCleared()` 都不为此新增调用。
 */
typealias AppState = com.kijeye.fnshare.presentation.state.AppState

typealias OwnerGroup = com.kijeye.fnshare.presentation.state.OwnerGroup

internal val CACHE_LIMIT_BYTES: Long
    get() = com.kijeye.fnshare.presentation.state.CACHE_LIMIT_BYTES
