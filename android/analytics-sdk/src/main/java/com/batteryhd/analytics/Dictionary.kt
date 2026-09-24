package com.batteryhd.analytics

/**
 * 事件与枚举字典（PRD 10.2 / 10.2.5）。
 *
 * **本文件是服务端 `backend/config/enums.php` 的镜像，两侧必须同步修改。**
 * 服务端对枚举值做白名单校验，非法值会被写入 rejected 表——客户端在本地先校验一次，
 * 可以尽早暴露问题，避免"埋点看起来发了但看板上没有"。
 *
 * 变更流程见 PRD 附录 A：新增枚举值需客户端与服务端同步发版。
 * 服务端可运行 `php artisan analytics:lint-dictionary --strict` 自检。
 */
object Dictionary {

    // ------------------------------------------------------------ 页面枚举
    object Screen {
        const val HOME_DASHBOARD = "home_dashboard"
        const val BATTERY_MONITOR = "battery_monitor"
        const val CHARGE_PROTECTION = "charge_protection"
        const val POWER_ANALYSIS = "power_analysis"
        const val SMART_SAVING = "smart_saving"
        const val SETTINGS = "settings"
        const val PRO_UPGRADE = "pro_upgrade"
        const val HELP_WEBVIEW = "help_webview"
    }

    // -------------------------------------------------------- 广告触发场景
    object TriggerScene {
        const val APP_COLD_START = "app_cold_start"
        const val SCREEN_SWITCH = "screen_switch"
        const val CHARGE_COMPLETE = "charge_complete"
        const val CALIBRATION_COMPLETE = "calibration_complete"
        const val REPORT_GENERATED = "report_generated"
        const val FEATURE_GATE_EXIT = "feature_gate_exit"
        const val APP_EXIT = "app_exit"

        /** 仅 debug 包可用；服务端在 release 环境会拒收，防止测试流量污染 eCPM */
        const val MANUAL_DEBUG = "manual_debug"
    }

    // -------------------------------------------------------------- 权限
    object Permission {
        const val NOTIFICATION = "notification"
        const val USAGE_STATS = "usage_stats"
        const val BATTERY_OPTIMIZATION = "battery_optimization"
        const val SYSTEM_ALERT_WINDOW = "system_alert_window"
        const val EXACT_ALARM = "exact_alarm"
        const val STORAGE = "storage"

        /** 权限申请场景上下文 */
        object Context {
            const val ONBOARDING = "onboarding"
            const val FEATURE_USE = "feature_use"
            const val SETTINGS = "settings"
            const val SYSTEM_DIALOG = "system_dialog"
        }
    }

    // ------------------------------------------------------------ 订阅 SKU
    /** 必须与 Google Play Console 的 productId 完全一致（大小写敏感） */
    object PlanId {
        const val PRO_MONTHLY = "pro_monthly"
        const val PRO_YEARLY = "pro_yearly"
        const val PRO_LIFETIME = "pro_lifetime"
        const val TRIAL_MONTHLY = "trial_monthly"
        const val PROMO_YEARLY = "promo_yearly"
    }

    /** 用户当前订阅状态（注意与 PlanId 区分：一个是商品，一个是状态） */
    object CurrentPlan {
        const val FREE = "free"
        const val TRIAL = "trial"
        const val PRO = "pro"
    }

    // -------------------------------------------------------------- 通知
    object NotificationType {
        const val CHARGE_LIMIT_REACHED = "charge_limit_reached"
        const val TEMP_ALERT = "temp_alert"
        const val CHARGE_COMPLETE = "charge_complete"
        const val WEEKLY_REPORT = "weekly_report"
        const val CALIBRATION_REMINDER = "calibration_reminder"
        const val BATTERY_HEALTH_DROP = "battery_health_drop"
        const val RE_ENGAGEMENT = "re_engagement"
        const val PROMO = "promo"
    }

    /** 提醒开关类型（reminder_setting_changed.reminder_type） */
    object ReminderType {
        const val CHARGE_LIMIT = "charge_limit"
        const val TEMP_ALERT = "temp_alert"
        const val WEEKLY_REPORT = "weekly_report"
        const val CALIBRATION = "calibration"
    }

    /**
     * 放弃校准的原因（calibration_abandoned.reason）。
     *
     * 注意与 [AdConfigInvalidReason] 区分：两者属性名都是 `reason`，
     * 但取值域完全不同，服务端按事件名分别校验（enums.event_overrides）。
     */
    object CalibrationAbandonReason {
        const val USER_CANCEL = "user_cancel"
        const val APP_KILLED = "app_killed"
        const val TIMEOUT = "timeout"
        const val CHARGE_INTERRUPTED = "charge_interrupted"
    }

    // ------------------------------------------------------------ 错误码
    /**
     * 广告错误码：AdMob LoadAdError.getCode() **原样透传**，不做映射。
     * 1(配置错误) 与 8(App ID 缺失) 会触发服务端告警，通常意味着后台 ad_unit_id 配错。
     */
    object AdErrorCode {
        const val INTERNAL_ERROR = 0
        const val INVALID_REQUEST = 1
        const val NETWORK_ERROR = 2
        const val NO_FILL = 3
        const val APP_ID_MISSING = 8
        const val REQUEST_ID_MISMATCH = 9
    }

