# Battery HD Pro — Android

## 构建

本机的工具链是手动安装的（未用 Android Studio），路径如下：

```bash
export JAVA_HOME=/Users/ming.yang/tools/jdk/jdk-17.0.20.1+1/Contents/Home   # Temurin 17（Gradle 8 / AGP 8 硬性要求）
export ANDROID_HOME=/Users/ming.yang/Library/Android/sdk                    # platform-34 + build-tools 34.0.0
export PATH=$PATH:/Users/ming.yang/tools/gradle-8.9/bin                     # Gradle 8.9

gradle :analytics-sdk:assembleDebug     # 埋点 SDK（aar）
gradle :app:assembleDebug               # 主工程（apk）
gradle :app:assembleRelease             # 需先配签名（见 release 小节）
```

> **本机环境的一个坑**：Gradle 清理 `build/` 目录时可能失败（macOS 沙箱对批量删除有限制），
> 表现为 `Unable to delete directory .../build/tmp/kotlin-classes/debug`。
> 解决办法二选一：手动把 `build` 目录移走（`mv build /tmp/xxx`），
> 或在 `~/.gradle/init.gradle` 里把构建输出重定向到临时目录：
> ```groovy
> gradle.allprojects { p -> p.layout.buildDirectory.set(p.file("/tmp/bhd-build/" + p.name)) }
> ```

### release 构建

`assembleRelease` 会启用 R8 混淆与资源压缩（规则见 `app/proguard-rules.pro`）。
签名配置尚未写入 `app/build.gradle.kts`，接入 Play 前需补：

```kotlin
signingConfigs {
    create("release") {
        storeFile = file(System.getenv("BHD_KEYSTORE") ?: "bhd.keystore")
        storePassword = System.getenv("BHD_KEYSTORE_PASSWORD")
        keyAlias = System.getenv("BHD_KEY_ALIAS")
        keyPassword = System.getenv("BHD_KEY_PASSWORD")
    }
}
buildTypes { release { signingConfig = signingConfigs.getByName("release") } }
```

凭据一律走环境变量，禁止入库。


```
android/
├── settings.gradle.kts
├── analytics-sdk/          自建轻量埋点 SDK（PRD F-010 / 10.3）
└── app/                    主工程：电池管理 + 广告 + 订阅
    ├── build.gradle.kts
    └── src/main/java/com/batteryhd/app/
        ├── BatteryHdApp.kt                 Application：同意优先 → 初始化 SDK / 广告 / 内购
        ├── util/Prefs.kt                   SharedPreferences 封装
        ├── battery/BatteryRepository.kt    电量采样（BroadcastReceiver + 轮询兜底）
        ├── battery/ChargeSessionTracker.kt 充放电会话推导（插拔/满电/上限提醒）
        ├── battery/BootReceiver.kt         开机重启监控
        ├── ads/AdsManager.kt               AdMob 加载、展示频次与冷却、收入上报
        ├── billing/BillingManager.kt       Play 订阅（客户端只做漏斗，真相源是 RTDN）
        └── ui/                             MainActivity + 6 个 Fragment + Pro/Consent 页
```

    ├── build.gradle.kts
    └── src/main/
        ├── AndroidManifest.xml
        └── java/com/batteryhd/analytics/
            ├── Dictionary.kt         事件与枚举字典（与服务端 config/enums.php 同源）
            ├── Analytics.kt          SDK 门面：初始化、track、生命周期、采样
            ├── Models.kt             数据模型与配置
            ├── ConsentManager.kt     同意状态（必须在 init 之前读取）
            ├── SessionManager.kt     会话与匿名设备 ID
            ├── DeviceInfo.kt         公共属性采集（合规红线内）
            ├── EventStore.kt         SQLite 持久化 + FIFO 淘汰 + TTL
            ├── EventUploader.kt      批量上报 + 指数退避 + 时钟校正
            ├── ApiClient.kt          HTTP（零第三方依赖，gzip 压缩）
            └── AdConfigManager.kt    广告配置门面 + 三级降级 + 熔断
```

## 集成

### 1. 隐私同意优先（不可省略）

```kotlin
// Application.onCreate()
if (!ConsentManager.hasDecided(this)) {
    // 未表态 → 展示隐私政策，SDK 暂不初始化
    showPrivacyDialog { analyticsAllowed, adsPersonalized ->
        ConsentManager(this).setConsent(analyticsAllowed, adsPersonalized)
        if (analyticsAllowed) initAnalytics()
    }
} else {
    initAnalytics()
}
```

> **为什么必须这样**：若先 `Analytics.init()` 再读同意状态，启动瞬间的事件已经被采集，
> 在印尼 PDP Law / 泰国 PDPA 下等同于"未获同意即收集"。

### 2. 初始化

```kotlin
private fun initAnalytics() {
    Analytics.init(this, AnalyticsConfig(
        apiBaseUrl = "https://api.batteryhd.app",
        appVersion = BuildConfig.VERSION_NAME,
        admobAppId = "ca-app-pub-xxxxxxxxxxxxxxxx~yyyyyyyyyy",
        // L0 内置兜底：必须有值，否则无网时该广告位无法展示
        defaultAdUnits = mapOf(
            "banner_home" to "ca-app-pub-3940256099942544/6300978111",
            "interstitial_after_calibration" to "ca-app-pub-3940256099942544/1033173712"
        ),
        logging = BuildConfig.DEBUG
    ))
}
```

### 3. 埋点

```kotlin
// 页面浏览（自动维护 previous_screen 与 session_depth）
Analytics.trackScreen(Dictionary.Screen.HOME_DASHBOARD, previousScreen)

