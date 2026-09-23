# F-010 埋点与数据上报系统 —— PRD 评审意见

> 评审对象：PRD 第 10 章（F-010 埋点与数据上报系统）
> 评审视角：数据可用性 / 工程可实现性 / 后端容量 / 合规 / 验收可测性
> 结论：**骨架完整、工程细节扎实**，但存在 **7 项阻断性问题（P0）**、**18 项建议补充（P1）**、**10 项优化（P2）**。其中 P0 若不修改，会导致"埋点上完线但算不出指标"或"上线即违规"。

---

## 一、评审结论速览

| 严重度 | 数量 | 代表问题 |
|---|---|---|
| 🔴 P0 阻断 | 7 | 指标与事件不匹配、包名上报违反自身合规承诺、缺幂等去重、注册接口无防护、订阅数据仅靠客户端、事件数量口径自相矛盾、缺数据保留期与删除权 |
| 🟠 P1 建议补充 | 18 | 公共属性规范、崩溃监控方案、采样分桶、时钟校正、事件 TTL、后端写入链路、分区策略、安装归因等 |
| 🟡 P2 优化 | 10 | 事件字典治理、调试验收工具、AB 实验预留、配置灰度、北极星指标等 |

**最大风险一句话**：这一章的事件体系是"自下而上按功能罗列"的，而 10.5 的看板指标是"自上而下按业务目标列"的，**两者没有对齐**——至少有 3 个看板指标在当前事件体系下算不出来。

---

## 二、P0 阻断问题（必须修改）

### P0-1｜看板指标与事件体系不匹配（10.2 ↔ 10.5）

当前 10.5 列出的指标，有 3 项在 10.2 的事件定义中**缺少数据来源**：

| 看板指标 | 缺失原因 | 修改建议 |
|---|---|---|
| **权限拒绝率** | 只有 `permission_denied`（分子），没有 `permission_requested`（分母），拒绝率无法计算 | 新增 `permission_requested`（`permission_name`、`context`、`is_rationale_shown`） |
| **平均充电时长** | 事件体系中**完全没有**充电开始/结束事件，只有 `charge_limit_triggered` | 新增 `charge_session_started` / `charge_session_ended`（`duration_ms`、`start_percent`、`end_percent`、`max_temp_c`），或由服务端会话聚合推导 |
| **广告 eCPM** | 广告事件属性里**没有 revenue 字段**，eCPM 无从计算 | ① 接入 AdMob `OnPaidEventListener` 采集 impression-level revenue，在 `*_impression` 事件增加 `revenue_micros`、`currency`、`precision_type`；② 或对接 AdMob Reporting API 做 T+1 对账（两者建议都做，客户端用于实时、API 用于校准） |

> 另需注意：`banner_impression` 的"展示量"无法与 AdMob 后台对账，建议同时在事件属性中保留 `ad_unit_id` + `ad_network`（若未来接入多家聚合平台，`ad_source`/ mediation 字段必须预留，否则无法做 waterfall 分析）。

### P0-2｜`app_hibernation_set` 上报 `package_name` 直接违反自身合规承诺（10.2.2 ↔ 10.6.1）

- 10.2.2 定义该事件携带 `package_name`；
- 10.6.1 明确承诺"其他应用的安装列表（功耗分析功能中的包名仅本地使用，**不随埋点上报**）"。

这是**同一文档内的自相矛盾**，且属于东南亚 PDP Law / PDPA 下的高风险项（应用列表可反推用户画像）。

**修改建议**（三选一，推荐 ①）：
1. **删除 `package_name`**，改为上报 `app_category`（social / video / game / tool / system）与 `target_app_count`；
2. 上报包名的**不可逆哈希**（但仍可能被彩虹表反推，需加盐且盐值不落库）；
3. 若业务强依赖包名分布，必须在隐私政策中单独披露，并在"数据与隐私"页提供**独立的二次同意开关**（默认关闭）。

