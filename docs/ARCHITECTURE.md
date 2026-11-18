# 架构地图（写 bug / 修 bug / 加功能前先读这页）

> 适用于 `fn-share-tv`（包名 `com.kijeye.fnshare.debug`，应用名 FN 共享相册）。
> 本文对应提交 `e9f0bab` 之后的分层结构。代码每个新文件的头部都有
> 「提取自哪个原文件 + 生命周期清理在哪调用」的注释，与本文互为索引。

## 1. 分层总图（依赖方向，箭头只能朝右/朝下）

```
 MainActivity（组合根，422 行）
   │  写侧：sink.onEvent(AppEvent.X)      读侧：collect model 的 StateFlow
   ▼
 ui/  ════════════ 只做三件事：渲染、焦点、动画 ════════════
   │   AppViewModel（1386 行）——唯一的状态与业务协调者
   │     ├── 实现 AppEventSink（presentation/event）：onEvent() = when 分派 45 个事件
   │     ├── 状态：StateFlow<AppState>（presentation/state）+ musicPlaying/failed/
   │     │        lyricsDoc/lyricIndex 四个独立 StateFlow
   │     └── 依赖全部来自 di/AppContainer 注入的接口
   ▼
 domain/  ═════ 纯 Kotlin，零 Android / 零 OkHttp 依赖 ═════
   │   model/    5 组数据模型（PhotoModels/MusicEntry/LyricsDoc/TimelineModels/GridRow）
   │   port/     5 个数据源契约（PhotoSource/SettingsSource/AlbumCacheSource/
   │             MusicSource/LyricsSource）
   │   usecase/  纯函数（BuildTimelineUseCase/BuildGridRowsUseCase，object+operator invoke）
   ▼
 data/  ═════ 网络与设备 IO ═════          engine/  ═════ 媒体引擎 ═════
   fn/       fnOS 协议栈（登录/签名/请求）      api/    6 个接口（Audio/Image/
   cache/    相册元数据磁盘缓存                         Video/LyricsTicker/
   local/    SharedPreferences / 缓存适配器             ImageRequestFactory…）
   music/    本地曲库扫描 + 背景乐播放器               impl/   Coil/ExoPlayer 实现
   lyrics/   LRC 解析 + 署名过滤
   remote/   PhotoSource 适配器 + 共享 OkHttp        core/  AppDispatchers/ImageSizes/
                                                    SharedHttpEngine（横切小件）
   di/AppContainer（114 行）═══ 唯一认识所有具体实现的地方 ═══
```

**铁律**（改代码前自查）：
- UI 不发网络请求、不碰 File/OkHttp/ExoPlayer/Coil 实例；只调 `ImageRequestFactory` 拿请求。
- domain 不 import 任何 `android.*` / `coil3.*` / `media3.*` / `okhttp3.*`。
- 只有 `di/` 允许写 `= 某具体实现类(...)`；其它层一律 `val x: 某接口`。
- 数据流单向：UI 发 `AppEvent` → VM 改 StateFlow → UI collect 重组。禁止 UI 直接改状态。
- 动画参数、焦点逻辑、播放器配置是**保护对象**：改这些前先对照本文第 5 节的陷阱清单。

## 2. 目录地图（找文件用）