// 普通事件
Analytics.track(Dictionary.Event.CHARGE_LIMIT_TRIGGERED, mapOf(
    "limit_percent" to 80,
    "current_percent" to 80,
    "temperature_c" to 36.5
))

// 布尔必须是 boolean、数值必须是 number，不要用字符串
Analytics.track(Dictionary.Event.BATTERY_HEALTH_VIEWED, mapOf(
    "health_score" to 88,        // Int，不是 "88"
    "is_pro" to false            // Boolean，不是 "false"
))
```

### 4. 广告配置使用

```kotlin
// 请求广告前先解析 unit_id；返回 null = 不应请求（总开关关/熔断/冷却）
val unitId = Analytics.ads.resolveUnitId("banner_home")
if (unitId != null) {
    loadBanner(unitId)
}

Analytics.ads.recordSuccess("banner_home")            // 加载成功
Analytics.ads.recordFailure("banner_home", errorCode) // 加载失败（触发熔断计数）
```

## 关键设计约束

| 约束 | 说明 |
|---|---|
| 主线程 ≤ 5ms | `track()` 只做校验 + 内存入队，落盘与网络全在 IO 协程 |
| 幂等 | 重试**复用同一 batch_id**，服务端据此去重；每次重试生成新 ID 会导致指标虚高 |
| 采样稳定性 | 按 `crc32(deviceId + eventName)` 分桶，同一设备结果恒定；随机采样会让漏斗断裂 |
| 时钟校正 | 客户端时钟不准（低端机常见），每次响应记录服务端时间计算 offset，>24h 偏移服务端会拒收 |
| 关键事件不丢弃 | 队列满时 FIFO 淘汰，但 `calibration_completed` / `ad_revenue_paid` / `purchase_completed` 豁免 |
| 广告降级链 | 远端 → 本地缓存 → 内置默认 → 关闭该位，**任何情况下不请求空 unit_id** |
| 熔断 | 连续失败达阈值进入冷却，避免用过期/错误 ID 狂刷被判无效流量 |

## 同步约定

`Dictionary.kt` 是服务端 `backend/config/enums.php` 的镜像。
**任一侧改动必须同步另一侧**，否则服务端会拒收事件（写入 `analytics_events_rejected`）。

服务端自检命令：

```bash
php artisan analytics:lint-dictionary --strict   # 字典一致性
php artisan analytics:lint-dictionary --test     # 29 项枚举校验冒烟
```

## 埋点契约自检（改完埋点必做）

编译能过 ≠ 数据能落库。服务端对事件有两层校验，不匹配的部分会被**静默剥离或拒收**：

| 校验 | 不通过的后果 | 检查方法 |
|---|---|---|
| 属性白名单（`config/analytics.php` 的 `props`） | 多余属性被剥离，事件本身保留 | 逐个比对 `Analytics.track` 的 mapOf 键名 |
| 枚举取值（`config/enums.php` 的 `values`） | 事件被拒收，写入 `analytics_events_rejected` | 比对传入的字符串字面量 |

已踩过的坑（本轮修复）：

- 属性名写错：`start_level` / `end_level` 应为 `start_percent` / `end_percent`
- 属性不属于该事件：`feature_gate_shown` 不带 `source`（那是 `pro_page_viewed` 的）
- 枚举值不在字典内：`onboarding_step_completed.step_name` 写了 `privacy_consent`，合法值里没有
- 必传属性漏传：`ad_load_failed` 缺 `network_type`、`ad_requested` 缺 `network/trigger_scene`，
  缺了就算不出填充率
- **状态型事件没有去重**：温度告警、充电限值触发在 UI 每次刷新都会命中条件，
  必须冷却或按会话去重（见 `ui/FragmentAnalytics.kt`），否则指标虚高几十倍


## 新增能力（PRD 补齐轮次）

| PRD 章节 | 实现位置 |
|---|---|
| 10.2.4 A `app_foregrounded` | `Analytics.observeLifecycle()`：会话未超时只记后台时长，不重开会话 |
| 10.2.4 B `screen_exited` | `Analytics.endCurrentScreen()`：由 `trackScreen` 自动配对结算，退后台也会补发 |
| 10.2.4 C 通知权限结果 | `SettingsFragment.askNotificationPermission()`（同时上报 `permission_requested` 作为拒绝率分母） |
| 10.2.4 D 校准流失 | `ChargeFragment` 三步校准，`calibration_abandoned`（user_cancel / app_killed） |
| 10.2.4 D 周报 | `MonitorFragment.showWeeklyReport()`，数据来自 `Prefs.recordWeeklySession()` 的周聚合 |
| 10.2.4 H 通知触达 | `notification/NotificationHelper.kt` + `MainActivity.handleNotificationIntent()` |
| 10.7.3 同意凭证 | `Analytics.syncConsent()` → `POST /api/v1/analytics/consent`（独立端点，不走事件通道） |

### 两个容易写错的点

**停留时长**：`screen_exited.duration_ms` 是「停留时长」看板的唯一数据源。
SDK 自动在 `trackScreen` 与退后台时结算上一页，宿主页不必（也不应）手动配对上报——
少调一处该页时长就永久缺失。

**同意凭证必须走独立端点**：撤回同意后事件通道会被关闭，
若把「撤回」塞进事件队列，这条记录永远发不出去，服务端会一直以为用户仍同意。