    /**
     * 购买错误码：Google Play Billing ResponseCode。
     * 注意：计算"购买失败率"时必须剔除 USER_CANCELED / ITEM_ALREADY_OWNED / SERVICE_DISCONNECTED，
     * 否则会把"用户改主意"和"恢复购买"算成系统故障。
     */
    object PurchaseErrorCode {
        const val SERVICE_DISCONNECTED = -1
        const val USER_CANCELED = 1
        const val SERVICE_UNAVAILABLE = 2
        const val BILLING_UNAVAILABLE = 3
        const val ITEM_UNAVAILABLE = 4
        const val DEVELOPER_ERROR = 5
        const val ERROR = 6
        const val ITEM_ALREADY_OWNED = 7
        const val ITEM_NOT_OWNED = 8
    }

    // ---------------------------------------------------------- 应用分类
    /**
     * 替代 package_name 上报（PRD 10.7.1 数据最小化）。
     * 包名一律不出端，本地映射为分类后再上报；分类词典由服务端下发，此处为内置兜底。
     */
    object AppCategory {
        const val SOCIAL = "social"
        const val VIDEO = "video"
        const val GAME = "game"
        const val SHOPPING = "shopping"
        const val BROWSER = "browser"
        const val IM = "im"
        const val SYSTEM = "system"

        /** 未命中任何规则的兜底值；服务端监控 other 占比，超过 30% 会告警 */
        const val OTHER = "other"
    }

    // -------------------------------------------------------- 其他枚举
    object LaunchType {
        const val COLD = "cold"
        const val WARM = "warm"
        const val HOT = "hot"
    }

    object Method {
        const val MANUAL = "manual"
        const val GUIDED = "guided"
    }

    object TimeRange {
        const val DAY = "day"
        const val WEEK = "week"
    }

    object Source {
        const val BANNER = "banner"
        const val FEATURE_GATE = "feature_gate"
        const val SETTINGS = "settings"
        const val NOTIFICATION = "notification"
        const val ONBOARDING = "onboarding"
    }

    object AdFormat {
        const val BANNER = "banner"
        const val INTERSTITIAL = "interstitial"
        const val REWARDED = "rewarded"
        const val NATIVE = "native"
    }

    object PrecisionType {
        const val UNKNOWN = "unknown"
        const val ESTIMATED = "estimated"
        const val PUBLISHER_PROVIDED = "publisher_provided"
        const val PRECISE = "precise"
    }

    object NetworkType {
        const val WIFI = "wifi"
        const val CELLULAR = "cellular"
        const val OFFLINE = "offline"
        const val UNKNOWN = "unknown"
    }

    /** 广告平台（服务端 enums.php: network）。当前只有 AdMob，保留枚举位便于接聚合平台 */
    object Network {
        const val ADMOB = "admob"
        const val ADMOB_MEDIATION = "admob_mediation"
        const val UNKNOWN = "unknown"
    }

    object ChargerType {
        const val AC = "ac"
        const val USB = "usb"
        const val WIRELESS = "wireless"
        const val UNKNOWN = "unknown"
    }

    object StepName {
        const val WELCOME = "welcome"
        const val BATTERY_PERMISSION = "battery_permission"
        const val USAGE_PERMISSION = "usage_permission"
        const val NOTIFICATION_PERMISSION = "notification_permission"
        const val FEATURE_INTRO = "feature_intro"
        const val DONE = "done"
    }

    object ReportType {
        const val WEEKLY = "weekly"
        const val MONTHLY = "monthly"
        const val CUSTOM = "custom"
    }

    object FeatureName {
        const val ADVANCED_HEALTH_REPORT = "advanced_health_report"
        const val UNLIMITED_CALIBRATION = "unlimited_calibration"
        const val AD_FREE = "ad_free"
        const val CUSTOM_CHARGE_LIMIT = "custom_charge_limit"
        const val HIBERNATION_AUTOMATION = "hibernation_automation"
        const val DETAILED_POWER_ANALYSIS = "detailed_power_analysis"
    }

    object Currency {
        const val IDR = "IDR"
        const val VND = "VND"
        const val THB = "THB"
        const val PHP = "PHP"
        const val MYR = "MYR"
        const val SGD = "SGD"
        const val USD = "USD"
    }

    object CalibrationStep {
        const val DISCHARGE = "discharge"
        const val FULL_CHARGE = "full_charge"
        const val VERIFY = "verify"
    }

    object SavingMode {
        const val BALANCED = "balanced"
        const val AGGRESSIVE = "aggressive"
        const val CUSTOM = "custom"
    }

    object CancelStep {
        const val PLAN_SELECT = "plan_select"
        const val CONFIRM = "confirm"
        const val PAYMENT_SHEET = "payment_sheet"
    }

