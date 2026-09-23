# 10. 埋点与数据上报系统（F-010）— 修订版 v2.0

> **修订说明**：本版基于 PRD 初版第 10 章的评审意见修订，主要变更：
> ① 事件体系重做为逐事件清单并引入 Tier 分级，修复数量口径矛盾；
> ② 修复 `package_name` 上报违反合规承诺的冲突；
> ③ 补齐幂等去重、注册防护、写入链路、订阅真相源等后端缺口；
> ④ **新增 10.5 广告配置远程下发（自建方案 B）**；
> ⑤ 补齐数据保留期、删除权等合规条款；
> ⑥ 验收标准改为可量化条目；⑦ 重排里程碑。
> 详细变更对照见 **附录 B**。

---

## 10.0 前提与依赖

**分发渠道（已确认）**：**仅通过 Google Play 分发**，目标设备具备 GMS（Google Mobile Services）。以下能力按"可用"设计，无需降级方案：

| 能力 | 依赖组件 |
|---|---|
| 广告变现 | AdMob（含 `OnPaidEventListener` impression 级收益回传） |
| 订阅验证 | Google Play RTDN + Play Developer API |
| 设备真实性校验 | Play Integrity API |
| 配置变更唤醒 | FCM 静默推送 |
| 崩溃监控 | Firebase Crashlytics |

> ⚠️ 若后续新增非 Play 渠道（华为 AppGallery、第三方商店等无 GMS 设备），上述五项需重新评估并补充降级方案，属于**架构变更**，需重新评审工期。

**外部依赖（由其他模块提供，本文档不重复实现）**：

| 输入 | 提供方 | 约定 |
|---|---|---|
| `is_pro` | 订阅模块 | 支付回调成功后立即置位，并触发广告配置刷新（否则 Pro 用户仍会看到广告） |
| 充电状态 | 充电保护模块 | 埋点**订阅**该模块的充电开始/结束事件，不自行实现广播监听 |
| 权限申请结果 | 权限模块 | `permission_requested` / `permission_denied` 由该模块触发 |
| `country` | 服务端 | 按请求 IP 判定并在配置响应中回写客户端持久化；**埋点与广告配置必须使用同一取值** |

---

## 10.1 设计原则

1. **精简优先，分层管理**：每个事件必须有明确的业务分析目的，不做"以防万一"的埋点。事件按 Tier 分级（见 10.2.1），行业共识的"5~15 个核心事件"指的是**驱动业务决策的北极星事件**——本项目中对应 10.6 看板强依赖的 **12 个事件**；其余为支撑分析与问题定位的事件，按 Tier 管理、可采样、可远程关闭。
2. **代码埋点为主**：核心链路（充电保护触发、校准完成、Pro 购买、广告收入）由研发在代码层面精确埋点。
3. **命名规范统一**：事件名采用 **`对象_动作`** 范式，小写字母 + 下划线，动词统一使用**过去式**（`banner_shown` 而非 `banner_impression`）。
4. **属性最小化**：单事件属性 ≤ 20 个，JSON 嵌套 ≤ 2 层，禁止采集隐私信息（IMEI、手机号、精确位置、**其他应用包名**）。
5. **与广告系统隔离**：埋点数据不用于广告定向；广告 SDK 的数据收集独立于自建埋点。**广告配置下发链路独立于埋点开关**（用户关闭埋点后广告仍正常展示，见 10.5.1）。
6. **崩溃不自建**：崩溃监控交由 Firebase Crashlytics / Sentry，自建 SDK 只负责业务异常（见 10.2.5 说明）。

---

## 10.2 事件体系

### 10.2.1 Tier 分级与数量口径

| Tier | 定位 | 数量 | 采样 | 用途 |
|---|---|---|---|---|
| **T1 核心** | 驱动核心看板、留存、转化与变现漏斗 | **19** | 不采样（100% 上报） | DAU/留存、功能使用、广告表现、转化漏斗、异常监控 |
| **T2 诊断** | 功能优化与问题定位 | **22** | 受 `sampling_rate` 控制（稳定分桶） | 使用深度、场景分布、通知效果 |
| **T3 技术** | SDK 与配置链路健康监控 | **3** | 默认开启，可远程关闭 | 配置拉取成功率、熔断、配置异常 |
| **合计** | | **44** | | |

> 计数口径说明：初版 10.2.1 的"页面浏览 6 个"是**页面数**口径，与事件数口径混用导致总数对不上。本版统一为**事件数**口径，8 个页面通过 `screen_name` 枚举覆盖（10.2.3）。

### 10.2.2 公共属性（Common Properties）

以下属性由 SDK 自动附加到**每一个事件**，不占用单事件 20 个属性的额度：

| 属性 | 说明 | 来源 |
|---|---|---|
| `app_version` / `os_version` / `locale` / `country` | 版本、系统、语言、**国家**（国家级别，非精确位置） | DeviceInfoProvider |
| `device_model` / `manufacturer` | 机型与厂商 | DeviceInfoProvider |
| `network_type` | wifi / 4g / 3g / offline | 系统 |
| `session_id` / `session_index` | 会话标识与序号 | SessionManager |
| `is_pro` / `protect_enabled` | 用户是否 Pro、是否开启充电保护 | 本地状态 |
| `install_source` / `referrer` | 安装来源（Play Install Referrer） | 首次启动采集并持久化 |
| `experiment_id` / `variant` | AB 实验分组（预留） | 远程配置 |
| `sample_rate` | 本次上报生效的采样率 | 远程配置 |

### 10.2.3 页面枚举

`screen_name` 枚举（8 个）：`home_dashboard`、`battery_monitor`、`charge_protection`、`power_analysis`、`smart_saving`、`settings`、`pro_upgrade`、`help_webview`。
首屏的 `previous_screen` 取 `null`（不再使用非枚举值 `app_launch`）。

### 10.2.4 事件清单

#### A. 生命周期（4）

| 事件名 | Tier | 触发时机 | 关键属性 |
|---|---|---|---|
| `app_first_open` | T1 | 首次启动且完成引导首屏渲染 | `install_source`、`referrer`、`is_first_launch` |
| `app_launched` | T1 | 冷启动完成、首页渲染完成 | `launch_type`（cold/hot/warm）、`launch_duration_ms` |
| `app_foregrounded` | T2 | 从后台恢复 | `background_duration_ms` |
| `app_backgrounded` | T2 | 进入后台 | `session_duration_ms`、`screens_visited` |

#### B. 页面浏览（2）

| 事件名 | Tier | 触发时机 | 关键属性 |
|---|---|---|---|
| `screen_viewed` | T1 | 页面 `onResume` | `screen_name`、`previous_screen`、`session_depth` |
| `screen_exited` | T2 | 页面 `onPause` | `screen_name`、`duration_ms` |

#### C. 引导与权限（4）

| 事件名 | Tier | 触发时机 | 关键属性 |
|---|---|---|---|
| `permission_requested` | T2 | 系统权限弹窗弹出前 | `permission_name`、`context`、`is_rationale_shown` |
| `permission_denied` | T1 | 用户拒绝关键权限 | `permission_name`、`context` |
| `onboarding_step_completed` | T2 | 引导流程每步完成 | `step_index`、`step_name` |
| `notification_permission_result` | T2 | 通知权限申请结果 | `granted`、`context` |