| 位置 | 职责 | 关键文件（行数） |
|---|---|---|
| `MainActivity.kt` | 组合根：建 VM、装 ImageLoader（`setContent` 之前）、算屏幕像素、所有屏的 when 分发与事件回填 | 422 |
| `ui/AppViewModel.kt` | 全部业务协调：登录/相册/缓存/幻灯片/音乐/歌词/闲置保护 | 1386 |
| `ui/ViewerScreen.kt` | 全屏看图：翻页、三档缩放、一级/二级菜单、幻灯片调度、视频状态牌 | 1347 |
| `ui/PhotoStage.kt` | 双缓冲图片槽（叠化 + Ken Burns）| 391 |
| `ui/GridScreen.kt` / `WallScreen.kt` | 照片网格 / 相册墙；时间轴入口在 GridScreen | 539 / 428 |
| `ui/FocusFrame.kt` | 焦点画框（外框 1.05/220ms、内图 1.15 进 320 出 220）+ 卡片图 | 254 |
| `ui/Backdrop.kt` | 焦点跟随毛玻璃背景（300ms 防抖、双图层 500ms 交叉淡化）| 199 |
| `ui/MusicDialog.kt` | 音乐弹窗：顶部四格工具条 + 二维光标 + 曲目列表 | 499 |
| `ui/SettingsScreen.kt` | 设置页（含缓存统计分组）| 644 |
| `ui/LyricsOverlay.kt` | 竖排两列歌词浮层（当前句 + 下一句，FN Lyrics Kai 楷体）| 226 |
| `ui/VideoPlayer.kt` | 视频渲染壳：PlayerView 开关 + 250ms/1s 轮询 | 142 |
| `ui/TimelineRail.kt` | 时间轴跳转浮层 | 443 |
| `ui/AppModels.kt` | 兼容层：`AppState` 等 typealias 指回 presentation/state | 28 |
| `ui/SlideShowConfig.kt` / `FocusRingColors.kt` / `TvScroll.kt` / `IdleGuard.kt` / `Components.kt` | 幻灯片参数档位 / 焦点圈色 / 自定义滚动曲线 / 闲置计时 UI / 公共小组件 | 小 |
| `presentation/state/AppState.kt` | 唯一的界面状态模型（含 `Screen` sealed interface）| 160 |
| `presentation/event/AppEvent.kt` | 45 个事件的 sealed hierarchy | 91 |
| `domain/usecase/` | 时间轴分组、网格分行（纯函数，有等价性单测） | 107/118 |
| `data/fn/` | fnOS 协议：`FnLogin` 登录握手、`FnApi` 请求、`FnSign/FnCipher/FnCrypto` 签名加密、`FnTrust` 自签证书 pin | — |
| `data/cache/AlbumCache.kt` | 相册元数据缓存（`filesDir/album_cache/`，256MB 上限）| 379 |
| `data/music/` | `MusicScanner` 扫电视 music 目录、`SlideshowMusicPlayer`（被 `ExoAudioEngine` 包住）| 338/243 |
| `data/lyrics/` | `LrcParser`（含署名过滤）、`LyricsRepo` 按曲目缓存 | 177/118 |
| `engine/impl/` | `CoilImageEngine`（全局唯一 ImageLoader，缩略图盘存 3GB）、`CoilImageRequestFactory`（四个请求构造点唯一出处）、`ExoAudioEngine`、`ExoVideoEngine`、`DefaultLyricsTicker`（100ms 轮询）| — |
| `di/AppContainer.kt` | 装配单例：http/settings/photoSource/albumCacheSource/musicSource/lyricsSource/imageEngine；`audioEngine()`/`lyricsTicker()` 按 VM 工厂产出 | 114 |

## 3. 运行时数据流

**启动**：`MainActivity.onCreate` → `viewModel.get<AppViewModel>()` → `model.installImageEngine()`
（全局注册 Coil，**必须在 setContent 前**，否则首批图全灰）→ `setContent { App(model) }`。

**一次按键的一生**（以设置页改幻灯片秒数为例）：
```
遥控器 OK
→ SettingsScreen 的 onSlideSeconds 回调
→ sink.onEvent(AppEvent.SlideSeconds(n))          // UI 从此只认识事件
→ AppViewModel.onEvent() when 分派 → setSlideSeconds(n)
→ settings 持久化 + _state.update { copy(slideSeconds=n) }
→ MainActivity collect 到新 state → SettingsScreen 重组显示新值
```
例外只有两个：`skipMusicTrack()` 保留直调（按键层要 Boolean 返回决定「没歌可切就退回翻页」）；
`onKeyDown` 的菜单键在 Activity 层收（Compose 按键链收不到）。

**幻灯片一帧的一生**：`ViewerScreen` 的 `LaunchedEffect` 按 `slideSeconds` 计时 →
`onAutoStep` 事件 → `autoStep()` 算下一张 → state 变 → `PhotoStage` 双缓冲叠化，
`CoilImageRequestFactory.photoRequest()` 出请求，邻张经 `model.enqueueImage()` 预取。

**歌词**：`LyricsTicker`（`engine/impl/DefaultLyricsTicker`）每 100ms 读播放器曲目与位置，
只写 `doc`（换曲）和 `index`（换行）两个 StateFlow → `LyricsOverlay` collect 渲染竖排两列（当前句 + 下一句）。

## 4. 生命周期与资源（谁建谁收）

