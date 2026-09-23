# PRD 第 10 章（修订版 v2.0）歧义与澄清清单

> 评审方式：以"研发拿着文档直接开工"的视角逐节走查，重点识别**会导致理解分歧、实现返工或技术上无法落地**的表述。
> 结论：**9 项 P0（建议澄清后再开发）+ 16 项 P1（需补规格）+ 9 项 P2（建议明确）**。
> 其中 P0-1 是数据库层面的**技术不可行**，P0-2 是**架构前提未声明**，P0-3 是**公式错误**——这三条建议立即修正。

---

## 一、P0：澄清前不宜开工

### P0-1｜`analytics_events` 分区表 + `UNIQUE(batch_id, event_index)` 在 MySQL 上无法创建

**位置**：10.4.3

**问题**：MySQL 8 分区表有硬性限制——**表中每一个唯一键（含主键）都必须包含分区表达式用到的所有列**。文档定义按 `server_timestamp` 做月分区，同时又要求 `UNIQUE(batch_id, event_index)`，该索引不含分区键，**建表会直接报错**。主键 `id` 同样不满足要求。

**影响**：后端开发在建表阶段就会卡住，且这是"改索引"级别的返工（涉及去重方案整体重构）。

**建议修订**：
1. 幂等去重**移出分区表**：新增非分区表 `analytics_batches`（`batch_id` 主键、`device_id`、`event_count`、`received_at`、`app_version`）。上报时先 `INSERT IGNORE` 该表，affected_rows = 0 即判定为重复批次，直接返回 `duplicate: true`。
2. `analytics_events` 主键改为 `(id, server_timestamp)`，不再建唯一索引。
3. 说明：去重粒度为**批次级**（而非事件级），这与"重试复用同一 `batch_id`"的机制一致，语义更清晰。

### P0-2｜全文假设 Google Play 服务可用，但未声明分发渠道前提

**位置**：10.4.2（Play Integrity）、10.4.7（RTDN）、10.5.5（FCM 静默推送）、广告方案（AdMob）

**问题**：整套方案同时依赖 **AdMob + Play Integrity + FCM + Play RTDN**，四者全部要求设备具备 GMS（Google Mobile Services）。但东南亚是**无 GMS 设备占比最高的市场之一**：华为设备（AppGallery）、部分三星/国产机、第三方渠道（APKPure、Aptoide）安装的包，GMS 可能缺失或不可用。

**影响**：这是**架构级前提**，不是细节。若存在非 Play 分发渠道，则广告 SDK、崩溃监控、推送、订阅验证、设备真实性校验五个模块需要全部替换或做降级，工期影响以"月"计。

**需 PM 明确**：
- [ ] 是否**仅 Google Play 分发**？
- [ ] 若存在第三方渠道，无 GMS 设备上的降级策略：广告（Meta Audience Network / Pangle / 穿山甲海外）、推送（厂商通道或自建长连接）、订阅（渠道自有支付）、埋点上报（仅 HTTP，不依赖 GMS——此部分无影响）。
- [ ] Play Integrity 在无 GMS 设备上的兜底（降级为 IP 限流 + 行为风控）。

### P0-3｜eCPM 计算公式错误 10⁶ 倍

**位置**：10.6 广告表现行

**问题**：文档写 `eCPM = ad_revenue_paid.revenue_micros / 展示量 × 1000`。但 AdMob `AdValue.getAmountMicros()` 的单位是**百万分之一货币单位**，必须先除以 10⁶ 换算为真实金额，否则 eCPM 会放大 100 万倍。

**正确公式**：
```
eCPM = ( Σ revenue_micros / 1,000,000 ) / 展示量 × 1000
```
同时建议注明：`currency` 为 AdMob 账户结算币种（通常 USD），跨币种对比需按日汇率归一化，否则东南亚多国数据不可比。

### P0-4｜会话边界存在"双权威"，且删掉的端点未说明

**位置**：10.3.2 / 10.4.3 / 10.4.4

**问题**：
- 10.3.2 说会话结束条件有两个："进入后台"**或**"超过 30 分钟无交互"。但 `app_backgrounded` 携带 `session_duration_ms`，说明**进后台即视为会话结束**——那"前台 30 分钟无交互"这条分支实际永远不会触发（用户要么在前台交互、要么进后台）。两条规则互斥且冗余。
- 10.4.3 又说服务端按"最后事件时间 + 30min"兜底判定会话结束，于是客户端与服务端各有一套判定逻辑，**可能算出不同的会话数**。
- 初版有 `POST /api/v1/analytics/session` 端点，修订版删除了但**未说明删除原因与替代方案**，研发会问"会话还要不要上报"。