> `permission_requested` 是"权限拒绝率"的分母，虽为 T2 但**不参与采样**。

#### D. 核心功能（13）

| 事件名 | Tier | 触发时机 | 关键属性 |
|---|---|---|---|
| `battery_health_viewed` | T1 | 查看健康度详情 | `health_score`、`capacity_mah`、`temperature_c` |
| `scene_estimate_expanded` | T2 | 展开场景续航列表 | `expanded_count`、`scenes_visible` |
| `charge_limit_triggered` | T1 | 充电限值提醒触发 | `limit_percent`、`current_percent`、`temperature_c` |
| `temp_alert_triggered` | T1 | 温度异常警报触发 | `temperature_c`、`threshold_c`、`is_charging` |
| `charge_session_started` | T2 | 检测到开始充电 | `start_percent`、`is_protection_on`、`charger_type` |
| `charge_session_ended` | T1 | 充电结束（拔除/充满） | `duration_ms`、`end_percent`、`max_temp_c`、`limit_triggered_count` |
| `calibration_started` | T1 | 开始电池校准 | `method`（manual/guided） |
| `calibration_completed` | T1 | 校准完成 | `cycles_completed`、`measured_capacity_mah`、`deviation_percent` |
| `calibration_abandoned` | T2 | 校准中途退出 | `step`、`reason` |
| `power_ranking_viewed` | T2 | 查看应用耗电排行 | `time_range`（day/week）、`apps_shown` |
| `app_hibernation_set` | T2 | 设置后台休眠 | `app_category`（social/video/game/tool/system）、`target_app_count` |
| `smart_saving_enabled` | T2 | 开启智能省电 | `mode`、`target_app_count` |
| `weekly_report_viewed` | T2 | 查看每周充电报告 | `week_number`、`report_type` |

> ⚠️ **`app_hibernation_set` 已移除 `package_name`**，改为 `app_category`。原因：初版该属性与 10.7.1「包名不随埋点上报」的合规承诺直接冲突。

#### E. 广告（9）

| 事件名 | Tier | 触发时机 | 关键属性 |
|---|---|---|---|
| `ad_requested` | T1 | 向广告 SDK 发起请求 | `placement_id`、`ad_unit_id`、`network`、`ad_format`、`trigger_scene` |
| `banner_shown` | T1 | Banner 完成展示 | `placement_id`、`ad_unit_id`、`screen_name`、`ad_format` |
| `banner_clicked` | T2 | 用户点击 Banner | `placement_id`、`ad_unit_id`、`screen_name` |
| `interstitial_shown` | T1 | 插屏完成展示 | `placement_id`、`ad_unit_id`、`trigger_scene`、`session_event_count` |
| `interstitial_closed` | T2 | 用户关闭插屏 | `placement_id`、`ad_unit_id`、`close_time_ms`、`was_skipped` |
| `ad_revenue_paid` | T1 | AdMob `OnPaidEventListener` 回调 | `placement_id`、`ad_unit_id`、`network`、`revenue_micros`、`currency`、`precision_type` |
| `ad_config_fetched` | T3 | 广告配置拉取完成 | `config_version`、`source`（remote/cache/builtin）、`duration_ms`、`success` |
| `ad_config_invalid` | T3 | 配置校验失败 | `placement_id`、`reason` |
| `ad_circuit_breaker_tripped` | T3 | 广告位触发熔断 | `placement_id`、`fail_count`、`cooldown_seconds` |

> 关键点：① 引入**稳定的 `placement_id`** 作为聚合维度，`ad_unit_id` 会因换单元而变化，仅用于对账；② `ad_requested` 是填充率的分母，缺失则无法计算填充率；③ `ad_revenue_paid` 是 eCPM 的唯一数据来源（初版缺失）；④ **`ad_requested` 按"一次展示意图"计数一次**，配置中的 `retry_count` 重试**不重复计数**，重试次数以属性 `retry_index` 记录。

#### F. 订阅转化（7）

| 事件名 | Tier | 触发时机 | 关键属性 |
|---|---|---|---|
| `feature_gate_shown` | T1 | Pro 功能拦截页曝光 | `feature_name`、`current_plan` |
| `pro_page_viewed` | T1 | 进入 Pro 升级页 | `source`（banner/feature_gate/settings）、`current_plan` |
| `purchase_initiated` | T1 | 点击购买按钮 | `plan_id`、`price_local`、`currency` |
| `purchase_completed` | T1 | 客户端确认购买成功 | `plan_id`、`price_local`、`order_id`、`is_first_purchase` |
| `purchase_cancelled` | T2 | 用户主动放弃支付 | `plan_id`、`cancel_step` |
| `purchase_failed` | T2 | 购买失败 | `plan_id`、`error_code`、`payment_method` |
| `purchase_restored` | T2 | 恢复购买 | `plan_id`、`source` |

> 客户端事件仅用于**行为漏斗**。收入与订阅状态的真相源为服务端 RTDN（见 10.4.7）。

#### G. 异常与错误（2）

| 事件名 | Tier | 触发时机 | 关键属性 |
|---|---|---|---|
| `feature_unavailable` | T2 | 功能因缺少权限不可用 | `feature_name`、`missing_permission` |
| `ad_load_failed` | T1 | 广告加载失败 | `placement_id`、`ad_unit_id`、`error_code`、`network_type` |

> **崩溃不计入自建埋点**：进程崩溃时队列与磁盘写入无法保证完成，崩溃监控由 Firebase Crashlytics 承担，SDK 不自建崩溃捕获。

#### H. 通知触达（3）

| 事件名 | Tier | 触发时机 | 关键属性 |
|---|---|---|---|
| `notification_received` | T2 | 通知送达 | `notification_type`、`is_foreground` |
| `notification_clicked` | T2 | 用户点击通知 | `notification_type`、`time_since_received_ms` |
| `reminder_setting_changed` | T2 | 提醒开关变更 | `reminder_type`、`enabled` |

---

### 10.2.5 属性枚举字典 ★新增

> 本节为**强约束**。以下属性的取值必须是表中枚举值之一；服务端对枚举属性做白名单校验，
> 非枚举值一律**写入 `analytics_events_rejected` 并计数告警**（不静默丢弃、不污染仓库）。
> 新增枚举值走附录 A 的字典变更流程，客户端与服务端**同步发版**。

#### （1）`trigger_scene` — 插屏/Banner 触发场景

| 值 | 触发位置 | 是否受频次策略约束 |
|---|---|---|
| `app_cold_start` | 冷启动完成后 | 是（受首次启动免广告时长限制） |
| `screen_switch` | 页面间切换 | 是 |
| `charge_complete` | 充电完成提醒后 | 是 |
| `calibration_complete` | 校准完成后 | 是 |
| `report_generated` | 周报/报告生成后 | 是 |
| `feature_gate_exit` | Pro 功能门关闭后 | 是 |
| `app_exit` | 用户退出 App | 是 |
| `manual_debug` | 调试入口（**仅 debug 包**） | 否，且服务端在 release 环境拒收 |