| 资源 | 建在哪 | 收在哪 |
|---|---|---|
| `CoilImageEngine`（全局唯一 ImageLoader） | `AppContainer.imageEngine`，进程级单例 | **刻意不 close()**：VM 重建后再 shutdown 会满屏灰块；随 OS 回收 |
| `AudioEngine`（背景乐 ExoPlayer） | `container.audioEngine()`，每个 VM 一份 | `AppViewModel.onCleared()` → `release()` |
| `LyricsTicker`（100ms 轮询 Job） | `container.lyricsTicker(scope, audio, shouldRun)`，每个 VM 一份 | `onCleared()` → `cancel()`；幻灯片/歌词关时 `ticker.stop()` |
| `ExoVideoEngine`（视频播放器） | `ui/VideoPlayer.kt` 的 `remember { engine.create(...) }` | 同文件 `DisposableEffect(engine)` 的 `onDispose → engine.release()` |
| OkHttp 客户端 | `AppContainer.http`（包 `FnRepo.client`，带证书 pin 与 AccessToken 拦截器） | 进程级，不收 |
| 相册元数据缓存 | `filesDir/album_cache/` | 退出登录时 `clearAll()`（同步，非生命周期） |

⚠️ **全局只能有一个 ImageLoader**：同目录两个实例会损坏 `coil3_disk_cache` 的 journal。

## 5. 已知陷阱（改这些地方之前先看）

1. **叠化失效（0.17.1 修过）**：同一张照片「换请求」时必须在重建 model 的**同一处**
   同步复位「已画出」标记（`PhotoStage` 的 `remember(photo.id, requestKey)` factory）。
   绝不能用 `SideEffect` 事后比对——Coil 命中内存缓存时 `onSuccess` 是同步回调，
   比它还早，事后复位会让该层永不绘制。
2. **`DisposableEffect` 的 key 不能放 getter**：`videoDataSourceFactory` 是 VM 的 getter、
   每次重组新建。第 6 批曾把它加进 key 导致播放器反复 release/attach，症状是视频卡在
   「暂停 00:00」且 logcat 零报错。视频页 key 只有 `engine`。
3. **Coil `.size()` 是解码框不是提示**：任何源图都会被降采样进这个框。原图必须乘
   `ORIGINAL_DECODE_SCALE=2.0`（常量在 `CoilImageRequestFactory`），否则「原图」白开。
   全部请求都带 `Precision.INEXACT`（只降不升）。
4. **内存/磁盘缓存上限**：内存 64MB、缩略图磁盘 3GB，常量在 `CoilImageRequestFactory`
   伴生对象与 `CoilImageEngine`。上限不落盘，改常量对已有缓存目录立即生效，无需迁移。
5. **fnOS 的 `photoCount` ≠ `/album/photos` 返回条数**（视频混在照片列表里）。
   缓存过期判定只能拿相册列表 `photoCount` 和缓存**快照**比，拿条数比会永远判「变了」。
6. **电视共享存储 `File.listFiles()` 能列 ≠ 能 open()**：本地音乐一律走
   MediaStore `content://`（见 `MusicScanner`），`file:` 只做未索引兜底。MuMu 上测不出
   这类问题（它存储栈正常），别用 MuMu 验证权限类 bug。
7. **歌词两列的盒宽 = 字号，钉死**：字号绝不随句长变（一变邻列平移）；超长只压行距。
   署名过滤在 `LrcParser.isCreditLine`，改它要拿五份真实 lrc 回归。
8. **Kotlin typealias 三坑**：不能访问嵌套类（`GridRow.Day` 需顶层别名）；不能用于
   超类型列表（`object : VideoController` 要真接口）；注释里写 `ui/*.kt` 会因嵌套注释
   吃掉文件后半段。
9. **本地媒体文件名/中文路径**：adb push 中文名会失败，用 ASCII 硬链接 push + 设备端 mv。

## 6. 定位 bug 速查表（症状 → 先看哪）

