plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/*
 * 版本号：加功能 0.4 → 0.5，只修 bug 0.4 → 0.4.1。
 * versionCode 由 versionName 推导，不手写——手写的那个总是忘。
 */
// 0.24.2 这个号已经发出去又回退过，不复用，便于在设备上分清装的是哪一版。
// 0.24.9：视频待播不再叠图片封面，改为「加载转圈 → 首帧直接播放」。
// 0.24.10：首帧门控开播（加载期无声、撤层即 00:00 首帧），删「待播」状态牌。
// 0.24.11：退出/切片立即断声画，补齐播放器停止与释放顺序，封死退出后首帧回调再开播。
// 0.24.12：代码审查七项 Major 收口——停止后无状态回流、超时只静音推进不撤层、
// 闲置/后台统一断流、坏片源提示、加载门闩按实例复位、引擎走 DI、补 LOADING 态。
// 0.24.13：播放失败提示附带错误码（电视取不到 logcat）、删英文资源；播放行为不变。
// 0.24.14：静态兼容收口——minSdk 23→26、lint 37 error→0、播放逻辑未动。
// 0.24.15：性能收口——焦点背景两层 blur 合为一次、描边颜色整页只解析一次。
// 0.25.0：看图页内 ←/→ 翻到的视频先显示静止封面（不创建播放器），按 OK 才起播；
// 封面盖到首帧真到才整块撤掉。网格直接点视频的播放链路保持不变。
// 0.25.1：修 MuMu 实测到的越界——起播授权改按「本次停留」记，翻到照片再翻回
// 同一条视频不再自动续播，仍显示静止封面等按 OK。
// 0.25.2：修电视上「静止封面 → 按 OK → 黑帧 + 声音先于画面」。渲染壳换 TextureView
// （SurfaceView 的空黑 layer 顶掉封面层），首帧交接延到下一次 vsync 统一放行。
// 0.25.3：文案收口——删静止封面的「按 OK 播放」、删播放中的「播放」状态牌，
// 暂停牌改为「已暂停，按 OK 继续」。只动文案与显示条件，不动播放/封面链路。
// 0.26.0：幻灯片随机改为「开播排一次次序表、一轮之内不重不漏」；
// 手动翻页固定按相册顺序 ±1，删掉原先「幻灯片+随机时手动也随机跳」的分支。
// 0.26.1：随机一轮补齐整本——看图页也能续页，表快见底提前取下一页，新页照片
// 接到表尾；整本取完才按循环开关重洗或干净收尾。视频与手动翻页链路未动。
// 0.26.2：幻灯片开着时人手翻到视频，这一格归人——静止封面停住、按 OK 能完整播，
// 播放/暂停/播完都不自动带走；幻灯片自己前进仍跳过视频。渲染壳与封面层未动。
// 0.26.3：修「换相册串照片」——网络页回来、后台索引补页、整本清单落缓存三处都先
// 核对当前屏还是不是发出请求的那一本相册；换相册时顺带作废旧的自动前进待办。
// 0.26.4：修「按 OK 暂停后仍被上报成在播」——乐观上报只活到真实播放第一次起来，
// 之后听播放器的真实状态；暂停牌因此能显示、闲置保护也能正常计时。附带文案与注释收口。
// 0.27.0：接住服务端一直给的像素宽高、视频时长、真实文件大小——信息栏多一行
// （视频「01:23 · 1080 × 1920」、照片「4096 × 3072」），视频大小改用真实文件大小。
// 清单缓存格式升到 v2，旧缓存自动作废、后台重新索引一次。播放与翻页链路未动。
// 0.28.0：相册网格页按菜单键也能进设置，返回时回到原相册（滚动位置按相册 id 保持）。
// 0.28.1：登录页地址提示改成不带端口的例子（如 192.168.1.66）。地址解析本来就会
// 在缺端口时补 5666（TLS 5667），提示里带着端口反而让人以为必须写。
// 0.28.2：修「相册墙按上聚焦右上角设置后，按下回不到相册」——第一张卡用的是单独的
// requester，交还焦点时取到的是没挂到卡片上的空 requester，调 requestFocus 既不移动
// 也不报错。改成所有卡片共用 requesterOf(id) 一套。
// 0.29.0：换标识——应用名改「FN 共享相册」，图标换成飞牛品牌蓝 #0066FF 纯色底 +
// 白色相框（前景/背景两层，启动页与电视横幅同造型）。只动资源与名字，界面逻辑未变。
// 0.29.1：启动页只留一层——接入 core-splashscreen 接管冷启动，删掉 Compose 自绘的
// 启动图。以前系统先画一层圆形图标、应用再画一层圆角方块，交接时肉眼能看到跳一下。
// 系统那层改用同造型的圆角方块图标，切换时不再变形。启动图最短停留仍 1.2 秒。
// 0.29.2：相册封面去掉左上角的「宝宝」二字角标（用户说不需要区分），右上角张数改为
// 单独靠右，顶行只剩这一段文字。
// 0.29.3：相册墙背景做磨砂玻璃质感——模糊 28→18dp（能辨出照片轮廓）、材质层由纯黑
// 0.48 改 #1A1A1A@0.35 + 降饱和 0.6、加静态噪点贴图（磨砂颗粒）、顶部一道亮边、
// 上下暗渐变减弱。只改 Backdrop.kt 与一张噪点图，布局/焦点/卡片未动。
// 0.29.4：启动页改回应用自绘——1.2 秒停留期间显示飞牛蓝启动页；系统层只铺同色底、
// 图标用全透明图（创维 ROM 不画系统 splash 图标，0.29.1 那套在电视上是黑屏直进相册）。
// 背景两层同色 + 图标只画一次，既看得见启动页又不会「圆→方」跳变。鉴权逻辑未动。
// 0.29.5：磨砂背景收淡——模糊 18→12dp（背景照片轮廓能读出来）、噪点 alpha 10~30→6~18
// （颗粒不扎眼）。两层叠加太重把背景图挤没了，这是一次观感回调，结构/焦点/卡片未动。
// 0.29.6：去掉背景图的降饱和滤镜（0.29.3 加的饱和度 0.6），背景保持 NAS 原图色彩。
// 0.30.0：设置页底部改成双列——左「更多」入口、右「退出登录」，全页 6 行统一双列；
// 焦点网格与方向导航同步。「更多」页内容下一步做，当前入口占位不跳转。
// 0.31.0：新版本提醒——冷启 5 秒后台查 Gitee/GitHub Release（一天一次），取两端最高
// 版本；有新版本在相册墙弹窗（取消/更新），点更新下载 APK 到私有目录后拉起系统安装。
// 独立干净 HTTP 客户端与 update_prefs，不碰 NAS 登录配置。更多页排版仍未做。
// 0.32.0：设置页「更多」入口接上真正的页面——应用名+版本、作者、GitHub/Gitee 开源地址、
// 使用说明；返回键与页面内「返回设置」都回设置页，不打断进设置前的返回目标。
val appVersionName = "0.32.0"
val appVersionCode = appVersionName
    .split(".")
    .map { it.toInt() }
    .let { it.getOrElse(0) { 0 } * 10000 + it.getOrElse(1) { 0 } * 100 + it.getOrElse(2) { 0 } }