> `manual_debug` 上报被服务端拒收并计入告警，用于防止测试流量污染 eCPM。

#### （2）`permission_name` — 权限名

| 值 | 对应 Android 权限 | 是否关键权限（拒绝即影响核心功能） |
|---|---|---|
| `notification` | `POST_NOTIFICATIONS` | 否（影响提醒，不影响主功能） |
| `usage_stats` | `PACKAGE_USAGE_STATS` | **是**（功耗分析/智能省电依赖） |
| `battery_optimization` | `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | 否 |
| `system_alert_window` | `SYSTEM_ALERT_WINDOW` | 否（充电浮窗，可选） |
| `exact_alarm` | `SCHEDULE_EXACT_ALARM` | 否（定时提醒） |
| `storage` | `READ_MEDIA_*` | 否 |

> `permission_denied` 的 `context` 枚举：`onboarding`、`feature_use`、`settings`、`system_dialog`。
> 权限拒绝率口径 = `permission_denied` 次数 ÷ `permission_requested` 次数，**按 `permission_name` 分别计算**。

#### （3）`plan_id` — 订阅商品 ID

| 值 | 说明 | 类型 |
|---|---|---|
| `pro_monthly` | Pro 月订阅 | 自动续期 |
| `pro_yearly` | Pro 年订阅 | 自动续期 |
| `pro_lifetime` | Pro 买断 | 一次性 |
| `trial_monthly` | 月订阅（含 7 天试用） | 自动续期 |
| `promo_yearly` | 促销年订阅 | 自动续期 |

> **必须与 Google Play Console 中真实配置的 `productId` 完全一致**（大小写敏感），
> 否则 RTDN 回调中的 `subscriptionId` 无法与客户端埋点按 `order_id` 对齐。
> 新增 SKU 时同步更新本表与服务端字典，否则该 SKU 的收入无法归因。

#### （4）`notification_type` — 通知类型

| 值 | 说明 | 频率上限 |
|---|---|---|
| `charge_limit_reached` | 充电到达限值提醒 | 每充电会话 1 次 |
| `temp_alert` | 温度异常警报 | 冷却 30 分钟 |
| `charge_complete` | 充电完成 | 每充电会话 1 次 |
| `weekly_report` | 每周充电报告 | 每周 1 次 |
| `calibration_reminder` | 校准周期提醒 | 每 30 天 1 次 |
| `battery_health_drop` | 健康度显著下降 | 每 7 天 1 次 |
| `re_engagement` | 召回推送 | 每日最多 1 次 |
| `promo` | 促销/订阅引导 | 每周最多 2 次 |

#### （5）`error_code` — 错误码（两个来源，命名空间隔离）

**广告来源**（`ad_load_failed`，值为 AdMob `LoadAdError.getCode()` 原始 int，**原样透传不做映射**）：

| 值 | 含义 | 处置建议 |
|---|---|---|
| 0 | `ERROR_CODE_INTERNAL_ERROR` | 重试 |
| 1 | `ERROR_CODE_INVALID_REQUEST`（多为 unit_id 配置错误） | **告警**：疑似配置错误，触发熔断 |
| 2 | `ERROR_CODE_NETWORK_ERROR` | 重试 |
| 3 | `ERROR_CODE_NO_FILL`（无填充） | 正常，不重试；计入填充率分母 |
| 8 | `ERROR_CODE_APP_ID_MISSING` | **P0 告警**：配置错误 |
| 9 | `ERROR_CODE_REQUEST_ID_MISMATCH` | 重试 |

> 服务端按 `error_code` 分布做监控：`INVALID_REQUEST` / `APP_ID_MISSING` 占比突增 → 立即告警，
> 通常是后台误改 `ad_unit_id` 所致（对应 10.5 的熔断机制）。

**购买来源**（`purchase_failed`，Google Play Billing 响应码）：

| 值 | 含义 | 是否计入失败率 |
|---|---|---|
| 1 | `USER_CANCELED` | **否**（用户主动放弃，单独统计） |
| 2 | `SERVICE_UNAVAILABLE` | 是 |
| 3 | `BILLING_UNAVAILABLE`（设备/地区不支持） | 是，按国家单独看 |
| 4 | `ITEM_UNAVAILABLE` | 是 |
| 5 | `DEVELOPER_ERROR` | 是，**告警**（集成 bug） |
| 6 | `ERROR` | 是 |
| 7 | `ITEM_ALREADY_OWNED` | **否**（转为恢复购买流程） |
| 8 | `ITEM_NOT_OWNED` | 否 |
| -1 | `SERVICE_DISCONNECTED` | 是 |

> **转化漏斗的"购买失败率"必须剔除 1/7/-1**，否则把"用户改主意"和"恢复购买"算成系统故障，指标会误导决策。

#### （6）`app_category` — 应用分类（替代 `package_name`）★合规修订

`app_hibernation_set` / `power_ranking_viewed` / `smart_saving_enabled` 中的包名**一律不上报**（10.7.1 数据最小化），
客户端本地按以下规则映射为分类后再上报：

| 值 | 判定规则（客户端本地执行） |
|---|---|
| `social` | 包名命中 `com.facebook.*`、`com.instagram.*`、`com.twitter.*`、`org.telegram.*`、`com.whatsapp*`、`id.zede*` 等社交类白名单 |
| `video` | `com.google.android.youtube*`、`com.netflix*`、`com.mxtech.*`、`com.tiktok*` 等 |
| `game` | 系统 `ApplicationInfo.CATEGORY_GAME`（API 26+） |
| `shopping` | `com.shopee*`、`com.tokopedia*`、`com.lazada*`、`id.bukalapak*` 等 |
| `browser` | `com.android.chrome*`、`com.uc.browser*`、`org.mozilla.firefox*` 等 |
| `im` | 即时通讯子类（与 social 区分） |
| `system` | 包名以 `com.android.`、`com.google.android.` 开头且非上述分类 |
| `other` | **兜底**，未命中任何规则 |

> 映射规则以**服务端下发的分类词典为准**（随广告配置端点一起下发，可远程更新），
> 上表为**内置兜底词典**（打包进 App）。服务端拒收 `other` 以外的非法值。
> `other` 占比超过 30% 时告警，说明词典需要补充。

#### （7）其他属性的取值域（一并固定，避免研发自行发明）

| 属性 | 取值域 |
|---|---|
| `screen_name` | 见 10.2.3（8 个） |
| `previous_screen` | 同 `screen_name`；**首屏传 `null`**（null 表示"无值"，豁免校验） |
| `launch_type` | `cold` / `warm` / `hot` |
| `method` | `manual` / `guided` |
| `time_range` | `day` / `week` |
| `source`（pro_page_viewed） | `banner` / `feature_gate` / `settings` / `notification` / `onboarding` |
| `step_name`（onboarding） | `welcome` / `battery_permission` / `usage_permission` / `notification_permission` / `feature_intro` / `done` |
| `report_type` | `weekly` / `monthly` / `custom` |
| `current_plan` | `free` / `trial` / `pro`（**注意与 `plan_id` 区分**：前者是用户状态，后者是商品 SKU） |
| `feature_name` | `advanced_health_report` / `unlimited_calibration` / `ad_free` / `custom_charge_limit` / `hibernation_automation` / `detailed_power_analysis` |
| `ad_format` | `banner` / `interstitial` / `rewarded` / `native` |
| `network` | `admob` / `admob_mediation` / `unknown` |
| `precision_type` | `unknown` / `estimated` / `publisher_provided` / `precise`（AdMob `AdValue.getPrecisionType()`） |
| `network_type` | `wifi` / `cellular` / `offline` / `unknown` |
| `charger_type` | `ac` / `usb` / `wireless` / `unknown` |
| `cancel_step` | `plan_select` / `confirm` / `payment_sheet` |
| `payment_method` | `google_play` / `unknown` |
| `reminder_type` | `charge_limit` / `temp_alert` / `weekly_report` / `calibration` |
| `missing_permission` | 同（2）`permission_name` |
| `reason`（ad_config_invalid） | `empty_unit_id` / `placement_disabled` / `kill_switch` / `schema_mismatch` |
| `step`（calibration_abandoned） | `discharge` / `full_charge` / `verify` |
| `mode`（smart_saving_enabled） | `balanced` / `aggressive` / `custom` |
| `currency` | `IDR` / `VND` / `THB` / `PHP` / `MYR` / `SGD` / `USD`（ISO 4217 **大写**） |
| 布尔属性 | `was_skipped`、`is_pro`、`enabled`、`success`、`granted`、`is_foreground`、`is_first_launch`、`is_charging`、`is_protection_on`、`is_rationale_shown`、`is_first_purchase` —— **统一用 JSON `true`/`false`**（禁止 `1`/`0`/`"yes"`/`"true"`） |
| 数值属性 | 所有 `*_ms` / `*_count` / `*_percent` / `*_mah` / `*_score` / `revenue_micros` / `price_local` 等 —— **必须是 JSON `number`**（禁止字符串数字） |

#### （8）两类特殊约束

**① 动态取值域（不可静态枚举）**

`placement_id` 由管理后台维护（10.5），运营随时可能新增广告位，**不能写死在枚举里**——否则新增广告位后该位的所有埋点会被服务端误拒。

- 取值 = `ad_placements.placement_key`（字符串，稳定不变）
- 服务端运行时从数据库读取并缓存 300 秒
- **数据库不可用时放行**（故障时放行优于误拒），并上报异常

**② 格式约束（防注入，非枚举）**

以下属性取值不固定，但需做格式校验，防止脏数据与注入：

| 属性 | 约束 | 说明 |
|---|---|---|
| `ad_unit_id` | 匹配 `^ca-app-pub-\d{15,20}\/\d{9,12}$` | AdMob 单元 ID 格式 |
| `order_id` | 匹配 `^[A-Za-z0-9._-]{4,64}$` | Google Play 订单号。**刻意宽松**——订单号格式会随 Google 调整，误拒将破坏收入对账，故仅约束字符集与长度 |

> **布尔与数值类型约定**是防线重点：Kotlin 用 `Any` 序列化时极易产出字符串 `"true"` 或 `"1200"`，
> 会让 `properties` JSON 字段类型不一致，导致后续 `JSON_EXTRACT` 与聚合查询出错。
> 服务端对类型做校验，类型不符同样写入 rejected 表。

#### （9）违规处置策略

| 违规类型 | 默认策略（strict） | 降级策略（`ANALYTICS_ENUM_STRICT=false`） |
|---|---|---|
| 枚举值不在白名单 | 整条事件写入 rejected 表，原因 `enum_violation` | 仅剔除该属性，事件主体保留 |
| 类型不符（布尔/数值） | 同上 | 同上 |
| 格式不符 | 同上 | 同上 |
| 未知事件名 | 整条拒绝，原因 `unknown_event` | 不可降级 |
| 时钟偏移 > 24h | 整条拒绝，原因 `clock_skew` | 不可降级 |

> **默认开启 strict**。降级开关仅用于"线上字典未及时同步"的应急场景，
> 启用期间必须同步告警并限期修复——长期开着等于放弃数据质量。

#### （10）字典体检命令

字典变更后必须执行，建议接入 CI：

```bash
php artisan analytics:lint-dictionary           # 一致性检查（列出未受约束属性）
php artisan analytics:lint-dictionary --strict  # 未受约束属性视为失败（CI 用）
php artisan analytics:lint-dictionary --test    # 跑 29 项枚举校验冒烟用例
```

该命令检查：① 每个事件声明的 `props` 是否都有取值域约束（无约束则列出，防止 PM 新增属性后忘记补枚举）；
② 枚举字典结构合法性；③ 冒烟用例验证关键约束（含"传包名被拦截""订单号注入被拦"两条合规用例）。

---

## 10.3 客户端 SDK 设计

### 10.3.1 架构

```
AnalyticsSDK
├── EventTracker（事件记录入口，主线程仅做轻量入队）
├── SessionManager（会话管理）
├── ConsentManager（同意状态，SDK 初始化前生效）★新增
├── ClockSynchronizer（客户端时钟偏移校正）★新增
├── Sampler（device_id 稳定分桶采样）★新增
├── EventQueue（内存队列 + SQLite 持久化）
├── BatchUploader（批量上报器，幂等 batch_id）
├── RetryHandler（指数退避 + TTL 淘汰）
├── DeviceInfoProvider（设备与公共属性）
├── RemoteConfig（统一配置门面：埋点配置 + 广告配置）★改造
└── AdConfigManager / AdCircuitBreaker（广告配置解析与熔断）★新增
```

### 10.3.2 会话与标识

- 会话：从冷启动或前台恢复开始，**进入后台即结束**（`app_backgrounded` 携带 `session_duration_ms`）；另设兜底规则——前台持续 **30 分钟无交互**也判定为新会话，防止长期驻留前台导致会话过长。
- **服务端为会话边界的唯一权威**：`analytics_sessions` 完全由 Worker 按事件流推导（首末事件时间 + 30min 间隙切分）。客户端 `session_id` 仅用于事件关联；与服务端推导冲突时**以服务端为准**，`session_duration_ms` 仅作校验参考。
- 初版的 `POST /api/v1/analytics/session` 端点**已废弃**：不再单独上报会话开始/结束（减少一次请求，并消除两套边界口径不一致的风险）。
- 设备标识：首次启动生成匿名 UUID 存于 `SharedPreferences`，**不采集 IMEI / MAC / Android ID**。
- **device_id 生成时机**：必须在 ConsentManager 确认同意**之后**；未同意则不生成、不建库、不采集。

### 10.3.3 同意与隐私（ConsentManager）★新增

| 规则 | 说明 |
|---|---|
| 生效时机 | 必须在 SDK 初始化前读取；未获得同意时 SDK 处于 `DISABLED` 状态 |
| 关闭埋点 | 停止采集 + **清除本地队列与 device_id** + 不再自动重注册 |
| 与广告解耦 | 关闭埋点**不影响**广告展示与广告配置拉取（10.5） |
| 同意凭证 | 本地记录同意时间、隐私政策版本号，供合规举证 |

### 10.3.4 队列、上报与重试

| 机制 | 参数 |
|---|---|
| 批量大小 | 20 条/批（远程可配） |
| 定时上报 | 每 30 秒检查一次 |
| 立即上报 | App 进入后台时强制 flush |
| 重试策略 | 指数退避 2s → 4s → 8s → 16s → 32s，最多 5 次 |
| **幂等** | 每批生成 `batch_id`，重试时**复用同一 batch_id**；服务端按 `batch_id` 去重 |
| **离线缓存** | SQLite；事件 TTL **7 天**，超期丢弃并计数 |
| **队列上限** | 总计 500 条：非关键事件 400 + 关键事件 100（`purchase_completed`、`calibration_completed`、`ad_revenue_paid`）；满时按 FIFO 丢弃各自类别中最旧的非关键项 |
| **单条上限** | 2KB，超限截断 `properties` 并标记 `truncated: true` |

**时钟校正**（★新增）：客户端时钟可能偏差或被篡改。SDK 在每次上报响应中读取 `server_timestamp`，计算 `offset = server_ts - client_ts` 并持久化；上报事件时使用校正后的时间。服务端拒收偏移 > 24h 的事件（写入 `analytics_events_rejected` 并告警）。

**采样**（★修订）：`sampling_rate` 必须按 **`hash(device_id) % 100 < rate * 100`** 做**稳定分桶**——禁止按事件随机采样，否则同一用户不同事件被采/不采，漏斗与留存会断裂。事件属性记录 `sample_rate`，分析时按权重还原。

### 10.3.5 性能约束

| 指标 | 目标值 |
|---|---|
| `track()` 主线程耗时 | P99 ≤ 5ms，P100 ≤ 10ms |
| SDK CPU 占用 | ≤ 1% |
| 内存队列 | ≤ 200KB |
| 本地存储 | ≤ 5MB |
| 单次请求体 | ≤ 100KB |
| StrictMode | 无主线程磁盘/网络违规 |

### 10.3.6 远程配置（埋点侧）

`GET /api/v1/config/analytics`，带 `If-None-Match` 增量更新，本地缓存 + TTL 1 小时。

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `analytics_enabled` | true | 总开关（本地同意状态优先于远程开关） |
| `batch_size` | 20 | 批量阈值 |
| `upload_interval_seconds` | 30 | 定时上报间隔 |
| `max_retry_count` | 5 | 最大重试次数 |
| `max_offline_events` | 500 | 离线队列上限 |
| `event_ttl_days` | 7 | 离线事件有效期 |
| `sampling_rate` | 1.0 | 采样率（稳定分桶） |
| `disabled_events` | [] | 远程禁用事件 |
| `config_ttl_seconds` | 3600 | 配置缓存有效期 |

---

## 10.4 后端设计（Laravel）

### 10.4.1 技术栈

| 层级 | 选型 |
|---|---|
| 框架 | Laravel 11+ |
| 认证 | Sanctum（客户端 Token）+ RBAC（后台管理员） |
| 数据库 | MySQL 8（按月分区）+ Redis（队列/缓存/配置缓存） |
| 队列 | Laravel Queue（Redis 驱动），`analytics` 独立队列 |
| 部署 | Nginx + PHP-FPM，支持水平扩展 |

### 10.4.2 设备注册与防护 ★修订

`POST /api/v1/analytics/register`（无 Token）返回匿名 Token，有效期 90 天，临近过期静默续期。

**必须的防护措施**（初版缺失）：

| 层 | 措施 |
|---|---|
| 设备真实性 | Play Integrity API attestation |
| 网络层 | IP 维度限流（20 次/小时），异常段 403 |
| 请求校验 | `app_version` 合法性、User-Agent 与包名匹配 |
| 数据层 | 异常设备识别（单设备单日事件量 > P99.9、注册后无任何核心事件）→ 标记 `is_suspect`，核心指标计算时剔除 |

### 10.4.3 数据库设计

**`analytics_devices`**：`id`、`device_uuid`(36)、`app_version`、`os_version`、`locale`、`country`、`device_model`、`install_source`、`is_suspect`、`first_seen_at`、`last_seen_at`。

**`analytics_events`**（按月 RANGE 分区，保留 13 个月）：

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | bigint | 主键 |
| `batch_id` | uuid | **UNIQUE**（幂等去重核心） |
| `event_index` | smallint | 批内序号，与 batch_id 组成业务唯一键 |
| `session_id` / `device_id` | uuid / bigint | 会话与设备 |
| `event_name` | varchar(64) | 事件名 |
| `properties` | json | 事件属性 |
| `screen_name` / `placement_id` / `plan_id` / `ad_unit_id` | varchar | **高频维度抽列** + 生成列索引 |
| `client_timestamp` / `server_timestamp` | bigint / timestamp | 客户端（已校正）/ 服务端时间 |
| `app_version` | varchar(20) | App 版本 |

索引：`(event_name, server_timestamp)`；`(device_id, session_id)`；`(placement_id, server_timestamp)`；`(batch_id)`。
分区：按 `server_timestamp` 月分区，查询须带分区裁剪条件；超期分区按月 DROP（先归档聚合表）。

> ⚠️ **MySQL 分区表限制**：每个唯一键（含主键）都必须包含分区键，且分区表不支持外键与全文索引。因此：
> ① 主键为 `(id, server_timestamp)`；
> ② **幂等去重索引不能建在本表**，改由下方 `analytics_batches` 承担；
> ③ 本表不建外键，设备关联靠应用层保证。

**`analytics_batches`**（幂等去重，**非分区表**）：`batch_id`(36, PRIMARY KEY)、`device_id`、`event_count`、`app_version`、`received_at`。
上报时先 `INSERT IGNORE`；`affected_rows = 0` 即判定为重复批次，直接返回 `duplicate: true` 且不重复入库。保留 30 天后清理。

**`analytics_sessions`**（**由"可选"改为必需**）：`session_id`(PK)、`device_id`、`started_at`、`ended_at`(可空)、`screen_count`、`event_count`、`app_version`。会话结束由 Worker 兜底（最后事件 + 30min）。

**`analytics_events_rejected`**：时钟偏移超 24h、Schema 校验失败的事件，用于监控告警。

### 10.4.4 API 端点

| 端点 | 方法 | 说明 | 认证 |
|---|---|---|---|
| `/api/v1/analytics/register` | POST | 设备注册 | 无（Play Integrity + IP 限流） |
| `/api/v1/analytics/events` | POST | 批量上报 | Bearer |
| `/api/v1/config/analytics` | GET | 埋点配置 | Bearer |
| `/api/v1/config/ads` | GET | **广告配置**（见 10.5） | Bearer |
| `/api/v1/analytics/me` | DELETE | **删除我的数据**（见 10.7.3） | Bearer |

**上报响应**（新增字段）：

```json
{ "success": true, "received": 20, "duplicate": false,
  "rejected": 0, "server_timestamp": 1726900001000 }
