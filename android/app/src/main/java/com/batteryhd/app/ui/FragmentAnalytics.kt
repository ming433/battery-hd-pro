package com.batteryhd.app.ui

import androidx.fragment.app.Fragment
import com.batteryhd.analytics.Analytics
import com.batteryhd.analytics.Dictionary
import com.batteryhd.app.BatteryHdApp
import com.batteryhd.app.R
import com.batteryhd.app.battery.BatteryRepository
import com.batteryhd.app.notification.NotificationHelper

/**
 * Fragment 侧的状态型埋点上报。
 *
 * 之所以集中在这里：**状态型事件必须去重**。
 * UI 每次刷新都会重新判断条件，而"温度过高""达到充电限值"是持续状态而非一次性动作，
 * 直接上报会把一次持续高温算成几十次告警，指标完全失真。
 */
private val Fragment.app: BatteryHdApp
    get() = requireContext().applicationContext as BatteryHdApp

/**
 * 上报 `temp_alert_triggered`，**同一段持续高温只上报一次**（冷却 10 分钟）。
 */
fun Fragment.reportTempAlert(snap: BatteryRepository.Snapshot, thresholdC: Float) {
    val now = System.currentTimeMillis()
    if (now - app.prefs.lastTempAlertAt < TEMP_ALERT_COOLDOWN_MS) return
    app.prefs.lastTempAlertAt = now

    Analytics.track(
        Dictionary.Event.TEMP_ALERT_TRIGGERED,
        mapOf(
            "temperature_c" to snap.temperatureC,
            "threshold_c" to thresholdC,
            "is_charging" to snap.isCharging
        )
    )
}

/**
 * 上报 `charge_limit_triggered`，**一次充电会话只上报一次**。
 *
 * 首页与充电保护页都会检查限值，去重键是会话起点时间戳，
 * 会话结束（[com.batteryhd.app.util.Prefs.clearChargeSession]）后自动失效。
 */
fun Fragment.reportLimitTriggered(limitPercent: Int, snap: BatteryRepository.Snapshot) {
    val prefs = app.prefs
    val sessionStart = prefs.chargeSessionStartedAt
    if (prefs.limitNotifiedSessionStart == sessionStart) return

    prefs.limitNotifiedSessionStart = sessionStart
    prefs.incrementChargeSessionLimitCount()

    Analytics.track(
        Dictionary.Event.CHARGE_LIMIT_TRIGGERED,
        mapOf(
            "limit_percent" to limitPercent,
            "current_percent" to snap.levelPercent,
            "temperature_c" to snap.temperatureC
        )
    )

    if (app.prefs.reminderChargeLimit) {
        NotificationHelper.notify(
            context = requireContext(),
            type = Dictionary.NotificationType.CHARGE_LIMIT_REACHED,
            title = getString(R.string.notification_charge_limit_title),
            text = getString(R.string.notification_charge_limit_text, snap.levelPercent),
            isForeground = app.isForeground
        )
    }
}

private const val TEMP_ALERT_COOLDOWN_MS = 10 * 60 * 1000L
