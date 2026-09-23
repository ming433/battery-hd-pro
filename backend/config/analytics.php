<?php

/*
|--------------------------------------------------------------------------
| 埋点服务端配置（PRD F-010 v2.0）
|--------------------------------------------------------------------------
| 事件字典是客户端与服务端共用的唯一契约：
|   tier        T1 核心（不采样）/ T2 诊断（受采样率影响）/ T3 技术
|   critical    队列满时不丢弃（PRD 10.3.4）
|   no_sampling 即使是 T2/T3 也不参与采样（如 permission_requested 是拒绝率分母）
|   props       允许的属性白名单（超出部分写入时会被剥离并计数）
*/

return [

    // ------------------------------------------------------------ 事件字典
    'events' => [
        // A 生命周期
        'app_first_open'                => ['tier' => 'T1', 'critical' => false, 'no_sampling' => true,  'props' => ['install_source', 'referrer', 'is_first_launch']],
        'app_launched'                  => ['tier' => 'T1', 'critical' => false, 'no_sampling' => true,  'props' => ['launch_type', 'launch_duration_ms']],
        'app_foregrounded'              => ['tier' => 'T2', 'critical' => false, 'no_sampling' => false, 'props' => ['background_duration_ms']],
        'app_backgrounded'              => ['tier' => 'T2', 'critical' => false, 'no_sampling' => false, 'props' => ['session_duration_ms', 'screens_visited']],

        // B 页面浏览
        'screen_viewed'                 => ['tier' => 'T1', 'critical' => false, 'no_sampling' => true,  'props' => ['screen_name', 'previous_screen', 'session_depth']],
        'screen_exited'                 => ['tier' => 'T2', 'critical' => false, 'no_sampling' => false, 'props' => ['screen_name', 'duration_ms']],

        // C 引导与权限
        'permission_requested'          => ['tier' => 'T2', 'critical' => false, 'no_sampling' => true,  'props' => ['permission_name', 'context', 'is_rationale_shown']],
        'permission_denied'             => ['tier' => 'T1', 'critical' => false, 'no_sampling' => true,  'props' => ['permission_name', 'context']],
        'onboarding_step_completed'     => ['tier' => 'T2', 'critical' => false, 'no_sampling' => false, 'props' => ['step_index', 'step_name']],
        'notification_permission_result'=> ['tier' => 'T2', 'critical' => false, 'no_sampling' => false, 'props' => ['granted', 'context']],

        // D 核心功能
        'battery_health_viewed'         => ['tier' => 'T1', 'critical' => false, 'no_sampling' => true,  'props' => ['health_score', 'capacity_mah', 'temperature_c']],
        'scene_estimate_expanded'       => ['tier' => 'T2', 'critical' => false, 'no_sampling' => false, 'props' => ['expanded_count', 'scenes_visible']],
        'charge_limit_triggered'        => ['tier' => 'T1', 'critical' => false, 'no_sampling' => true,  'props' => ['limit_percent', 'current_percent', 'temperature_c']],
        'temp_alert_triggered'          => ['tier' => 'T1', 'critical' => false, 'no_sampling' => true,  'props' => ['temperature_c', 'threshold_c', 'is_charging']],
        'charge_session_started'        => ['tier' => 'T2', 'critical' => false, 'no_sampling' => false, 'props' => ['start_percent', 'is_protection_on', 'charger_type']],
        'charge_session_ended'          => ['tier' => 'T1', 'critical' => false, 'no_sampling' => true,  'props' => ['duration_ms', 'end_percent', 'max_temp_c', 'limit_triggered_count']],
        'calibration_started'           => ['tier' => 'T1', 'critical' => false, 'no_sampling' => true,  'props' => ['method']],
        'calibration_completed'         => ['tier' => 'T1', 'critical' => true,  'no_sampling' => true,  'props' => ['cycles_completed', 'measured_capacity_mah', 'deviation_percent']],
        'calibration_abandoned'         => ['tier' => 'T2', 'critical' => false, 'no_sampling' => false, 'props' => ['step', 'reason']],
        'power_ranking_viewed'          => ['tier' => 'T2', 'critical' => false, 'no_sampling' => false, 'props' => ['time_range', 'apps_shown']],
        'app_hibernation_set'           => ['tier' => 'T2', 'critical' => false, 'no_sampling' => false, 'props' => ['app_category', 'target_app_count']],
        'smart_saving_enabled'          => ['tier' => 'T2', 'critical' => false, 'no_sampling' => false, 'props' => ['mode', 'target_app_count']],
        'weekly_report_viewed'          => ['tier' => 'T2', 'critical' => false, 'no_sampling' => false, 'props' => ['week_number', 'report_type']],

        // E 广告
        'ad_requested'                  => ['tier' => 'T1', 'critical' => false, 'no_sampling' => true,  'props' => ['placement_id', 'ad_unit_id', 'network', 'ad_format', 'trigger_scene', 'retry_index']],
        'banner_shown'                  => ['tier' => 'T1', 'critical' => false, 'no_sampling' => true,  'props' => ['placement_id', 'ad_unit_id', 'screen_name', 'ad_format']],
        'banner_clicked'                => ['tier' => 'T2', 'critical' => false, 'no_sampling' => false, 'props' => ['placement_id', 'ad_unit_id', 'screen_name']],
        'interstitial_shown'            => ['tier' => 'T1', 'critical' => false, 'no_sampling' => true,  'props' => ['placement_id', 'ad_unit_id', 'trigger_scene', 'session_event_count']],
        'interstitial_closed'           => ['tier' => 'T2', 'critical' => false, 'no_sampling' => false, 'props' => ['placement_id', 'ad_unit_id', 'close_time_ms', 'was_skipped']],
        'ad_revenue_paid'               => ['tier' => 'T1', 'critical' => true,  'no_sampling' => true,  'props' => ['placement_id', 'ad_unit_id', 'network', 'revenue_micros', 'currency', 'precision_type']],
        'ad_config_fetched'             => ['tier' => 'T3', 'critical' => false, 'no_sampling' => false, 'props' => ['config_version', 'source', 'duration_ms', 'success']],
        'ad_config_invalid'             => ['tier' => 'T3', 'critical' => false, 'no_sampling' => false, 'props' => ['placement_id', 'reason']],
        'ad_circuit_breaker_tripped'    => ['tier' => 'T3', 'critical' => false, 'no_sampling' => false, 'props' => ['placement_id', 'fail_count', 'cooldown_seconds']],

        // F 订阅转化
        'feature_gate_shown'            => ['tier' => 'T1', 'critical' => false, 'no_sampling' => true,  'props' => ['feature_name', 'current_plan']],
        'pro_page_viewed'               => ['tier' => 'T1', 'critical' => false, 'no_sampling' => true,  'props' => ['source', 'current_plan']],
        'purchase_initiated'            => ['tier' => 'T1', 'critical' => false, 'no_sampling' => true,  'props' => ['plan_id', 'price_local', 'currency']],
        'purchase_completed'            => ['tier' => 'T1', 'critical' => true,  'no_sampling' => true,  'props' => ['plan_id', 'price_local', 'order_id', 'is_first_purchase']],
        'purchase_cancelled'            => ['tier' => 'T2', 'critical' => false, 'no_sampling' => false, 'props' => ['plan_id', 'cancel_step']],
        'purchase_failed'               => ['tier' => 'T2', 'critical' => false, 'no_sampling' => false, 'props' => ['plan_id', 'error_code', 'payment_method']],
        'purchase_restored'             => ['tier' => 'T2', 'critical' => false, 'no_sampling' => false, 'props' => ['plan_id', 'source']],

        // G 异常与错误
        'feature_unavailable'           => ['tier' => 'T2', 'critical' => false, 'no_sampling' => false, 'props' => ['feature_name', 'missing_permission']],
        'ad_load_failed'                => ['tier' => 'T1', 'critical' => false, 'no_sampling' => true,  'props' => ['placement_id', 'ad_unit_id', 'error_code', 'network_type']],

        // H 通知触达
        'notification_received'         => ['tier' => 'T2', 'critical' => false, 'no_sampling' => false, 'props' => ['notification_type', 'is_foreground']],
        'notification_clicked'          => ['tier' => 'T2', 'critical' => false, 'no_sampling' => false, 'props' => ['notification_type', 'time_since_received_ms']],
        'reminder_setting_changed'      => ['tier' => 'T2', 'critical' => false, 'no_sampling' => false, 'props' => ['reminder_type', 'enabled']],
    ],

    // -------------------------------------------------- 公共属性（自动附加）
    'common_props' => [
        'app_version', 'os_version', 'locale', 'country', 'device_model', 'manufacturer',
        'network_type', 'session_id', 'session_index', 'is_pro', 'protect_enabled',
        'install_source', 'referrer', 'experiment_id', 'variant', 'sample_rate',
    ],

    // -------------------------------------------------- 枚举校验（10.2.5）
    // true = 枚举/类型违规时整条事件写入 rejected 表（推荐）
    // false = 仅剔除违规属性后保留事件主体，用于线上字典未及时同步时的临时降级
    'enum_strict' => env('ANALYTICS_ENUM_STRICT', true),

    // ------------------------------------------------------------ 上报限制
    'limits' => [
        'max_events_per_request' => 50,      // 超过直接 422
        'max_batch_size_remote'  => 50,      // 远程配置下发上限（PRD P1-14）
        'event_ttl_days'         => 7,       // 客户端离线事件有效期
        'clock_skew_hours'       => 24,      // 超过该偏移写入 rejected 表
        'max_properties'         => 20,
    ],

    // ---------------------------------------------------------------- 去重
    'dedupe' => [
        'driver'   => env('ANALYTICS_DEDUPE_DRIVER', 'redis'), // redis | database
        'redis_ttl'=> 86400,                 // batch_id 去重键 TTL（秒）
        'batch_ttl_days' => 30,              // analytics_batches 保留天数
    ],

    // -------------------------------------------- 远程配置默认值（下发客户端）
    'remote_defaults' => [
        'analytics_enabled'       => true,
        'batch_size'              => 20,
        'upload_interval_seconds' => 30,
        'max_retry_count'         => 5,
        'max_offline_events'      => 500,
        'sampling_rate'           => 1.0,
        'disabled_events'         => [],
    ],

    // ------------------------------------------------------ 监控告警（10.4.8）
    // 这些是埋点系统自身的生命线：异常意味着数据不可信，优先级高于业务指标。
    'monitor' => [
        'max_queue_backlog'              => env('ANALYTICS_MAX_QUEUE_BACKLOG', 1000),
        'max_failed_jobs'                => env('ANALYTICS_MAX_FAILED_JOBS', 10),
        'max_unknown_events'             => env('ANALYTICS_MAX_UNKNOWN_EVENTS', 0),
        'max_clock_skew'                 => env('ANALYTICS_MAX_CLOCK_SKEW', 50),
        'max_enum_violations'            => env('ANALYTICS_MAX_ENUM_VIOLATIONS', 100),
        'max_throttle_rate'              => env('ANALYTICS_MAX_THROTTLE_RATE', 1.0),  // %
        'max_suspect_rate'               => env('ANALYTICS_MAX_SUSPECT_RATE', 5.0),   // %
        'max_events_per_device_per_hour' => env('ANALYTICS_MAX_EVT_PER_DEVICE_H', 2000),
        // 注册后超过该天数仍无 T1 核心事件 → 判为可疑设备
        'suspect_grace_days'             => env('ANALYTICS_SUSPECT_GRACE_DAYS', 3),
        // 告警推送地址（企业微信/飞书机器人）；留空则只写日志
        'alert_webhook'                  => env('ANALYTICS_ALERT_WEBHOOK'),
    ],

    // -------------------------------------------------------------- 保留期
    'retention' => [
        'events_months'   => 13,             // 事件明细按月分区保留
        'rejected_days'   => 30,
        'partitions_ahead'=> 3,              // 预建未来月分区数
    ],
];