### P0-3｜上报接口缺幂等设计，`batch_id` 未用于去重（10.4.3 / 10.3.2）

客户端指数退避重试（最多 5 次）在网络抖动时**极可能重复提交同一批事件**（请求超时但服务端已入库），而文档中 `batch_id` 只是记录字段，未说明去重。

后果：所有计数类指标（DAU、触发次数、转化率）系统性虚高，且无法事后清洗。

**修改建议**：
- `analytics_events.batch_id` 建 **UNIQUE 索引**（或 `UNIQUE(batch_id, event_index)`），入库使用 `INSERT IGNORE` / upsert 幂等写入；
- 响应体返回 `duplicate: false`，客户端据此判定重试是否成功；
- 验收标准增加："同一 batch 重复提交 3 次，`analytics_events` 仅入库 1 份"。

### P0-4｜`/analytics/register` 无认证、无限流，可被批量伪造（10.4.2 / 10.4.4）

匿名 Token 注册接口**完全开放**，攻击者可用脚本伪造无限 `device_uuid` 注册，导致：设备表爆炸、DAU 等核心指标被彻底污染、存储与队列成本失控。

**修改建议**（分层防护）：
1. **设备真实性校验**：接入 Play Integrity API（Android），对注册请求做设备 attestation（这是 Google Play 生态的标准做法）；
2. **网络层限流**：按 IP 维度限流（如 20 次/小时），异常 IP 段返回 403；
3. **数据层校验**：注册时校验 `app_version` 合法性、User-Agent 与包名匹配；
4. **离线清洗**：服务端增加"异常设备识别"规则（如单设备单日事件量 > P99.9、注册后无任何核心功能事件），在 ETL 层标记 `is_suspect` 并排除出核心指标；
5. 文档需补充"数据防污染"小节，说明指标计算时的剔除规则。

### P0-5｜订阅数据仅靠客户端上报，收入与转化必然失真（10.2.2 / 10.4.x）

`purchase_completed` 由客户端上报，会漏掉/错记以下场景：
- 订单在 Google Play 侧完成但 App 崩溃/退后台未上报；
- **退款、撤单、自动续订、宽限期恢复**——客户端完全无感知；
- 跨设备/重新安装后的订阅恢复；
- 支付链路被劫持的伪造上报（安全性）。

**修改建议**（这是订阅型 App 的标准架构）：
- 接入 **Google Play Real-time Developer Notifications (RTDN)**，服务端接收订阅生命周期通知（`SUBSCRIPTION_PURCHASED` / `RENEWED` / `CANCELED` / `EXPIRED` / `REFUNDED` / `ON_HOLD`），作为**收入与订阅状态的唯一事实来源**；
- 客户端埋点仅用于**转化漏斗行为分析**（浏览→发起→完成），且 `purchase_completed` 需携带 `order_id` 以便与服务端订单关联校验；
- 数据管道中明确"财务口径收入以 RTDN 为准，行为口径以客户端为准，两者按 `order_id` 对齐并每日核对差异率"。
- 补充事件：`purchase_cancelled`（用户主动放弃）、`purchase_restored`（恢复购买）、`pro_entitlement_changed`（服务端驱动）。

### P0-6｜事件数量口径自相矛盾（10.1 / 10.2.1 / 10.8）

| 出处 | 声称 | 实际核对 |
|---|---|---|
| 10.1 原则 1 | 引用行业共识"5~15 个精心设计的事件" | 与 37 个事件冲突 |
| 10.2.1 分类表 | 生命周期 4 / 页面 6 / 核心功能 12 / 广告 6 / 订阅 5 / 异常 4 = **37** | **明细仅定义 24 个**（生命周期 3、页面 1、核心功能 9、广告 4、订阅 4、异常 3），**13 个缺失** |
| 10.8 验收 | "核心事件（37 个）全部按规范命名" | 无法验收，因为 13 个事件没有定义 |