    // ------------------------------------------------------------ 事件名
    object Event {
        // A 生命周期
        const val APP_FIRST_OPEN = "app_first_open"
        const val APP_LAUNCHED = "app_launched"
        const val APP_FOREGROUNDED = "app_foregrounded"
        const val APP_BACKGROUNDED = "app_backgrounded"

        // B 页面浏览
        const val SCREEN_VIEWED = "screen_viewed"
        const val SCREEN_EXITED = "screen_exited"

        // C 引导与权限
        const val PERMISSION_REQUESTED = "permission_requested"
        const val PERMISSION_DENIED = "permission_denied"
        const val ONBOARDING_STEP_COMPLETED = "onboarding_step_completed"
        const val NOTIFICATION_PERMISSION_RESULT = "notification_permission_result"

        // D 核心功能
        const val BATTERY_HEALTH_VIEWED = "battery_health_viewed"
        const val SCENE_ESTIMATE_EXPANDED = "scene_estimate_expanded"
        const val CHARGE_LIMIT_TRIGGERED = "charge_limit_triggered"
        const val TEMP_ALERT_TRIGGERED = "temp_alert_triggered"
        const val CHARGE_SESSION_STARTED = "charge_session_started"
        const val CHARGE_SESSION_ENDED = "charge_session_ended"
        const val CALIBRATION_STARTED = "calibration_started"
        const val CALIBRATION_COMPLETED = "calibration_completed"
        const val CALIBRATION_ABANDONED = "calibration_abandoned"
        const val POWER_RANKING_VIEWED = "power_ranking_viewed"
        const val APP_HIBERNATION_SET = "app_hibernation_set"
        const val SMART_SAVING_ENABLED = "smart_saving_enabled"
        const val WEEKLY_REPORT_VIEWED = "weekly_report_viewed"

        // E 广告
        const val AD_REQUESTED = "ad_requested"
        const val BANNER_SHOWN = "banner_shown"
        const val BANNER_CLICKED = "banner_clicked"
        const val INTERSTITIAL_SHOWN = "interstitial_shown"
        const val INTERSTITIAL_CLOSED = "interstitial_closed"
        const val AD_REVENUE_PAID = "ad_revenue_paid"
        const val AD_CONFIG_FETCHED = "ad_config_fetched"
        const val AD_CONFIG_INVALID = "ad_config_invalid"
        const val AD_CIRCUIT_BREAKER_TRIPPED = "ad_circuit_breaker_tripped"

        // F 订阅转化
        const val FEATURE_GATE_SHOWN = "feature_gate_shown"
        const val PRO_PAGE_VIEWED = "pro_page_viewed"
        const val PURCHASE_INITIATED = "purchase_initiated"
        const val PURCHASE_COMPLETED = "purchase_completed"
        const val PURCHASE_CANCELLED = "purchase_cancelled"
        const val PURCHASE_FAILED = "purchase_failed"
        const val PURCHASE_RESTORED = "purchase_restored"

        // G 异常
        const val FEATURE_UNAVAILABLE = "feature_unavailable"
        const val AD_LOAD_FAILED = "ad_load_failed"

        // H 通知
        const val NOTIFICATION_RECEIVED = "notification_received"
        const val NOTIFICATION_CLICKED = "notification_clicked"
        const val REMINDER_SETTING_CHANGED = "reminder_setting_changed"

        // I AI Coach
        const val AI_COACH_REFRESHED = "ai_coach_refreshed"
        const val SMART_LIMIT_APPLIED = "smart_limit_applied"
    }

    /**
     * 队列满时不参与 FIFO 丢弃的关键事件（PRD 10.3.4）。
     * 与服务端 config/analytics.php 的 critical 标记保持一致。
     */
    val CRITICAL_EVENTS = setOf(
        Event.CALIBRATION_COMPLETED,
        Event.AD_REVENUE_PAID,
        Event.PURCHASE_COMPLETED
    )

    /**
     * 免采样事件：即使采样率 < 1.0 也必定上报（多为指标分母，采样会破坏比率）。
     */
    val NO_SAMPLING_EVENTS = setOf(
        Event.APP_FIRST_OPEN, Event.APP_LAUNCHED, Event.SCREEN_VIEWED,
        Event.PERMISSION_REQUESTED, Event.PERMISSION_DENIED,
        Event.BATTERY_HEALTH_VIEWED, Event.CHARGE_LIMIT_TRIGGERED,
        Event.TEMP_ALERT_TRIGGERED, Event.CHARGE_SESSION_ENDED,
        Event.CALIBRATION_STARTED, Event.CALIBRATION_COMPLETED,
        Event.AD_REQUESTED, Event.BANNER_SHOWN, Event.INTERSTITIAL_SHOWN,
        Event.AD_REVENUE_PAID, Event.AD_LOAD_FAILED,
        Event.FEATURE_GATE_SHOWN, Event.PRO_PAGE_VIEWED,
        Event.PURCHASE_INITIATED, Event.PURCHASE_COMPLETED
    )
}