**建议**：明确单一权威——**服务端以事件流推导为唯一权威**（跨版本、跨设备一致），客户端 `session_id` 仅作事件关联标识；`analytics_sessions` 完全由 Worker 生成，`session_duration_ms` 仅作校验参考，冲突时以服务端为准。并在附录 B 补一条"删除 session 端点，改由服务端推导"。

另需定义 `session_index`（10.2.2 公共属性）的计数规则：按设备累计第几个会话、从 0 还是 1 开始、本地持久化位置。

### P0-5｜充电会话采集在 Android 现行限制下不可行，方案缺失

**位置**：D5 `charge_session_started` / D6 `charge_session_ended`

**问题**：这两个事件是"平均充电时长"的唯一数据来源，但文档未说明采集方式，而按常规写法会失败：
- `ACTION_POWER_CONNECTED` / `ACTION_POWER_DISCONNECTED` **不在 Android 8+ 隐式广播豁免列表**，静态注册无效；
- `ACTION_BATTERY_CHANGED` 是粘性广播，**只能动态注册**，进程被杀后收不到；
- Android 12+ 限制后台启动前台服务，无法常驻监听。

**结果**：充电结束事件大量丢失 → 充电时长指标严重偏差，且是"上线后才发现"的问题。

**建议**（需 PM 与客户端负责人确认后写入 PRD）：
1. **复用充电保护模块的充电状态监听**（该模块必然已有监听方案），埋点只做订阅，不重复实现；
2. 明确"会话重建"逻辑：App 启动时若本地存在未结束的充电会话，按 `BatteryManager` 当前状态补报 `charge_session_ended`（携带校正后的客户端时间戳）；
3. 明确进程被杀导致的会话丢失为**已知数据缺口**，看板需标注覆盖率（建议验收项：充电会话捕获率 ≥ 85%）。

### P0-6｜Play Integrity 接入流程与配额未说明，Token 续期机制缺失

**位置**：10.4.2

**问题**：
- 文档只说"Play Integrity API attestation"，但完整流程是：客户端请求 integrity token → 提交给服务端 → **服务端调用 Google API 解密验证**。文档未写这一来一往，研发无法直接实现。
- Play Integrity **默认调用配额有限**（标准层每日约 1 万次），百万级 DAU 的注册场景会直接打满。需说明配额申请与降级策略。
- "临近过期静默续期"未定义：提前多少天？是否复用 register？旧 Token 是否立即失效（会导致在途请求 401）？

**建议**：补充时序说明 + 配额策略（新设备注册走 attestation，续期走轻量校验）+ 续期规则（剩余 < 7 天时续期，新旧 Token 重叠 24 小时有效）。

### P0-7｜"关键事件""免采样事件"未落到事件字典字段

**位置**：10.3.4 / 10.2.4（C 节）

**问题**：10.3.4 定义关键事件为 `purchase_completed`、`calibration_completed`、`ad_revenue_paid`；10.2.4 又规定 `permission_requested` 虽为 T2 但"不参与采样"。这两类例外目前只存在于散文描述中，**事件字典里没有对应字段**，研发只能硬编码，后续加事件必然遗漏。

**建议**：在附录 A 的事件字典 Schema 中显式定义两个布尔字段：
- `critical: true`（队列满时不丢弃）
- `no_sampling: true`（不受 `sampling_rate` 影响）

### P0-8｜`country` 来源未统一，广告配置与埋点可能对不上

**位置**：10.2.2（公共属性 `country`）、10.5.5（`GET /config/ads?country=ID`）

**问题**：`country` 的取值来源未定义——SIM 卡国家、系统 locale、还是服务端按 IP 判定？东南亚用户**跨国漫游、SIM 与 locale 不一致**极为常见（如印尼用户手机 locale 为 en-US）。若配置下发用一个来源、埋点属性用另一个来源，会出现"看板显示 VN 用户看到的是 ID 配置"的对不上账问题，直接影响 eCPM 归因。

**建议**：明确优先级——**服务端按请求 IP 判定为准**（广告与收入归因口径一致），客户端同时上报 SIM country 与 locale 作为辅助字段；埋点公共属性的 `country` **必须与配置下发使用同一取值**（建议由服务端在响应中回写，客户端持久化）。

### P0-9｜`ad_requested` 计数口径与广告事件命名双前缀

**位置**：10.2.4 E 节