具体缺失项（需补齐或修正计数）：
- 生命周期缺第 4 个（建议 `app_first_open` 或 `app_crashed`）；
- 页面浏览声称 6 但只定义 `screen_viewed` 一个事件（**混淆了"事件数"与"页面数"**，而 `screen_name` 枚举有 8 个值，也不是 6）；
- 核心功能缺 3 个（建议补 `smart_saving_enabled`、`optimization_applied`、`battery_saver_mode_changed` 等）；
- 广告缺 2 个（建议补 `ad_load_failed` 已在异常类、另补 `rewarded_*` 或 `ad_revenue_paid`）；
- 订阅缺 1 个（建议补 `feature_gate_shown`）；
- 异常缺 1 个（建议补 `app_crashed`，但见 P1-1，崩溃建议不自建）。

**修改建议**：
1. 重做 10.2.1 表格，改为**逐事件清单表**（事件名 / 类别 / Tier / 触发时机 / 属性 / 用途），而不是"类别计数表"，让计数天然自洽；
2. 引入 **Tier 分级**：`core`（12~15 个，进核心看板与漏斗）、`diagnostic`（长尾诊断，采样或按需开启）。这样既符合 10.1 的"精简优先"原则，又能容纳 37 个事件；
3. 10.8 验收标准改为"事件字典中已定义的 N 个事件 100% 覆盖，属性填充率 ≥ 95%"（**属性填充率**比"都埋上了"更可测）。

### P0-7｜合规章节缺"数据保留期限"与"数据删除权"（10.6）

印尼 PDP Law、泰国 PDPA、菲律宾 DPA 均赋予数据主体**访问 / 更正 / 删除 / 撤回同意**的权利，文档只写了"用户可关闭上报"，**关闭 ≠ 删除已收集的数据**。

**修改建议**（10.6 新增两条）：
- **保留期限**：明确原始事件明细保留 **13 个月**（兼顾年同比），超期自动转为聚合表或删除；会话聚合表可长期保留。需在隐私政策中同步声明。
- **删除机制**：设置页提供"删除我的数据"入口 → 调用 `DELETE /api/v1/analytics/me` → 服务端按 `device_uuid` 硬删除或匿名化（保留聚合量）。需说明删除的生效时限（如 30 天内）与异步任务实现。
- **同意凭证**：记录同意时间、隐私政策版本号、同意/撤回操作日志，用于合规举证。

---

## 三、P1 建议补充（影响数据质量与线上稳定性）

### 事件体系

| # | 问题 | 建议 |
|---|---|---|
| P1-1 | 异常类含"崩溃"，但自建 SDK **无法可靠捕获崩溃**（进程将死，队列/磁盘写入来不及） | 崩溃监控交给 **Firebase Crashlytics 或 Sentry**；自建埋点负责业务异常（`permission_denied` 等）。文档需明确这条边界，避免研发用错工具 |
| P1-2 | 缺**公共属性（common properties）规范** | 定义所有事件自动携带的全局属性：app_version、os_version、device_model、manufacturer、locale、country、network_type、session_id、session_index、is_pro、install_source、experiment_id。Batch 头里现有 3 个字段不足以支撑分群分析 |
| P1-3 | `screen_viewed` 缺**停留时长** | 表头写的是"进入与停留"，属性里却没有时长。建议新增 `screen_exited`（携带 `duration_ms`）或在 `screen_viewed` 中回填上一页时长 |
| P1-4 | 转化漏斗不完整 | 补 `feature_gate_shown`（Pro 拦截曝光，转化漏斗真正的起点）、`purchase_cancelled`；校准漏斗补 `calibration_abandoned`（`step`、`reason`） |
| P1-5 | **通知链路无埋点** | 充电保护是本 App 核心价值，必须补 `notification_permission_*`、`notification_received` / `notification_clicked` / `notification_dismissed`、`reminder_enabled`，否则无法衡量核心功能的真实触达率 |
| P1-6 | 缺**安装归因** | 补 Play Install Referrer 采集，`install_source` / `utm_*` 作为设备级属性，否则无法评估买量质量与渠道 LTV |
| P1-7 | 事件命名时态混用 | `viewed/clicked/started`（过去式）与 `impression`（名词）混用。建议统一为"对象_过去式动词"，`banner_impression` → `banner_shown` |
| P1-8 | `screen_viewed` 示例值 `previous_screen: "app_launch"` 不在 `screen_name` 枚举内 | 枚举需补 `app_launch`（或统一用 `null` 表示首屏） |
| P1-9 | 缺 Pro 状态 / 实验分组等**用户级属性** | 事件属性只能描述"那一刻发生了什么"，无法做"Pro 用户 vs 免费用户"分群。需补充 user_properties 机制（`is_pro`、`protect_enabled`、`device_tier`） |

