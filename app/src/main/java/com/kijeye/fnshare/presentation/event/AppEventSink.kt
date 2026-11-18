package com.kijeye.fnshare.presentation.event

/**
 * 该代码是从 `MainActivity.kt:101` 的 `val state by model.state.collectAsStateWithLifecycle()`
 * 这条「UI 只读状态」的既有关系**反向补全**出来的（结构化重构第 5 批）：
 * 既然读走 StateFlow，写就该走一个统一入口，而不是 UI 直接调二十几个 VM 方法。
 *
 * 单向数据流：UI --[AppEvent]--> [AppEventSink]（ViewModel 实现）--> StateFlow --> UI。
 * UI 层只依赖本接口，不依赖 `AppViewModel` 的具体类型。
 *
 * 第 6 批已接线：`AppViewModel : AppEventSink`，`onEvent()` 用 `when (event)`
 * 分派到现有方法；`MainActivity` 里构造 `ViewerActions` 的每个 lambda 改成
 * `sink.onEvent(AppEvent.X)`。唯一例外是 `onSkipMusic`（要返回 Boolean 给按键层），
 * 仍直接调 `model::skipMusicTrack`。`ViewerScreen.kt` 的函数体与按键/焦点逻辑一行未动。
 *
 * 对应的生命周期清理应该在原文件的哪个位置调用：
 * 【无需清理】。没有 Flow、没有 Channel、没有协程作用域——事件是同步派发的，
 * 不排队也不缓存。`AppViewModel.onCleared():1198` 不需要为此新增 cancel。
 *
 * 线程约定：只在主线程调用（Compose 按键回调 / 点击事件均在主线程）。
 * 需要 IO 的动作由接收方自己在 `viewModelScope` 里切 `Dispatchers.IO`，与现状一致。
 */
fun interface AppEventSink {

    fun onEvent(event: AppEvent)
}
