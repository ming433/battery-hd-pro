package com.batteryhd.app.util

import android.content.Context
import android.content.SharedPreferences
import java.util.Calendar

/**
 * 轻量偏好存储。
 *
 * 只存本地状态与"广告频次计数"这类运营数据，**不存任何可识别个人信息**
 * （PRD 10.6.1）。订阅状态以服务端 RTDN 为真相源，本地仅做 UI 缓存。
 */
class Prefs(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    // ------------------------------------------------------------ 订阅

    /** Pro 状态本地缓存；真实判断以 Play 订阅（服务端 RTDN）为准 */
    var isPro: Boolean
        get() = prefs.getBoolean(KEY_IS_PRO, false)
        set(value) = prefs.edit().putBoolean(KEY_IS_PRO, value).apply()

    /**
     * 本设备是否曾发生过购买（用于 `purchase_completed.is_first_purchase`）。
     *
     * 注意这是**本地口径**：换机、重装会丢失，因此只能作为参考，
     * 首购率的真相源仍是服务端 RTDN。
     */
    var hasPurchasedBefore: Boolean
        get() = prefs.getBoolean(KEY_PURCHASED_BEFORE, false)
        set(value) = prefs.edit().putBoolean(KEY_PURCHASED_BEFORE, value).apply()

    // ------------------------------------------------------------ 功能设置

    var chargeLimitPercent: Int
        get() = prefs.getInt(KEY_CHARGE_LIMIT, DEFAULT_CHARGE_LIMIT)
        set(value) = prefs.edit().putInt(KEY_CHARGE_LIMIT, value.coerceIn(50, 100)).apply()

    var savingEnabled: Boolean
        get() = prefs.getBoolean(KEY_SAVING_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_SAVING_ENABLED, value).apply()

    var savingMode: String
        get() = prefs.getString(KEY_SAVING_MODE, "balanced") ?: "balanced"
        set(value) = prefs.edit().putString(KEY_SAVING_MODE, value).apply()

    // ------------------------------------------------------------ 提醒开关

    /**
     * 各类提醒开关。
     *
     * 与系统通知权限是两件事：开关决定"要不要发"，权限决定"发了能不能显示"。
     * 分开存是为了在埋点上能区分「用户主动关闭」和「系统层面收不到」，
     * 二者的运营动作完全不同（前者是产品问题，后者是引导开启权限）。
     */
    var reminderChargeLimit: Boolean
        get() = prefs.getBoolean(KEY_REMINDER_LIMIT, true)
        set(value) = prefs.edit().putBoolean(KEY_REMINDER_LIMIT, value).apply()

    var reminderTempAlert: Boolean
        get() = prefs.getBoolean(KEY_REMINDER_TEMP, true)
        set(value) = prefs.edit().putBoolean(KEY_REMINDER_TEMP, value).apply()

    var reminderWeeklyReport: Boolean
        get() = prefs.getBoolean(KEY_REMINDER_WEEKLY, true)
        set(value) = prefs.edit().putBoolean(KEY_REMINDER_WEEKLY, value).apply()

    var reminderCalibration: Boolean
        get() = prefs.getBoolean(KEY_REMINDER_CALIBRATION, false)
        set(value) = prefs.edit().putBoolean(KEY_REMINDER_CALIBRATION, value).apply()

    // ------------------------------------------------------------ 校准会话

    /**
     * 进行中的校准步骤（[com.batteryhd.analytics.Dictionary.CalibrationStep]）。
     * 空串表示没有进行中的校准。
     */
    var calibrationStep: String
        get() = prefs.getString(KEY_CALIBRATION_STEP, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CALIBRATION_STEP, value).apply()

    var calibrationStartedAt: Long
        get() = prefs.getLong(KEY_CALIBRATION_STARTED, 0L)
        set(value) = prefs.edit().putLong(KEY_CALIBRATION_STARTED, value).apply()

    fun clearCalibration() {
        prefs.edit()
            .remove(KEY_CALIBRATION_STEP)
            .remove(KEY_CALIBRATION_STARTED)
            .apply()
    }

    // -------------------------------------------------------------- 周报

    /**
     * 周报累计数据。
     *
     * 只存**聚合值**（会话数、总时长），不存逐条明细：
     * 明细在服务端，端上存明细既无必要也会放大本地存储与合规风险。
     *
     * 周标识用 `yyyyWW`（ISO 周），跨周自动归零。
     */
    var weeklyKey: String
        get() = prefs.getString(KEY_WEEKLY_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_WEEKLY_KEY, value).apply()

    var weeklySessionCount: Int
        get() = prefs.getInt(KEY_WEEKLY_SESSIONS, 0)
        set(value) = prefs.edit().putInt(KEY_WEEKLY_SESSIONS, value).apply()

    var weeklyTotalDurationMs: Long
        get() = prefs.getLong(KEY_WEEKLY_DURATION, 0L)
        set(value) = prefs.edit().putLong(KEY_WEEKLY_DURATION, value).apply()

    /**
     * 记录一次充电会话到本周汇总。周标识变化时自动清零。
     *
     * @return 当前周序号（ISO 周），用于周报展示与埋点的 week_number
     */
    fun recordWeeklySession(durationMs: Long): Int {
        val week = currentIsoWeek()

        if (weeklyKey != weekKey(week)) {
            weeklyKey = weekKey(week)
            weeklySessionCount = 0
            weeklyTotalDurationMs = 0L
        }

        weeklySessionCount += 1
        weeklyTotalDurationMs += durationMs

        return week
    }

    /** 本周平均充电时长（毫秒）；无数据返回 0 */
    fun weeklyAverageDurationMs(): Long =
        if (weeklySessionCount > 0) weeklyTotalDurationMs / weeklySessionCount else 0L

    /** ISO 周序号（1-53） */
    fun currentIsoWeek(): Int {
        val c = Calendar.getInstance().apply {
            firstDayOfWeek = Calendar.MONDAY
            minimalDaysInFirstWeek = 4
        }
        return c.get(Calendar.WEEK_OF_YEAR)
    }

    private fun weekKey(week: Int): String =
        "${Calendar.getInstance().get(Calendar.YEAR)}${week.toString().padStart(2, '0')}"

    /** 已设置休眠的应用分类集合（合规：只存分类，不存包名） */
    var hibernatedCategories: Set<String>
        get() = prefs.getStringSet(KEY_HIBERNATED, emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_HIBERNATED, value).apply()

    // ------------------------------------------------------------ 充电会话

    var chargeSessionStartedAt: Long
        get() = prefs.getLong(KEY_CHARGE_START, 0L)
        set(value) = prefs.edit().putLong(KEY_CHARGE_START, value).apply()

    var chargeSessionStartLevel: Int
        get() = prefs.getInt(KEY_CHARGE_START_LEVEL, -1)
        set(value) = prefs.edit().putInt(KEY_CHARGE_START_LEVEL, value).apply()

    /**
     * 当前充电会话内的**最高温度**（`charge_session_ended.max_temp_c`）。
     *
     * 结束事件要的是"整个会话的峰值"而非结束瞬间值，因此每次电量广播都取 max。
     * 用 Float 存储：电池温度是 `EXTRA_TEMPERATURE / 10`，整数会丢失 0.5℃ 精度。
     */
    var chargeSessionMaxTempC: Float
        get() = prefs.getFloat(KEY_CHARGE_MAX_TEMP, Float.MIN_VALUE)
        set(value) = prefs.edit().putFloat(KEY_CHARGE_MAX_TEMP, value).apply()

    /**
     * 上次上报温度告警的时间（毫秒）。
     *
     * 温度告警是"状态"而非"动作"：手机持续高温期间 UI 每次刷新都会命中阈值，
     * 不加冷却会把一次持续高温算成几十次告警。冷却窗口内只算一次。
     */
    var lastTempAlertAt: Long
        get() = prefs.getLong(KEY_LAST_TEMP_ALERT, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_TEMP_ALERT, value).apply()

    /**
     * 已上报过"达到充电限值"的会话起点时间戳。
     *
     * 防重：UI 每次刷新都会检查限值，若不加这个标记，
     * 一次充电会重复上报几十次 `charge_limit_triggered`，指标彻底失真。
     */
    var limitNotifiedSessionStart: Long
        get() = prefs.getLong(KEY_LIMIT_NOTIFIED_SESSION, -1L)
        set(value) = prefs.edit().putLong(KEY_LIMIT_NOTIFIED_SESSION, value).apply()

    /** 当前充电会话内充电上限触发次数（`charge_session_ended.limit_triggered_count`） */
    var chargeSessionLimitCount: Int
        get() = prefs.getInt(KEY_CHARGE_LIMIT_COUNT, 0)
        set(value) = prefs.edit().putInt(KEY_CHARGE_LIMIT_COUNT, value).apply()

    fun incrementChargeSessionLimitCount() {
        chargeSessionLimitCount = chargeSessionLimitCount + 1
    }

    fun clearChargeSession() {
        prefs.edit()
            .remove(KEY_CHARGE_START)
            .remove(KEY_CHARGE_START_LEVEL)
            .remove(KEY_CHARGE_MAX_TEMP)
            .remove(KEY_CHARGE_LIMIT_COUNT)
            .apply()
    }

    // ------------------------------------------------------------ 广告频次

    /**
     * 插屏广告计数。
     *
     * 按自然日存储（key 带 yyyymmdd），跨天自动归零；
     * 会话级计数由 AdsManager 在内存中维护（会话结束即清零）。
     *
     * 频次上限本身来自远程配置的 frequency_cap，这里只负责计数。
     */
    fun interstitialCountToday(): Int = prefs.getInt(dayKey(), 0)

    fun incrementInterstitialToday() {
        val key = dayKey()
        prefs.edit()
            .putInt(key, prefs.getInt(key, 0) + 1)
            // 顺手清掉昨天的键，避免累积
            .apply()
        prefs.edit().remove(yesterdayKey()).apply()
    }

    private fun dayKey(): String {
        val c = Calendar.getInstance()
        return "ic_${c.get(Calendar.YEAR)}${c.get(Calendar.MONTH) + 1}${c.get(Calendar.DAY_OF_MONTH)}"
    }

    private fun yesterdayKey(): String {
        val c = Calendar.getInstance()
        c.add(Calendar.DAY_OF_MONTH, -1)
        return "ic_${c.get(Calendar.YEAR)}${c.get(Calendar.MONTH) + 1}${c.get(Calendar.DAY_OF_MONTH)}"
    }

    // ------------------------------------------------------------ 首启

    /** 首次启动时间，用于"新用户免广告"宽限期判断（远程配置 first_launch_grace_minutes） */
    var firstLaunchAt: Long
        get() = prefs.getLong(KEY_FIRST_LAUNCH, 0L)
        set(value) = prefs.edit().putLong(KEY_FIRST_LAUNCH, value).apply()

    companion object {
        private const val PREF_NAME = "bhd_app"
        private const val KEY_IS_PRO = "is_pro"
        private const val KEY_PURCHASED_BEFORE = "purchased_before"
        private const val KEY_CHARGE_LIMIT = "charge_limit"
        private const val KEY_SAVING_ENABLED = "saving_enabled"
        private const val KEY_SAVING_MODE = "saving_mode"
        private const val KEY_HIBERNATED = "hibernated_categories"
        private const val KEY_CHARGE_START = "charge_session_start"
        private const val KEY_CHARGE_START_LEVEL = "charge_session_start_level"
        private const val KEY_CHARGE_MAX_TEMP = "charge_session_max_temp"
        private const val KEY_CHARGE_LIMIT_COUNT = "charge_session_limit_count"
        private const val KEY_LIMIT_NOTIFIED_SESSION = "limit_notified_session"
        private const val KEY_LAST_TEMP_ALERT = "last_temp_alert_at"
        private const val KEY_FIRST_LAUNCH = "first_launch_at"
        private const val KEY_REMINDER_LIMIT = "reminder_charge_limit"
        private const val KEY_REMINDER_TEMP = "reminder_temp_alert"
        private const val KEY_REMINDER_WEEKLY = "reminder_weekly_report"
        private const val KEY_REMINDER_CALIBRATION = "reminder_calibration"
        private const val KEY_CALIBRATION_STEP = "calibration_step"
        private const val KEY_CALIBRATION_STARTED = "calibration_started_at"
        private const val KEY_LAST_WEEKLY_WEEK = "last_weekly_report_week"
        private const val KEY_WEEKLY_KEY = "weekly_key"
        private const val KEY_WEEKLY_SESSIONS = "weekly_sessions"
        private const val KEY_WEEKLY_DURATION = "weekly_duration_ms"

        const val DEFAULT_CHARGE_LIMIT = 80
    }
}