```

### 10.4.5 异步处理管道 ★修订

初版"API 直写 MySQL + 按事件分发 Job"存在两个问题：API 直写与 P95 ≤ 200ms 冲突；按事件分发使 Job 量放大 20 倍。修订为：

```
API 鉴权/校验/限流 → Redis 缓冲（攒批 1000 条 或 5s）→ Worker 批量 INSERT
                                                      ↓
                                        按 batch 分发单个 ProcessAnalyticsBatch Job
                                                      ↓
                              ├── 更新 analytics_sessions 聚合
                              ├── 更新设备活跃时间
                              └── 触发告警规则（购买失败率、广告失败率异常）
```

- API 侧**零数据库写入**，保障 P95 ≤ 200ms；
- Job **按批次**分发，而非按事件；
- `analytics` 队列独立于默认队列，Supervisor 按队列长度扩缩容。

### 10.4.6 限流与校验

- 限流改为**事件数为主 + 请求数为辅**：600 事件/分钟 + 10 请求/分钟（远程调小 `batch_size` 时不会误触发请求限流）。
- 429 响应必须携带 `Retry-After`，客户端据此退避（而非固定 2s）。
- **Schema 白名单校验**：服务端维护事件字典（JSON Schema），拒绝未知事件名与超白名单属性，返回被拒原因；未知事件计数进入告警。

### 10.4.7 订阅数据真相源（RTDN）★新增

客户端 `purchase_completed` 无法覆盖退款、续订、宽限期恢复、离线购买，且存在伪造风险。

- 接入 **Google Play Real-time Developer Notifications (RTDN)**：服务端接收 `SUBSCRIPTION_PURCHASED` / `RENEWED` / `CANCELED` / `EXPIRED` / `REFUNDED` / `ON_HOLD`，作为**收入与订阅状态的唯一事实来源**；
- 客户端埋点仅用于**行为漏斗**，通过 `order_id` 与服务端订单对齐；
- 每日核对两者差异率，差异 > 1% 触发告警。

### 10.4.8 监控告警

队列积压长度、批量写入失败率、429 比例、未知事件数、时钟偏移异常数、单设备事件量异常、`is_suspect` 设备占比 —— 埋点系统自身的生命线指标。

---

## 10.5 广告配置远程下发（自建方案 B）★新增章节

### 10.5.1 目标与原则

| 目标 | 说明 |
|---|---|
| **不发版改配置** | 更换 `ad_unit_id`、调整频次、上下线广告位均通过管理后台完成 |
| **紧急止血** | AdMob 单元被政策停用或判定无效流量时，5 分钟内全量停投 |
| **数据驱动调优** | 频次、场景、超时等参数可持续 A/B 调优，无需发版 |
| **与埋点解耦** | 广告配置独立端点与独立缓存，**用户关闭埋点后广告仍正常拉取与展示** |
| **永远有兜底** | 任何故障下都不能出现"空 ad_unit_id 狂刷" |

### 10.5.2 配置优先级链

```
L0  App 内置默认值（打包进 assets/remote_config_defaults.json）
L1  全局配置（ads_enabled / kill_switch / child_directed / content_rating / npa / 免广告时长）
L2  国家维度（ID / VN / TH / PH / OTHER）
L3  广告位维度（placement）
L4  用户分群（is_pro / 新用户 / 实验分组）
L5  运营临时覆盖（紧急熔断、临时降级，最高优先级）
```

解析顺序：L0 → L1 → L2 → L3 → L4 → L5 逐级覆盖。

### 10.5.3 配置项清单

| 配置项 | 层级 | 说明 |
|---|---|---|
| `placement_id` | L3 | 稳定广告位标识（埋点按此聚合） |
| `ad_unit_id` | L3 | 广告单元 ID（Android / iOS 分开） |
| `enabled` | L1/L2/L3 | 广告位开关 |
| `network` | L3 | admob / 聚合平台（预留 waterfall） |
| `frequency_cap` | L3 | `per_session` / `per_day` / `min_interval_seconds` |
| `trigger_scenes` | L3 | 插屏触发场景白名单 |
| `refresh_interval_seconds` | L3 | Banner 刷新间隔 |
| `load_timeout_ms` / `retry_count` | L3 | 弱网市场必调 |
| `circuit_breaker` | L3 | `fail_threshold` / `cooldown_seconds` — 保护 AdMob 账号 |
| `first_launch_grace_minutes` | L1 | 新用户免广告时长 |
| `max_ad_content_rating` | L1 | G / PG / T / MA — **设错会停号** |
| `tag_for_child_directed` | L1 | 儿童向标识 — **设错会停号** |
| `npa` | L1 | 未获同意时降级为非个性化广告 |
| `kill_switch` | L1 | 全局一键停投 |
| `config_ttl_seconds` | L1 | 客户端缓存有效期（默认 300s） |

### 10.5.4 数据库设计

| 表 | 主要字段 |
|---|---|
| `ad_placements` | `id`、`placement_key`(unique)、`name`、`ad_format`(banner/interstitial/native/rewarded)、`description`、`status` |
| `ad_units` | `id`、`placement_id`、`network`、`platform`(android/ios)、`country_code`(null=全部)、`ad_unit_id`、`enabled`、`ecpm_floor`、`priority`；unique(placement_id, network, platform, country_code) |
| `ad_policies` | `id`、`placement_id`、`country_code`、`app_version_min/max`、`user_segment`(all/free/pro/new)、`config_json`（频次/场景/超时/重试/熔断）、`priority`、`enabled` |
| `ad_global_settings` | `ads_enabled`、`kill_switch`、`first_launch_grace_minutes`、`tag_for_child_directed`、`max_ad_content_rating`、`npa_default`、`config_ttl_seconds`、`updated_by` |
| `ad_config_releases` | `id`、`version`、`snapshot_json`、`status`(draft/published/rolled_back)、`rollout_percent`、`target_json`、`published_by`、`note` |
| `ad_config_audit_logs` | `operator_id`、`action`、`target_type`、`target_id`、`before_json`、`after_json`、`ip`、`created_at` |

### 10.5.5 API 端点

**客户端**：

```
GET /api/v1/config/ads?country=ID&app_version=1.2.0&is_pro=0
Authorization: Bearer {token}
If-None-Match: "v17"
```

响应：配置 JSON + `config_version` + `ttl_seconds`；无变更返回 304。

**管理后台**（Sanctum + RBAC）：

| 端点 | 说明 |
|---|---|
| `GET/POST/PUT/DELETE /api/admin/ad/placements` | 广告位 CRUD |
| `GET/POST/PUT/DELETE /api/admin/ad/units` | 广告单元 CRUD |
| `GET/POST/PUT/DELETE /api/admin/ad/policies` | 策略 CRUD |
| `GET/PUT /api/admin/ad/global-settings` | 全局开关 |
| `POST /api/admin/ad/releases` | 发布（含 `rollout_percent` 与 `target_json`） |
| `POST /api/admin/ad/releases/{id}/rollback` | 回滚 |
| `POST /api/admin/ad/kill-switch` | 紧急停投（需双人复核） |
| `GET /api/admin/ad/audit-logs` | 审计日志 |
| `GET /api/admin/ad/monitor` | 各 placement 的 eCPM / 填充率 / 失败率（来自埋点） |

**缓存与加速生效**：
- Redis 缓存 key：`ad:cfg:{country}:{version_bucket}:{segment}:{version}`；
- 发布/回滚时主动清除相关 key；
- 灰度：`hash(device_uuid) % 100 < rollout_percent` 命中新版本；
- 紧急熔断：FCM 静默推送 `ad_config_refresh`，客户端收到后立即拉取（不依赖 FCM 兜底，TTL 300s 已保证 5 分钟内生效）。

### 10.5.6 客户端 RemoteConfig 门面

```
RemoteConfig.init(builtinDefaults)   // L0
    → loadCachedSnapshot()           // 启动即用缓存，保证首屏有广告
    → fetchIfStale()                 // TTL 到期/前后台切换/FCM 唤醒时拉取
    → getAdConfig(placementKey)      // 逐级解析 L1~L5，返回 EffectiveConfig