android {
    namespace = "com.kijeye.fnshare"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.kijeye.fnshare"
        // 0.24.14：此前写 23，但代码里大量用 java.time（LocalDate/LocalDateTime/Period）
        // 与 Java 8 默认方法，这些在 API 26 才有；装到 Android 6/7 上会直接
        // NoClassDefFoundError。不引 desugaring（体积/方法数代价），改成诚实声明 26。
        // 目标设备（创维 A7E Pro / MuMu）都是 API 33+，行为零变化。
        minSdk = 26
        // 36 而不是 29：这个应用不碰共享存储。它只从 NAS 取图，缓存落在应用自己的
        // filesDir 里，不需要遍历任何公共目录，所以没必要为存储模型降版本。
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    testOptions {
        // OkHttp 的日志类会碰 android.util.Log，JVM 单测里那是会抛异常的桩。
        // 返回默认值让协议层能在没有设备的情况下被测。
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.18.0")
    // 系统启动画面接管（0.29.1）。以前这里是 Composable 自画一张启动图，和系统那层
    // 图标形状不同，冷启动时能看到跳变；这个库让系统那一层一直留到数据就绪。
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")

    implementation(platform("androidx.compose:compose-bom:2026.04.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    // 图片加载。它和接口客户端共用同一个 OkHttp 实例，这样缩略图请求也带着会话凭证。
    implementation("io.coil-kt.coil3:coil-compose:3.5.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.5.0")

    // 视频播放。ExoPlayer 必须复用 FnTrust 信任自签证书的 OkHttp 客户端取流——
    // NAS 是 https 自签证书，系统证书库不认，默认 DataSource 会直接 TLS 失败（已踩坑）。
    // 复用 repo.client 后，证书信任和 AccessToken 头都自动带上，和图片加载走同一个通道。
    implementation("androidx.media3:media3-exoplayer:1.7.1")
    implementation("androidx.media3:media3-ui:1.7.1")
    implementation("androidx.media3:media3-datasource:1.7.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.7.1")

    implementation("com.squareup.okhttp3:okhttp:5.2.3")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")
}
