# FN 共享相册

一个只读的飞牛（fnOS）共享相册电视端客户端（Android TV）。

家里的相册里有大量不想公开的照片。这个应用的做法是：在 NAS 上单独开一个账号，
家人把自己愿意让全家看的那几个相册共享给它，电视只登这个账号——于是电视能看到的，
正好就是被共享出来的那一部分，物理上看不到别的东西。

它不提供上传、删除、编辑，也没有任何把照片导出的入口。

## 功能

- **登录**：账号密码 + 可选访问码，自签证书首次信任；退出登录后设置已记住，下次开机自动登录。
- **相册墙**：只列 `album_grant/list_to_me`（别人共享给我的），按共享人分组；磨砂玻璃背景跟随当前相册。
- **照片网格**：每页 60 张，滚到剩两行时自动续页；视频与照片混排。
- **全屏看片**：左右键手动翻页；OK 开关幻灯片（顺序或随机、可设间隔、可循环、自动跳过视频）。
- **视频**：点开即从首帧起播，声画同步；暂停、播完、快进有对应提示。
- **背景音乐**：可用电视本地音乐，或登录飞牛音乐后播放歌单；歌词两列竖排显示。
- **设置**：显示/焦点动效、相册/相片背景、索引与缩略图缓存、闲置保护、飞牛音乐登录、退出登录。
- **新版本提醒**：冷启动后台检查 GitHub/Gitee 的 Release，有更新时在相册墙弹窗，下载后经系统确认安装。

返回键在看片页先收起菜单或信息框，再按一次才退出。遥控器菜单键在相册墙/照片墙打开设置，在看片页显示照片信息。

## 运行

用 Android Studio 或 Gradle 8.14 构建 `app` 模块，安装到 Android TV（`minSdk 26`）：

```sh
gradle :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

首次启动填入 NAS 地址（只填 IP，如 `192.168.1.100`，不带端口）、账号、密码，按需填访问码；
自签证书会弹一次信任确认。发行版安装包见
[GitHub Releases](https://github.com/ASH-C776/fn-share-tv/releases) 或
[Gitee Releases](https://gitee.com/kijeye/fn-share-tv/releases)。

## 关于来源

这是一个独立实现，代码从零开始写，与任何现有客户端没有代码级共用。

飞牛相册的接口没有公开文档，协议细节参考了两个公开的逆向项目：
[FNOSP/fnnas-api](https://github.com/FNOSP/fnnas-api)（MIT）与
[HelloWangXiangDong/fnAlbum](https://github.com/HelloWangXiangDong/fnAlbum)（MIT）。
接口结构、字段名、签名算法属于事实，不受版权保护；实现是本仓库自己写的。

内置字体「FN Lyrics Kai」基于霞鹜文楷 Lite（SIL OFL 1.1），许可证见
`app/src/main/assets/licenses/OFL-FN-Lyrics-Kai.txt`。

## Project structure

- `app/src/main/java/com/kijeye/fnshare/core/` — Shared HTTP client, image budget, and dispatch helpers.
- `domain/model/` — Pure data models and version-comparison helpers.
- `domain/port/` — Interfaces for photos, cache, settings, music, lyrics, and updates.
- `domain/usecase/` — Pure Kotlin rules for slideshow order, grid rows, and timeline grouping.
- `data/fn/` — fnOS login, signing, encryption, and the read-only photo API.
- `data/local/` — SharedPreferences and on-disk album cache.
- `data/music/`, `data/lyrics/` — Local media scanning and fnMusic catalog/streaming clients.
- `data/remote/` — Photo repository and GitHub/Gitee release updates.
- `engine/api/`, `engine/impl/` — Image, video, audio, and lyrics engines behind interfaces.
- `presentation/state/`, `presentation/event/` — UI state snapshot and one-way event sink.
- `ui/` — Compose TV screens: login, wall, grid, viewer, settings, and dialogs.
- `di/` — Manual dependency container wiring all implementations.
- `app/src/test/` — JVM unit tests for pure rules, state guards, and protocol behavior.
- `docs/ARCHITECTURE.md` — Layer map and bug-fixing guide.
- `tools/` — Font subsetting helper for the bundled lyrics font.
