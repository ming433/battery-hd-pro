<?php

/*
|--------------------------------------------------------------------------
| 属性枚举字典（PRD 10.2.5）
|--------------------------------------------------------------------------
| 本文件是 PRD 10.2.5 的可执行版本：客户端与服务端共用同一份取值域。
|
| 结构：
|   属性名 => ['type' => 'string'|'int'|'bool'|'number', 'values' => [...]]
|
|   type=string/int  → values 为枚举白名单，非白名单值写入 rejected 表
|   type=bool        → 必须是 JSON boolean（禁止 "true"/1/"yes"）
|   type=number      → 必须是 int 或 float（禁止字符串数字）
|
| 变更流程见 PRD 附录 A：新增枚举值需客户端与服务端同步发版。
*/

return [

    // ---------------------------------------------------- 广告触发场景
    'trigger_scene' => [
        'type'   => 'string',
        'values' => [
            'app_cold_start', 'screen_switch', 'charge_complete',
            'calibration_complete', 'report_generated', 'feature_gate_exit',
            'app_exit', 'manual_debug',
        ],
    ],

    // ------------------------------------------------------------ 权限
    'permission_name' => [
        'type'   => 'string',
        'values' => [
            'notification', 'usage_stats', 'battery_optimization',
            'system_alert_window', 'exact_alarm', 'storage',
        ],
    ],

    'context' => [
        'type'   => 'string',
        'values' => ['onboarding', 'feature_use', 'settings', 'system_dialog'],
    ],

    // ------------------------------------------------------------ 订阅
    'plan_id' => [
        'type'   => 'string',
        'values' => [
            'pro_monthly', 'pro_yearly', 'pro_lifetime',
            'trial_monthly', 'promo_yearly',
        ],
    ],

    // ------------------------------------------------------------ 通知
    'notification_type' => [
        'type'   => 'string',
        'values' => [
            'charge_limit_reached', 'temp_alert', 'charge_complete',
            'weekly_report', 'calibration_reminder', 'battery_health_drop',
            're_engagement', 'promo',
        ],
    ],

    'reminder_type' => [
        'type'   => 'string',
        'values' => ['charge_limit', 'temp_alert', 'weekly_report', 'calibration'],
    ],

    // -------------------------------------------------------- 错误码
    // 广告：AdMob LoadAdError.getCode() 原样透传
    'ad_error_code' => [
        'type'   => 'int',
        'values' => [0, 1, 2, 3, 8, 9],
    ],

    // 购买：Google Play Billing ResponseCode
    'purchase_error_code' => [
        'type'   => 'int',
        'values' => [-1, 1, 2, 3, 4, 5, 6, 7, 8],
    ],

    // ------------------------------------------------------ 应用分类
    'app_category' => [
        'type'   => 'string',
        'values' => [
            'social', 'video', 'game', 'shopping', 'browser',
            'im', 'system', 'other',
        ],
    ],

    // ------------------------------------------------------------ 页面
    'screen_name' => [
        'type'   => 'string',
        'values' => [
            'home_dashboard', 'battery_monitor', 'charge_protection',
            'power_analysis', 'smart_saving', 'settings',
            'pro_upgrade', 'help_webview',
        ],
    ],

    // 与 screen_name 同域；首屏无前序页面时传 null（PRD 10.2.3）
    'previous_screen' => [
        'type'   => 'string',
        'values' => [
            'home_dashboard', 'battery_monitor', 'charge_protection',
            'power_analysis', 'smart_saving', 'settings',
            'pro_upgrade', 'help_webview',
        ],
    ],

    // ----------------------------------------------------- 其他字符串枚举
    'launch_type' => [
        'type'   => 'string',
        'values' => ['cold', 'warm', 'hot'],
    ],

    'method' => [
        'type'   => 'string',
        'values' => ['manual', 'guided'],
    ],

    'time_range' => [
        'type'   => 'string',
        'values' => ['day', 'week'],
    ],

    'source' => [
        'type'   => 'string',
        'values' => ['banner', 'feature_gate', 'settings', 'notification', 'onboarding'],
    ],

    'ad_format' => [
        'type'   => 'string',
        'values' => ['banner', 'interstitial', 'rewarded', 'native'],
    ],

    'network' => [
        'type'   => 'string',
        'values' => ['admob', 'admob_mediation', 'unknown'],
    ],

    'precision_type' => [
        'type'   => 'string',
        'values' => ['unknown', 'estimated', 'publisher_provided', 'precise'],
    ],

    'network_type' => [
        'type'   => 'string',
        'values' => ['wifi', 'cellular', 'offline', 'unknown'],
    ],

    'charger_type' => [
        'type'   => 'string',
        'values' => ['ac', 'usb', 'wireless', 'unknown'],
    ],

    'cancel_step' => [
        'type'   => 'string',
        'values' => ['plan_select', 'confirm', 'payment_sheet'],
    ],

    'payment_method' => [
        'type'   => 'string',
        'values' => ['google_play', 'unknown'],
    ],

    // 注意：`reason` 这个属性名在多个事件中含义完全不同。
    // 这里是 ad_config_invalid 用的取值域；calibration_abandoned 的 reason
    // 走下方 event_overrides 映射到 calibration_abandon_reason，避免被本规则误判。
    'reason' => [
        'type'   => 'string',
        'values' => ['empty_unit_id', 'placement_disabled', 'kill_switch', 'schema_mismatch'],
    ],

    'calibration_abandon_reason' => [
        'type'   => 'string',
        'values' => ['user_cancel', 'app_killed', 'timeout', 'charge_interrupted'],
    ],

    'step' => [
        'type'   => 'string',
        'values' => ['discharge', 'full_charge', 'verify'],
    ],

    'mode' => [
        'type'   => 'string',
        'values' => ['balanced', 'aggressive', 'custom'],
    ],

    'missing_permission' => [
        'type'   => 'string',
        'values' => [
            'notification', 'usage_stats', 'battery_optimization',
            'system_alert_window', 'exact_alarm', 'storage',
        ],
    ],

    // -------------------------------------------------------- 布尔属性
    // 必须是 JSON boolean，禁止 1/0/"true"/"yes"
    'bool_props' => [
        'type'   => 'bool',
        'values' => [
            'was_skipped', 'is_pro', 'enabled', 'success', 'granted',
            'is_foreground', 'is_first_launch', 'is_charging',
            'is_protection_on', 'is_rationale_shown', 'is_first_purchase',
        ],
    ],

    // -------------------------------------------------------- 数值属性
    // 必须是 JSON number（int 或 float），禁止字符串数字
    'number_props' => [
        'type'   => 'number',
        'values' => [
            'launch_duration_ms', 'background_duration_ms', 'session_duration_ms',
            'screens_visited', 'session_depth', 'duration_ms', 'step_index',
            'health_score', 'capacity_mah', 'temperature_c', 'threshold_c',
            'expanded_count', 'scenes_visible', 'limit_percent', 'current_percent',
            'start_percent', 'end_percent', 'max_temp_c', 'limit_triggered_count',
            'cycles_completed', 'measured_capacity_mah', 'deviation_percent',
            'apps_shown', 'target_app_count', 'week_number', 'session_event_count',
            'retry_index', 'revenue_micros', 'close_time_ms',
            'config_version', 'fail_count', 'cooldown_seconds',
            'price_local', 'time_since_received_ms',
        ],
    ],

    // ------------------------------------------------------------ 引导
    'step_name' => [
        'type'   => 'string',
        'values' => [
            'welcome', 'battery_permission', 'usage_permission',
            'notification_permission', 'feature_intro', 'done',
        ],
    ],

    'report_type' => [
        'type'   => 'string',
        'values' => ['weekly', 'monthly', 'custom'],
    ],

    // ------------------------------------------------------ 订阅相关
    // current_plan 与 plan_id 不同：前者是用户当前状态，后者是商品 SKU
    'current_plan' => [
        'type'   => 'string',
        'values' => ['free', 'trial', 'pro'],
    ],

    'feature_name' => [
        'type'   => 'string',
        'values' => [
            'advanced_health_report', 'unlimited_calibration', 'ad_free',
            'custom_charge_limit', 'hibernation_automation', 'detailed_power_analysis',
        ],
    ],

    // ------------------------------------------------------------ 货币
    // ISO 4217；东南亚目标市场 + AdMob 结算币种
    'currency' => [
        'type'   => 'string',
        'values' => ['IDR', 'VND', 'THB', 'PHP', 'MYR', 'SGD', 'USD'],
    ],

    // ------------------------------------------------------ 事件级覆盖
    // 同名属性在不同事件中取值域不同（PRD 10.2.5（5）：error_code 两个来源隔离）
    'event_overrides' => [
        'ad_load_failed'        => ['error_code' => 'ad_error_code'],
        'purchase_failed'       => ['error_code' => 'purchase_error_code'],
        // reason 在 calibration_abandoned 中表达"为什么放弃校准"，
        // 与 ad_config_invalid 的"配置为何无效"不是同一回事
        'calibration_abandoned' => ['reason' => 'calibration_abandon_reason'],
    ],

    // -------------------------------------------------------- 动态取值域
    // 这些属性由管理后台维护，不能静态枚举，否则运营新增配置后埋点会被误拒。
    // 取值域在运行时从数据库读取并缓存（见 EventDictionary::dynamicValues）。
    'dynamic_props' => [
        'placement_id' => 'ad_placements',   // 广告位稳定键（= ad_placements.placement_key）
    ],

    // ------------------------------------------------------ 格式约束属性
    // 非枚举但需格式校验，防止注入与脏数据
    'format_props' => [
        'ad_unit_id' => '/^ca-app-pub-\d{15,20}\/\d{9,12}$/',   // AdMob 单元 ID 格式
        // 订单号格式会随 Google 调整，此处只做宽松的字符集与长度约束，
        // 避免误拒 purchase_completed（关键事件、收入对账依赖）
        'order_id'   => '/^[A-Za-z0-9._-]{4,64}$/',
    ],
];
