package com.batteryhd.app.battery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机 / 包替换后补做一次充电会话对账。
 *
 * 作用见 [ChargeSessionTracker] 的说明：动态注册的接收器随进程消亡，
 * 开机时补一次对账，可把「充电期间进程被杀再开机」这段盲区的会话捞回来。
 *
 * 注意：这里不直接埋点上报（此时 SDK 可能尚未初始化），
 * 而是启动 App 进程后由 Application 统一处理，避免上报时序问题。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        // 仅记录待对账标记；真正的埋点由 BatteryHdApp 启动后 reconcile() 完成
        val prefs = context.getSharedPreferences("bhd_app", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("need_charge_reconcile", true).apply()
    }
}