**问题**：
- **计数口径**：配置项里有 `retry_count`，一次广告展示可能发起多次请求。`ad_requested` 是"每次重试都记"还是"每次展示意图记一次"，直接决定填充率数值，必须明确（建议：按展示意图记一次，重试不计入，重试次数作为属性 `retry_index`）。
- **命名双前缀**：同一类事件混用 `ad_requested` / `ad_load_failed` / `ad_revenue_paid`（`ad_` 前缀）与 `banner_shown` / `banner_clicked` / `interstitial_shown`（媒介前缀）。研发会困惑"新增激励视频该叫 `ad_shown` 还是 `rewarded_shown`"。这与 10.1 原则 3 的命名统一要求自相矛盾。

**建议**：统一为 `ad_` 事件族 —— `ad_requested` / `ad_shown` / `ad_clicked` / `ad_closed` / `ad_load_failed` / `ad_revenue_paid`，用 `ad_format`（banner/interstitial/rewarded/native）+ `placement_id` 区分。若坚持保留 banner/interstitial 命名，需在 10.1 中说明例外规则。

---

## 二、P1：需要补充规格（不影响开工，但影响一致性）

| # | 位置 | 问题 | 建议 |
|---|---|---|---|
| P1-1 | 10.2.4 E / 10.5.3 | `trigger_scene` **枚举未定义**，而它是配置项 `trigger_scenes` 与埋点属性的双向依赖 | 给出枚举：calibration_completed / weekly_report_viewed / scan_finished / app_backgrounded / feature_gate_shown |
| P1-2 | 10.2.4 C/G | `permission_name` 枚举未给 | 给出：POST_NOTIFICATIONS / PACKAGE_USAGE_STATS / BATTERY_STATS / SYSTEM_ALERT_WINDOW |
| P1-3 | 10.2.4 F | `plan_id` 枚举未给（月付/年付/终身？） | 给出定价方案枚举 |
| P1-4 | 10.2.4 H | `notification_type` 枚举未给 | 给出：charge_limit / temp_alert / weekly_report / calibration_reminder |
| P1-5 | 10.2.4 E/F | `error_code` **同名不同义**：广告侧是 AdMob error domain，购买侧是 Play Billing responseCode | 注明各自枚举来源，避免混淆 |
| P1-6 | D11 | `app_category` 的**映射规则未定义** | 说明：Android 8+ 用 `ApplicationInfo.category`；低版本按包名前缀白名单映射；无法归类记 `unknown` |
| P1-7 | 10.5.4 | `ad_policies.app_version_min/max` 的**版本比较方式** | 明确用语义化版本比较（`version_compare`），字符串比较会导致 1.10.0 < 1.9.0 的错误 |
| P1-8 | 10.5.4 | `user_segment = new`（新用户）定义 | 明确：安装后 7 天内 / 首次启动当天？（直接影响运营配置） |
| P1-9 | 10.5.4/10.5.5 | `rollout_percent`（哈希分桶）与 `target_json`（条件）**两套筛选机制的关系**未说明 | 明确为 AND 关系，且哈希分桶仅对命中条件的设备生效 |
| P1-10 | 10.5.5 | 缓存 key `ad:cfg:{country}:{version_bucket}:{segment}:{version}` 中 **`version` 指配置版本还是 App 版本**命名歧义 | 改名 `config_version` 与 `app_version_bucket` |
| P1-11 | 10.5.5 / 10.5.8 | **刷新时机与验收矛盾**：TTL 300s，但若用户持续在前台且从不切后台，何时刷新？"15 分钟 90% 生效"无法保证 | 明确前台定时轮询间隔（建议 5 分钟）+ 前后台切换 + FCM 唤醒三重触发 |
| P1-12 | 10.4.4 | **部分成功**时 `received` 与 `duplicate` 的语义不清（一批中部分事件 Schema 校验失败） | 明确：`received` = 成功入库条数，`rejected` = 被拒条数，`duplicate` = 整批重复 |
| P1-13 | 10.3.1 / 10.3.5 | **线程模型未说明**（内存队列如何保证线程安全、SQLite 是否批量事务） | 补充：单线程 dispatcher + Channel；DB 写入批量事务；`track()` 仅做入队 |
| P1-14 | 10.3.4 / 10.3.5 | `batch_size` 远程可配，但**与"请求体 ≤ 100KB"的约束关系未定义**（调到 50 会超限） | 明确服务端下发 `batch_size` 上限 ≤ 50，客户端取 `min(batch_size, 100KB/近30条均值)` |
| P1-15 | 10.3.4 | 队列淘汰**优先级未定义**：TTL 7 天与 500 条上限同时触发时先按哪个 | 明确：每次入队先按 TTL 清理，再按条数 FIFO |
| P1-16 | 跨模块 | **依赖未声明**：`is_pro`（订阅模块）、充电状态（充电保护模块）、权限结果（权限模块）均为外部输入 | 新增"依赖关系与接口约定"小节，明确各字段由谁提供、更新时机 |