| 症状 | 第一嫌疑 | 第二嫌疑 |
|---|---|---|
| 首屏图片全灰 | `installImageEngine()` 是否在 `setContent` 前调（MainActivity） | OkHttp 拦截器没带 token（`RepoSharedHttpEngine`/`FnTrust`） |
| 图片糊 / 原图没变清晰 | `CoilImageRequestFactory` 的 `.size()` 算式 | `Precision.INEXACT` 丢了 |
| 翻页不叠化 / 幻灯片停在一张死图 | `PhotoStage` 的 model 复位时机（陷阱 1） | 预取 key 与显示 key 不一致 |
| 焦点框不动 / 跳动 | `FocusFrame`（动画参数全在这一个文件） | GridScreen/WallScreen 的 `scrolling` 透传 |
| 网格滚动卡顿 | FocusFrame 绘制期 vs 组合期读动画值 | `TvScroll` 的 BringIntoView 曲线 |
| 视频卡「暂停 00:00」 | `VideoPlayer` 的 `DisposableEffect(engine)` key | `ExoVideoEngine` 的监听器/取流工厂 |
| 背景乐不出声 | `ExoAudioEngine` + 音频焦点；见第 5 节后确认 | `MusicScanner` 扫描结果（MediaStore content://） |
| 歌词不同步 / 不出现 | `DefaultLyricsTicker` 的 shouldRun 条件 | `LrcParser` 解析 / `LyricsDoc.indexOf` 二分 |
| 缓存统计不对 | `CoilImageEngine.diskBytes()`（同步 API） | `AlbumCache.stats()` 口径 |
| 时间轴跳错位置 | `BuildTimelineUseCase`（有单测，先跑测试） | `TimelineRail` 的定位（按 photo id） |
| 往回翻看不到相册名 | `GridScreen.atTopPhotoRow()` 的 Up 分支（相册名是第 0 项、不可获焦，bringIntoView 不会滚到头） | 相册名 header 是否被误改成可获焦/移出列表 |
| 登录 401 / 证书弹窗 | `FnTrust` pin 持久化（prefs） | `FnLogin` 握手 / `FnCookies` 过期 |

## 7. 加新功能的标准路径

**加一个设置项**（例：加「幻灯片方向」）：
1. `presentation/state/AppState.kt`：加字段 + 默认值；
2. `data/FnSettings.kt`：加持久化键（`PrefsSettingsSource` 自动透传）；
3. `presentation/event/AppEvent.kt`：加一个事件类；
4. `ui/AppViewModel.kt`：加 `setXxx()`（持久化 + `_state.update`），`onEvent()` 加一行分派；
5. `ui/SettingsScreen.kt`：加行（用现成 `FocusableSurface` + `ValuePill`）；
6. `MainActivity.kt`：把回调改成 `sink.onEvent(...)`；
7. `res/values/strings.xml` + `values-en/`：加文案。

**加一种数据能力**（例：加「往年今日」）：
1. `domain/model/` 定义模型，`domain/port/` 定义契约；
2. `data/` 写实现（网络走 `AppContainer.http.client`，磁盘走 `filesDir` 不走 `cacheDir`）；
3. `di/AppContainer.kt` 装配一行；
4. `domain/usecase/` 写纯函数并配单测（参照 `PureFunctionParityTest` 的等价性写法）；
5. VM 加方法 + 状态字段，UI 只 collect。

**加一块 UI**（例：加新屏）：`presentation/state` 的 `Screen` 加子类 →
MainActivity 的 `when` 加分支（`contentKey` 按 `it::class` 已天然支持）→ 新文件放
`ui/`，参数只有 state + 回调；请求一律 `requestFactory.cardRequest/photoRequest`。

## 8. 构建与测试速查

```bash
# 编译（无 gradlew，用本机 gradle）
cd fn-share-tv && GRADLE_USER_HOME=D:/Android/.gradle /d/Android/gradle-8.14.5/bin/gradle :app:compileDebugKotlin

# 单测 + 出包（26 例，含时间轴/分行纯函数的等价性测试 PureFunctionParityTest）
... :app:testDebugUnitTest :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk

# 装到 MuMu（192.168.1.xx:5555；电视只装不测，装前确认没在放视频）
MSYS_NO_PATHCONV=1 /d/Android/Sdk/platform-tools/adb connect 192.168.1.xx:5555
MSYS_NO_PATHCONV=1 /d/Android/Sdk/platform-tools/adb install -r app-debug.apk
```

- **动设备前先备份设置**：`adb exec-out run-as com.kijeye.fnshare.debug cat shared_prefs/fn_share.xml` 落盘
  （含访问码、账号、证书 pin、全部开关），测完可 push 回去，否则要重新登录。
- MuMu 系统动画缩放默认 0.5：量动画时长前先 `settings get global animator_duration_scale`。
- 连续按键/录屏必须写在**同一条** `adb shell '...'` 里（每条独立命令会重启 adb daemon）。
- 版本号：只改 `app/build.gradle.kts` 顶部 `appVersionName`；`versionCode` 自动推导
  （加功能 1.0→1.1，修 bug →1.0.1）。
- 提交约定：每个可独立回退的批次一个提交；出问题 `git log --oneline` 找点，`git reset --hard <点>`。