### SDK 设计（10.3）

| # | 问题 | 建议 |
|---|---|---|
| P1-10 | `sampling_rate` 若按事件随机采样，会**破坏漏斗与留存**（同一用户不同事件被采/不采） | 必须按 `hash(device_id) % 100 < rate*100` 做**稳定分桶**；且采样配置变化时需在事件属性中记录 `sample_rate`，分析时还原权重 |
| P1-11 | 仅上报 `client_timestamp`，设备时钟可偏差/被篡改 | 增加**时钟偏移校正**：客户端记录发起请求时的 client_time，服务端用响应体已有的 `server_timestamp` 计算 offset 并本地持久化，后续事件上报校正后的时间。服务端应拒收偏移 > 24h 的事件 |
| P1-12 | 离线事件无 TTL，留存队列中会混入**数周前的陈旧事件**污染当日指标 | 客户端事件 TTL 7 天；服务端按 `server_timestamp - client_timestamp` 校验，超期事件写入 `analytics_events_rejected` 并告警 |
| P1-13 | 队列上限策略不完整 | "500 条满且全为关键事件"时行为未定义；建议：非关键事件上限 400 + 关键事件上限 100 + 单条事件 ≤ 2KB（超限截断 properties 并打标 `truncated: true） |
| P1-14 | 同意状态（consent）生效时机未说明 | SDK 架构需增加 **ConsentManager**，且**必须在 SDK 初始化前**读取同意状态：未同意则不生成 device_id、不初始化数据库。否则"用户关闭埋点"在启动瞬间已失效 |
| P1-15 | 关闭埋点后本地缓存事件如何处理未定义 | 明确：关闭开关 → 停止采集 + **清除本地队列与 device_id**，并在下次启动时不再自动重注册 |

### 后端设计（10.4）

| # | 问题 | 建议 |
|---|---|---|
| P1-16 | **"API 接收 → 写入 analytics_events 表"** 与 P95 ≤ 200ms 目标冲突：高 QPS 下 MySQL 直接写入是瓶颈 | 改为：API 仅做鉴权/校验/限流 → `LPUSH`/Stream 写入 Redis → Worker 攒批（1000 条或 5 秒）批量 insert。API 侧 DB 写入降为 0，P95 才有保障 |
| P1-17 | 10.4.5 "分发 ProcessAnalyticsEvent Job" 若**按事件**分发，20 条/请求 = 20 个 Job，队列压力放大 20 倍 | 明确为**按 batch 分发单个 Job**，Job 内批量处理 |
| P1-18 | 缺**事件 Schema 白名单校验** | 服务端维护事件/属性字典（JSON Schema），拒绝未知事件名与超白名单属性，返回被拒原因；未知事件计数上报到告警。防止 SDK bug 或恶意数据污染仓库 |

### 其他后端细节（合并说明）

- **分表/分区表述模糊**：建议明确"按月 RANGE 分区（`server_timestamp`）+ 13 个月保留 + 过期分区 DROP"，并说明查询需带分区裁剪条件。
- **JSON 查询性能**：`properties` 全 JSON 存储会导致高频分析全表扫。建议将高频维度（`screen_name`、`ad_unit_id`、`plan_id`、`permission_name`）抽取为**独立列 + 生成列索引**。
- **限流口径**：按"请求数 10 次/分钟"不够——远程配置若把 `batch_size` 调小，请求数会暴涨。建议改为**事件数限流**（如 600 事件/分钟）+ 请求数双重限流，429 响应必须带 `Retry-After` 头（客户端据此退避，而非固定 2s）。
- **会话表**：`analytics_sessions` 标注"可选"，但留存/时长类指标依赖它，建议改为**必需**；且需服务端兜底：App 被杀时会话结束事件丢失，应由 Worker 按"最后事件时间 + 30min"判定会话结束（`ended_at` 可空）。
- **Token 轮换**：90 天有效期需说明续期机制（临近过期时静默刷新），避免到期后数据断流。
- **监控告警**：补充队列积压长度、写入失败率、429 比例、未知事件数、单设备事件量异常——这些是埋点系统自身的生命线。

---

## 四、P2 优化建议（提升可持续性与分析深度）

| # | 建议 | 价值 |
|---|---|---|
| P2-1 | 建立**事件字典（Event Dictionary）**并版本化管理：schema_version、废弃流程、变更需评审 | 避免半年后无人敢动、无人能改 |
| P2-2 | SDK 内置**调试模式**（实时事件流日志 + 属性校验面板） | 埋点验收效率提升数倍，减少"上线后发现字段是 null" |
| P2-3 | 事件属性预留 `experiment_id` / `variant` | 为后续 AB 实验预留，否则改造成本极高 |
| P2-4 | 远程配置支持**按 app_version / country 灰度下发** | 新版埋点灰度验证、问题版本快速止血 |
| P2-5 | 明确数据**时效性 SLA**：实时指标（T+5min）vs 离线指标（T+1） | 避免业务方对"看板什么时候能看到"产生分歧 |
| P2-6 | 看板补充**北极星指标**与指标 owner、埋点变更通知机制 | 数据没人用 = 白做 |
| P2-7 | 广告增加 **impression-level revenue** 与 AdMob 后台对账报表 | 收入归因与 eCPM 优化的基础 |
| P2-8 | 事件属性限制 JSON 深度 ≤ 2 层、key ≤ 20 个（对齐 10.1 原则 4） | 防止后续埋点失控膨胀 |
| P2-9 | 补 `app_updated` 事件与版本迁移分析 | 版本升级后的留存/崩溃归因 |
| P2-10 | 补充**成本估算与容量规划**：按 DAU × 日均事件数 × 单条大小估算存储与写入 QPS | 避免上线后发现存储成本超预期 |

---

## 五、里程碑与工期评估（10.7）

**结论：+2 周低估，且排期顺序存在风险。**

| 问题 | 说明 | 建议 |
|---|---|---|
| M2 做 SDK、M3 才做后端 | 整个 M2（第 7-10 周）SDK **无法联调验证**，问题会积压到 M3 集中爆发 | 改为**契约先行**：M2 启动前先定稿 OpenAPI 规范 + 事件字典，用 Mock Server 联调 |
| 合规审查放在 M5 | 太晚。数据本地化（印尼/越南）会**决定服务器选型与部署架构**，M5 才审意味着架构可能返工 | 合规评审**前置到 M2**（与 SDK 设计同步），M5 只做上线前复核 |
| +2 周低估 | 拆算：SDK（事件/队列/持久化/配置）约 3 周、后端（4 接口 + 队列 + 分区 + 幂等）约 2.5 周、看板约 2 周、合规约 1 周 | 串行需 +8 周；若 2 人并行（客户端 + 后端）且看板用 Metabase 而非自研，可压缩到 **+4~5 周**。请在 PRD 中写明并行人力假设，而非笼统"+2 周" |

---

## 六、验收标准修订建议（10.8）

现有标准多为定性描述，建议改为**可量化、可复现**的条目：

| 现有条目 | 问题 | 修改建议 |
|---|---|---|
| 核心事件 37 个全部按规范命名 | 与明细对不上 | 改为"事件字典中 N 个事件 100% 覆盖，属性非空填充率 ≥ 95%，未知事件占比 < 0.1%" |
| 批量上报成功率 ≥ 99% | 未定义网络条件与测量方式 | "弱网（RTT 500ms、丢包 5%）场景下，24 小时内事件最终送达率 ≥ 99%，重复入库率 = 0" |
| 离线缓存不丢失 | 场景模糊 | "断网 30 分钟并杀进程后，重启联网 60 秒内完成补报，事件零丢失、零重复" |
| CPU ≤ 1%、主线程无阻塞 | 缺测量口径 | "连续采集 1000 事件的完整会话中，主线程单次 `track()` P99 ≤ 5ms，StrictMode 无磁盘/网络违规" |
| 开关关闭后停止采集 | 缺验证手段 | "关闭后抓包确认零网络请求，且本地 SQLite 队列被清空" |
| P95 ≤ 200ms | 缺压测量级 | "1000 并发设备 × 20 条/批 场景下，上报接口 P95 ≤ 200ms、P99 ≤ 500ms" |
| 看板可展示核心指标 | 不可测 | 逐条列出看板名称与对应 SQL 口径，并要求"T+1 08:00 前数据就绪，与客户端抽样日志差异率 < 1%" |

**另建议新增 3 条**：
- 数据准确性：随机抽取 100 条客户端本地日志与服务端记录逐条比对，一致率 100%；
- 端到端时延：从事件发生到可在看板查询 ≤ 5 分钟（实时指标）；
- 防污染：脚本模拟 1000 个伪造设备注册，被拦截率 ≥ 99%。

---

## 七、建议新增的事件清单（可直接并入 10.2）

| 事件名 | 类别 | Tier | 关键属性 |
|---|---|---|---|
| `app_first_open` | 生命周期 | core | `install_source`、`referrer` |
| `permission_requested` | 异常/权限 | core | `permission_name`、`context`、`is_rationale_shown` |
| `feature_gate_shown` | 订阅转化 | core | `feature_name`、`current_plan` |
| `purchase_cancelled` | 订阅转化 | core | `plan_id`、`cancel_step` |
| `notification_received` | 核心功能 | core | `notification_type`、`is_foreground` |
| `notification_clicked` | 核心功能 | core | `notification_type`、`time_since_received_ms` |
| `charge_session_started` | 核心功能 | core | `start_percent`、`is_protection_on` |
| `charge_session_ended` | 核心功能 | core | `duration_ms`、`end_percent`、`max_temp_c` |
| `calibration_abandoned` | 核心功能 | supporting | `step`、`reason` |
| `smart_saving_enabled` | 核心功能 | core | `mode`、`target_app_count` |
| `ad_revenue_paid` | 广告 | core | `ad_unit_id`、`revenue_micros`、`currency`、`precision_type` |
| `screen_exited` | 页面浏览 | supporting | `screen_name`、`duration_ms` |
| `onboarding_step_completed` | 生命周期 | core | `step_index`、`step_name` |

> 崩溃监控不在此列，交由 Crashlytics/Sentry；订阅收入真相源为 Google Play RTDN。

---

## 八、优先级修改路线（建议执行顺序）

1. **先修 P0-1 / P0-2 / P0-3**：指标对齐 + 包名合规 + 幂等去重 —— 这三件事决定了数据"能不能用"和"合不合规"，且改动成本低（多为增补与索引）。
2. **再补 P0-4 / P0-5**：注册防护 + RTDN 订阅链路 —— 涉及服务端架构，需在 M3 前定稿。
3. **同步推进 P0-6 / P0-7**：重做事件清单表（Tier 分级）+ 合规保留/删除条款 —— 文档层改动，但影响法务与验收。
4. **P1 项随开发落地**，其中 P1-10（稳定分桶）、P1-11（时钟校正）、P1-16（Redis 缓冲写入）建议在 M2/M3 设计评审时一并确定，避免返工。
