package com.batteryhd.app.battery

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import kotlin.math.roundToInt

/**
 * 电池信息采集。
 *
 * 数据来源全部是系统 [BatteryManager]（粘性广播 ACTION_BATTERY_CHANGED），
 * 无需任何权限，也不采集 IMEI / 位置等敏感信息（PRD 10.6.1）。
 */
class BatteryRepository(private val context: Context) {

    private val batteryManager: BatteryManager? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        } else {
            null
        }

    data class Snapshot(
        val levelPercent: Int,
        val temperatureC: Float,
        val voltageV: Float,
        val isCharging: Boolean,
        val chargerType: String,
        val technology: String,
        val capacityMah: Int,
        val healthScore: Int
    )

    /** 读取当前电池快照 */
    fun snapshot(): Snapshot {
        // ACTION_BATTERY_CHANGED 是粘性广播，registerReceiver(null, ...) 可直接拿到最后一次值
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val levelPercent = if (level >= 0 && scale > 0) (level * 100f / scale).roundToInt() else 0

        // EXTRA_TEMPERATURE 单位是 0.1°C
        val tempRaw = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        val temperatureC = tempRaw / 10f

        val voltageMv = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        val voltageV = voltageMv / 1000f

        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL

        val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val chargerType = when (plugged) {
            BatteryManager.BATTERY_PLUGGED_AC -> "ac"
            BatteryManager.BATTERY_PLUGGED_USB -> "usb"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
            else -> "unknown"
        }

        val technology = intent?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) ?: "unknown"

        val capacityMah = estimateCapacityMah()
        val healthScore = estimateHealthScore(levelPercent, temperatureC)

        return Snapshot(
            levelPercent = levelPercent,
            temperatureC = temperatureC,
            voltageV = voltageV,
            isCharging = isCharging,
            chargerType = chargerType,
            technology = technology,
            capacityMah = capacityMah,
            healthScore = healthScore
        )
    }

    /**
     * 估算电池容量（mAh）。
     *
     * Android 没有公开的设计容量 API。这里用 BATTERY_PROPERTY_CHARGE_COUNTER（剩余电荷 μAh）
     * 除以当前电量百分比反推满充容量，属于行业通行做法。
     * 返回值仅用于展示与埋点，不追求精度。
     */
    private fun estimateCapacityMah(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return 0
        val counterUah = runCatching {
            batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        }.getOrNull() ?: return 0
        if (counterUah <= 0) return 0

        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level <= 0 || scale <= 0) return 0

        val percent = level.toFloat() / scale
        if (percent <= 0.05f) return 0   // 电量过低时反推误差过大，不展示

        return (counterUah / 1000f / percent).roundToInt()
    }

    /**
     * 健康度评分（0~100）。
     *
     * 简化模型：基础分 100，按高温、低温、深度放电扣分。
     * 这是产品展示用的启发式评分，不是物理测量；埋点上报的
     * health_score 用于分群对比，跨版本可比性由同一算法保证。
     *
     * 注意：**刻意不纳入 capacityMah**。系统无法给出"设计容量"，
     * 用实测容量自行推算衰减率在机型间不可比（同一台机不同 ROM 上报值差异很大），
     * 纳入只会让分群对比失真。需要真实衰减时应走校准流程的 measured_capacity_mah。
     */
    private fun estimateHealthScore(levelPercent: Int, temperatureC: Float): Int {
        var score = 100

        // 高温是电池寿命的头号杀手
        score -= when {
            temperatureC >= 45f -> 25
            temperatureC >= 40f -> 15
            temperatureC >= 35f -> 8
            else -> 0
        }
        // 低温同样损伤
        if (temperatureC <= 5f) score -= 10

        // 长期处于极低电量
        if (levelPercent <= 10) score -= 8

        return score.coerceIn(0, 100)
    }

    /**
     * 场景续航估算（小时）。
     *
     * 基于当前电量与典型功耗的经验值，仅作参考展示。
     * 展开时上报 [com.batteryhd.analytics.Dictionary.Event.SCENE_ESTIMATE_EXPANDED]。
     */
    fun sceneEstimates(levelPercent: Int): List<Pair<String, Float>> {
        // 每小时耗电百分比（经验值）
        val drainPerHour = mapOf(
            "video" to 12f,
            "game" to 20f,
            "browsing" to 8f,
            "music" to 4f,
            "standby" to 1f
        )
        return drainPerHour.map { (scene, drain) -> scene to (levelPercent / drain) }
    }
}
