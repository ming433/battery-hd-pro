package com.batteryhd.app.battery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import com.batteryhd.analytics.Analytics
import com.batteryhd.analytics.Dictionary

/**
 * 充电会话追踪（PRD 10.2 D 组：**平均充电时长**的唯一数据来源）。
 *
 * ── 为什么必须动态注册 ──────────────────────────────────────────
 * Android 8（API 26）起，manifest 静态注册的隐式广播受到限制，而
 * `ACTION_POWER_CONNECTED` / `ACTION_POWER_DISCONNECTED` **不在豁免列表**里。
 * 若在 manifest 里注册，系统会静默丢弃——表现为「充电时长数据大面积缺失」。
 * 因此这里在进程内动态注册，覆盖 App 在前台/后台存活的场景。
 *
 * ── 进程被杀怎么办 ──────────────────────────────────────────────
 * 动态注册的接收器随进程消亡。若用户在充电期间进程被杀，结束事件必然丢失。
 * 应对：
 *   1. 会话开始时把 started_at / start_level 落 [Prefs]；
 *   2. 每次 App 启动（[reconcile]）读取当前充电状态做对账：
 *      - 有未结束记录 + 当前未在充电 → 补报 charge_session_ended（时长按记录起点算）
 *      - 无记录 + 当前正在充电 → 补报 charge_session_started（标记 recovered=true）
 *   3. [BootReceiver] 在开机/包替换后触发对账，缩短丢失窗口。
 *
 * 即便如此仍有覆盖盲区（进程被杀后数日才开机），
 * 因此看板需标注该指标的覆盖率，不能当作 100% 全量。
 */
class ChargeSessionTracker(
    private val context: Context,
    private val prefs: com.batteryhd.app.util.Prefs,
    private val repo: BatteryRepository
) {

    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_POWER_CONNECTED -> onChargeStarted(false)
                Intent.ACTION_POWER_DISCONNECTED -> onChargeEnded(false)
                Intent.ACTION_BATTERY_CHANGED -> onBatteryChanged(intent)
            }
        }
    }

    /** 在 Application 中调用一次；进程存活期间持续监听 */
    fun register() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        runCatching {
            context.applicationContext.registerReceiver(receiver, filter)
            registered = true
        }
    }

    fun unregister() {
        if (!registered) return
        runCatching {
            context.applicationContext.unregisterReceiver(receiver)
            registered = false
        }
    }

    /**
     * 启动对账：修正进程被杀导致的丢失。
     * 必须在 [register] 之前调用，否则刚补报完立刻又收到广播会重复。
     */
    fun reconcile() {
        val snap = repo.snapshot()
        val hasOpenSession = prefs.chargeSessionStartedAt > 0

        when {
            hasOpenSession && !snap.isCharging -> {
                // 上次充电开始后进程死了，结束时点已不可知 → 按最后已知时间补报
                reportEnd(prefs.chargeSessionStartedAt, snap, recovered = true)
                prefs.clearChargeSession()
            }
            !hasOpenSession && snap.isCharging -> {
                prefs.chargeSessionStartedAt = System.currentTimeMillis()
                prefs.chargeSessionStartLevel = snap.levelPercent
                reportStart(snap, recovered = true)
            }
        }
    }

    private fun onBatteryChanged(intent: Intent) {
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val hasOpenSession = prefs.chargeSessionStartedAt > 0

        // 会话内峰值温度：结束事件要的是 max_temp_c，只能边充电边累积
        if (hasOpenSession) {
            val temp = repo.snapshot().temperatureC
            if (temp > prefs.chargeSessionMaxTempC) prefs.chargeSessionMaxTempC = temp
        }

        if (isCharging && !hasOpenSession) {
            onChargeStarted(false)
        } else if (!isCharging && hasOpenSession) {
            onChargeEnded(false)
        }
    }

    private fun onChargeStarted(recovered: Boolean) {
        val snap = repo.snapshot()
        prefs.chargeSessionStartedAt = System.currentTimeMillis()
        prefs.chargeSessionStartLevel = snap.levelPercent
        reportStart(snap, recovered)
    }

    private fun onChargeEnded(recovered: Boolean) {
        val snap = repo.snapshot()
        val startedAt = prefs.chargeSessionStartedAt
        if (startedAt <= 0) return
        reportEnd(startedAt, snap, recovered)
        prefs.clearChargeSession()
    }

    /**
     * @param recovered true 表示本次是启动对账补报（进程被杀后恢复）。
     *   仅用于本地日志：**服务端没有这个字段**，带上会被静默剥离。
     */
    private fun reportStart(snap: BatteryRepository.Snapshot, recovered: Boolean) {
        if (recovered) Log.d(TAG, "charge_session_started (recovered)")
        Analytics.track(
            Dictionary.Event.CHARGE_SESSION_STARTED,
            mapOf(
                "start_percent" to snap.levelPercent,
                // 充电保护 = 用户设置了充电上限（默认 80%），用于分析"开保护的人充得更久吗"
                "is_protection_on" to (prefs.chargeLimitPercent > 0),
                "charger_type" to snap.chargerType
            )
        )
    }

    private fun reportEnd(
        startedAt: Long,
        snap: BatteryRepository.Snapshot,
        recovered: Boolean
    ) {
        val durationMs = System.currentTimeMillis() - startedAt
        // 短于 10 秒的插拔抖动不算一次充电会话
        if (durationMs < MIN_DURATION_MS) return
        if (recovered) Log.d(TAG, "charge_session_ended (recovered)")

        // 会话峰值温度：对账补报时只有结束瞬间值，此时 max 就是它本身
        val maxTemp = maxOf(prefs.chargeSessionMaxTempC, snap.temperatureC)

        Analytics.track(
            Dictionary.Event.CHARGE_SESSION_ENDED,
            mapOf(
                // 平均充电时长指标的唯一数据来源（PRD 10.2 D 组）
                "duration_ms" to durationMs,
                "end_percent" to snap.levelPercent,
                "max_temp_c" to maxTemp,
                "limit_triggered_count" to prefs.chargeSessionLimitCount
            )
        )

        // 端上只累聚合值供周报展示，明细以服务端为准
        prefs.recordWeeklySession(durationMs)
    }

    companion object {
        private const val TAG = "ChargeSession"
        private const val MIN_DURATION_MS = 10_000L

        /** 当前是否在充电（供 UI 与对账使用） */
        fun isPluggedNow(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                bm?.isCharging == true
            } else {
                val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
                status == BatteryManager.BATTERY_STATUS_CHARGING
            }
        }
    }
}