```

**三级降级链**（必须实现）：

```
远端配置 → 本地缓存快照 → 内置 L0 默认 → 该广告位关闭（绝不空 ID 请求）
```

**容错要求**：
1. `ad_unit_id` 格式校验（`ca-app-pub-...`），非法视为该位关闭，上报 `ad_config_invalid`；
2. 熔断：某单元连续失败达 `fail_threshold` → 冷却期内不再请求，冷却结束试探恢复，上报 `ad_circuit_breaker_tripped`；
3. 配置解析失败保留上一版可用配置，不覆盖为破坏性空值。

### 10.5.7 管理后台功能

| 功能 | 说明 |
|---|---|
| 广告位 / 广告单元 / 策略管理 | 按国家、平台、版本、分群配置 |
| 灰度发布 | 5% → 20% → 100%，异常秒级回滚 |
| 紧急熔断 | 全局 kill_switch 与单广告位开关 |
| **审计日志** | 操作人、时间、动作、前后值、IP，可追溯 |
| **双人复核** | `ad_unit_id` 变更、`kill_switch`、`child_directed`、`content_rating` 需二次确认 |
| 效果闭环 | 直接查看该 `placement_id` 的展示量、填充率、失败率、eCPM 随 `config_version` 的变化 |

角色：运营（编辑草稿） / 主管（发布、回滚、熔断）。

### 10.5.8 验收标准

| # | 验收项 |
|---|---|
| 1 | 不发版修改任一 `ad_unit_id`，**15 分钟内** ≥90% 活跃客户端生效 |
| 2 | 触发 `kill_switch` 后 **5 分钟内** ≥95% 客户端停止广告请求（抓包验证） |
| 3 | 服务端不可达 + 本地缓存为空时，使用内置默认配置正常展示，无空 ID 请求 |
| 4 | 某单元连续失败达阈值后熔断，冷却期内请求量为 0 |
| 5 | Pro 用户全局不出广告，且不影响埋点上报 |
| 6 | 用户关闭埋点开关后，广告配置仍能拉取、广告正常展示 |
| 7 | 所有配置变更有审计日志，可追溯操作人与前后值 |

---

## 10.6 数据分析看板

| 看板 | 核心指标 | 计算口径（事件来源） |
|---|---|---|
| **DAU/MAU 与留存** | 日活、月活、次日/7日/30日留存 | 以 `device_id` 去重，**当日任一事件即计为活跃**（不仅 `app_launched`，避免仅后台恢复的用户被漏计）；留存按 `first_seen_at` 分群 |
| **功能使用** | 各功能使用率、使用深度、停留时长 | 功能事件去重设备数 / DAU；`screen_viewed` 分布；`screen_exited.duration_ms` |
| **充电行为** | 限值触发次数、温度警报频率、**平均充电时长** | `charge_limit_triggered`、`temp_alert_triggered`、`charge_session_ended.duration_ms` |
| **广告表现** | 展示量、点击率、**填充率**、**eCPM**、ARPU | 填充率 = `banner_shown`/`ad_requested`；**eCPM = (Σ `revenue_micros` ÷ 1,000,000) ÷ 展示量 × 1000**（`revenue_micros` 为百万分之一货币单位，必须换算）；按 `placement_id` 聚合。`currency` 为 AdMob 账户结算币种，跨币种对比需按日汇率归一化 |
| **转化漏斗** | 拦截曝光 → Pro 浏览 → 发起 → 完成 | `feature_gate_shown` → `pro_page_viewed` → `purchase_initiated` → `purchase_completed`；收入以 RTDN 为准 |
| **异常监控** | **权限拒绝率**、功能不可用率、广告失败率、配置异常率 | 拒绝率 = `permission_denied` / `permission_requested`（分母已补齐） |
| **广告配置监控** ★新增 | 配置版本分布、拉取成功率、熔断次数、eCPM 随版本变化 | `ad_config_fetched`、`ad_circuit_breaker_tripped` |

工具：Metabase（初期）+ 自建运营后台（广告配置与监控）。
时效：实时指标 T+5min，离线指标 T+1 08:00 前就绪。

---

## 10.7 合规要求

### 10.7.1 数据最小化

**不采集**：个人身份信息（手机号、邮箱、姓名）、IMEI / MAC / Android ID 等持久硬件标识、精确地理位置、通讯录/短信/照片、**其他应用包名**（`app_hibernation_set` 只上报 `app_category`）。

### 10.7.2 用户同意

首次启动展示隐私政策，明确告知采集目的与范围。用户可在 **设置 → 数据与隐私 → 使用分析** 中关闭（关闭后 SDK 停止采集上报并清除本地数据，核心功能不受影响）。广告展示不受该开关影响，但个性化广告需独立同意。

### 10.7.3 数据保留与删除权 ★新增

| 项 | 要求 |
|---|---|
| **保留期限** | 原始事件明细保留 **13 个月**，超期转聚合表或删除；会话聚合表可长期保留。隐私政策同步声明 |
| **删除权** | 设置页提供"删除我的数据" → `DELETE /api/v1/analytics/me` → 按 `device_uuid` 硬删除或匿名化（保留聚合量），30 天内完成 |
| **同意凭证** | 记录同意时间、隐私政策版本号、同意/撤回操作日志，用于合规举证 |

### 10.7.4 东南亚合规适配

| 国家 | 要求 | 应对 |
|---|---|---|
| 印尼 | PDP Law | 数据存储于印尼境内或获得用户明确同意 |
| 越南 | Cybersecurity Law | **按法务评估结论执行**（初版直接承诺"越南境内保留副本"成本极高，需先确认适用性） |
| 泰国 | PDPA | 明确告知用途并获取同意 |
| 菲律宾 | Data Privacy Act | 指定数据保护官，建立数据主体请求响应流程 |

存储策略：埋点数据存储于东南亚区域（新加坡/印尼）云服务器。匿名 UUID 不含可识别信息，降低合规风险（注：严格法域下设备标识仍可能被视为个人数据，表述需谨慎）。

### 10.7.5 Play Data Safety 与广告合规 ★新增

- `ad_unit_id` 变更若伴随数据收集范围变化（启用个性化广告、使用 AAID），需同步更新 **Google Play Data Safety 表单**；
- `tag_for_child_directed` / `max_ad_content_rating` 设为服务端可配 + 双人复核（设错会导致 AdMob 停号）；
- 用户撤回个性化同意时，远程下发 `npa: true` 全局降级，无需发版。

---

## 10.8 里程碑与工期

| 阶段 | 内容 |
|---|---|
| **M1（前置）** | ★**合规评审前置**（数据本地化影响基础设施选型）；事件字典与 OpenAPI 契约定稿；广告配置数据模型设计 |
| **M2（第 7-10 周）** | 埋点 SDK（事件、队列、持久化、幂等、时钟校正、同意管理）+ 广告配置门面与熔断 + **Mock Server 联调** |
| **M3（第 11-14 周）** | Laravel 埋点 API（注册防护、上报、配置、删除）+ Redis 攒批管道 + RTDN 接入 + 广告配置后台 API |
| **M4（新增）** | 广告配置管理后台 UI（CRUD、灰度、回滚、审计日志）+ 监控看板 |
| **M5（第 19-22 周）** | 数据分析看板（Metabase）、合规上线前复核、验收 |

**工期估算**：

| 模块 | 人周 |
|---|---|
| 埋点 SDK（含配置门面、熔断） | 3.5 |
| Laravel 埋点 API + 管道 + RTDN | 3 |
| 广告配置后台 API + UI + 审计 | 2（与埋点共用基础设施，边际成本已压缩） |
| 数据看板 | 2 |
| 合规 | 1 |
| **串行合计** | **11.5 人周** |

**建议：总工期在原有基础上增加 5 周**（假设客户端 1 人 + 后端 1 人并行）。初版"+2 周"低估。

---

## 10.9 验收标准（可量化）

| # | 验收项 | 判定方式 |
|---|---|---|
| 1 | 事件字典中 44 个事件 100% 覆盖 | 事件字典比对 |
| 2 | 属性非空填充率 ≥ 95%，未知事件占比 < 0.1% | 服务端统计 |
| 3 | 批量上报：弱网（RTT 500ms、丢包 5%）下 24h 送达率 ≥ 99%，**重复入库率 = 0** | 压测 + 幂等验证 |
| 4 | 离线：断网 30 分钟并杀进程 → 重启联网 60 秒内完成补报，零丢失、零重复 | 手工场景验证 |
| 5 | 连续采集 1000 事件会话中 `track()` P99 ≤ 5ms，StrictMode 无违规 | 性能profile |
| 6 | 关闭埋点后抓包确认零网络请求，本地 SQLite 队列已清空 | 抓包 + 本地检查 |
| 7 | 上报接口：1000 并发设备 × 20 条/批，P95 ≤ 200ms、P99 ≤ 500ms | 压测 |
| 8 | 数据准确性：随机抽取 100 条客户端日志与服务端逐条比对，一致率 100% | 抽样核对 |
| 9 | 端到端时延：事件发生到可查询 ≤ 5 分钟（实时指标） | 计时验证 |
| 10 | 防污染：脚本模拟 1000 个伪造设备注册，拦截率 ≥ 99% | 安全测试 |
| 11 | 看板：6 大看板指标可在 T+1 08:00 前产出，与抽样日志差异 < 1% | 对账 |
| 12 | 广告配置：满足 10.5.8 全部 7 条 | 见 10.5.8 |

---

## 附录 A：事件字典维护流程

1. 事件字典（事件名、Tier、属性、Schema、用途）以 JSON Schema 形式纳入代码仓库，服务端与客户端共用同一份；
2. 新增/修改事件需提交评审，同步更新字典、服务端白名单、看板 SQL；
3. 废弃事件走**标记废弃 → 停止上报 → 下线**三步，禁止直接删除（避免历史数据断裂）；
4. Schema 版本号随 App 版本递增，服务端兼容 N-2 版本。

## 附录 B：初版 → 修订版变更对照

| 章节 | 初版问题 | 修订 |
|---|---|---|
| **10.2.5** | **6 个枚举缺失，研发只能自行发明取值** | **新增完整枚举字典：`trigger_scene`/`permission_name`/`plan_id`/`notification_type`/`error_code`（广告·购买双来源隔离）/`app_category`；另固定 20+ 属性的取值域、布尔与数值类型约定、动态取值域与格式约束、违规处置策略、字典体检命令** |
| 10.1 | "5~15 个核心事件"与 37 个事件矛盾 | 引入 Tier 分级，说明 12 个北极星事件 |
| 10.2.1 | 声称 37 个，明细仅定义 24 个；事件数与页面数混用 | 重做为逐事件清单，44 个（T1 19 / T2 22 / T3 3） |
| 10.2.2 | `app_hibernation_set` 上报 `package_name` 违反 10.6.1 | 改为 `app_category` |
| 10.2.2 | 缺公共属性规范 | 新增 10.2.2 |
| 10.2.x | 缺充电时长、权限分母、revenue、填充率分母、通知链路、校准流失 | 新增 11 个事件 |
| 10.3 | 缺同意管理、时钟校正、稳定分桶、TTL、队列上限细化 | 新增 10.3.3，修订 10.3.4 |
| 10.4.2 | 注册接口无认证无限流 | Play Integrity + IP 限流 + 异常设备剔除 |
| 10.4.3 | `batch_id` 未用于幂等；分区策略模糊；JSON 全属性存储 | UNIQUE 索引 + 按月分区 + 高频维度抽列 |
| 10.4.5 | API 直写 MySQL；按事件分发 Job | Redis 缓冲攒批 + 按 batch 分发 |
| 10.4.x | 订阅仅靠客户端上报 | 新增 10.4.7 RTDN |
| — | 广告配置无定义 | **新增 10.5 整章** |
| 10.5 | 3 项指标无数据来源 | 10.6 补齐计算口径 |
| 10.6 | 缺保留期与删除权 | 新增 10.7.3 |
| 10.6.3 | 越南本地化承诺过重 | 改为"按法务评估结论执行" |
| 10.7 | 合规审查放 M5；+2 周低估 | 合规前置 M1；工期 +5 周（2 人并行） |
| 10.8 | 验收标准多为定性 | 12 条可量化标准 |
| — | 缺 GMS/分发渠道前提、缺外部依赖声明 | **新增 10.0 前提与依赖**（已确认仅 Google Play 分发） |
| 10.4.3 | `UNIQUE(batch_id, event_index)` 建在分区表上，MySQL 不支持 | 去重移至非分区表 `analytics_batches`；主键改 `(id, server_timestamp)` |
| 10.6 | eCPM 公式漏了 ÷10⁶，结果放大 100 万倍 | 改为 `(Σ revenue_micros ÷ 1,000,000) ÷ 展示量 × 1000`，并补充跨币种归一化 |
| 10.3.2 | 会话边界客户端/服务端双权威，`/analytics/session` 端点删除未说明 | 明确服务端为唯一权威，端点废弃原因写明 |
| 10.2.4 E | `ad_requested` 重试计数口径未定，影响填充率 | 明确按展示意图计一次，重试记为 `retry_index` |