---

## 三、P2：建议明确的细节

| # | 问题 | 建议 |
|---|---|---|
| P2-1 | 采样 / 远程开关 / `disabled_events` / 用户同意的**优先级链未定义** | 明确：用户同意 > 远程 `analytics_enabled` > `disabled_events` > Tier 采样 |
| P2-2 | `app_first_open` 携带 `is_first_launch` 属性**语义冗余** | 删除该属性，或说明其表示"是否清除过数据后的首次" |
| P2-3 | "首次启动"的定义（安装后首次？升级后？清数据后？） | 明确：以本地持久化标记为准，清除数据或重装后重新计为首次 |
| P2-4 | 公共属性与单事件属性**同名冲突**时谁优先 | 明确单事件属性优先（可覆盖公共属性） |
| P2-5 | `previous_screen` 首屏取 `null`，但属性表未标注可空 | 属性表增加"可空性"标注 |
| P2-6 | **用户关闭埋点后**，`ad_config_fetched` 等 T3 技术事件是否还上报 | 明确取舍：关闭后停止所有埋点上报（含 T3），接受广告配置监控存在盲区 |
| P2-7 | `is_pro` 更新时机：购买成功后本地 entitlement 何时置位 | 明确：支付回调成功后立即置位 + 立即刷新广告配置（否则 Pro 用户仍看广告，是投诉高发点） |
| P2-8 | `analytics_events_rejected` 保留期、`is_suspect` 剔除**在哪个环节执行**（ETL 还是查询时） | 明确：rejected 保留 30 天；`is_suspect` 在查询层统一过滤，保证口径一致 |
| P2-9 | 数据删除请求期间是否继续采集 | 明确：收到删除请求即置 `pending_deletion` 标记并停止入库，异步任务 30 天内完成 |

---

## 四、建议直接修订的文案（可粘贴替换）

### 4.1 替换 10.4.3 幂等与索引段

> **幂等去重**：新增非分区表 `analytics_batches`（`batch_id` PRIMARY KEY、`device_id`、`event_count`、`received_at`）。上报时先 `INSERT IGNORE`，affected_rows = 0 判定为重复批次，返回 `duplicate: true` 且不重复入库。
> 注：MySQL 分区表的唯一键必须包含分区键，因此去重索引不能建在按月分区的 `analytics_events` 上。该表主键为 `(id, server_timestamp)`。

### 4.2 替换 10.6 eCPM 口径

> eCPM = ( Σ `revenue_micros` ÷ 1,000,000 ) ÷ 展示量 × 1000。
> `currency` 为 AdMob 账户结算币种，跨币种对比需按日汇率归一化。

### 4.3 新增 10.0 节：前提与依赖

> **前提**：本方案依赖 Google Play 服务（AdMob / FCM / Play Integrity / RTDN）。若产品存在非 Play 分发渠道，需另行定义无 GMS 降级方案（广告平台、推送通道、订阅验证）。
> **外部依赖**：`is_pro`（订阅模块）、充电状态（充电保护模块）、权限申请结果（权限模块）、`country`（服务端按 IP 判定并回写客户端）。

---

## 五、建议补齐的枚举表模板（一次性填完可消除多数歧义）

| 枚举字段 | 取值 | 备注 |
|---|---|---|
| `screen_name` | 已定义（8 个） | ✅ |
| `trigger_scene` | **待补** | 广告触发场景 |
| `permission_name` | **待补** | Android 权限名 |
| `plan_id` | **待补** | 订阅方案 |
| `notification_type` | **待补** | 通知类型 |
| `ad_format` | banner / interstitial / rewarded / native | ✅ |
| `app_category` | social / video / game / tool / system / unknown | 需补映射规则 |
| `user_segment` | all / free / pro / new | "new" 需定义 |
| `error_code`（广告） | AdMob error domain | 需注明来源 |
| `error_code`（购买） | Play Billing responseCode | 需注明来源 |

---

## 六、优先级建议

1. **立即修**（当天）：P0-1（建表方案）、P0-3（eCPM 公式）、P0-4（会话权威）
2. **PM 拍板后修**（影响架构）：P0-2（GMS 前提）、P0-5（充电会话采集可行性）
3. **开发前澄清会**：P0-6 ~ P0-9、P1-1 ~ P1-5（枚举表一次性补齐）
4. **开发过程中明确**：P1-6 ~ P1-16、全部 P2
