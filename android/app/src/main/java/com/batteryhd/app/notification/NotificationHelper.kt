package com.batteryhd.app.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.batteryhd.analytics.Analytics
import com.batteryhd.analytics.Dictionary
import com.batteryhd.app.R
import com.batteryhd.app.ui.MainActivity

/**
 * 通知渠道与埋点（PRD 10.2.4 H：通知触达链路）。
 *
 * 埋点要点：
 * - `notification_received` 在**本端发出通知时**上报。本项目没有自建推送服务端，
 *   通知由端上本地触发，因此"送达"即等于"发出"；若将来接入 FCM，
 *   应改为在 FCM onMessageReceived 上报，避免漏统计服务端推送的到达。
 * - `notification_clicked` 的 `time_since_received_ms` 依赖把发出时间写入
 *   Intent extra 再带回，不能在上报时"估"一个值。
 */
object NotificationHelper {

    const val CHANNEL_ALERTS = "battery_alerts"
    const val CHANNEL_REPORTS = "battery_reports"

    /** Intent extra：通知类型，用于点击时回传 */
    const val EXTRA_TYPE = "extra_notification_type"

    /** Intent extra：发出时间戳（毫秒），用于计算点击延迟 */
    const val EXTRA_SENT_AT = "extra_notification_sent_at"

    private var sequence = 0

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        val alerts = NotificationChannel(
            CHANNEL_ALERTS,
            context.getString(R.string.notification_channel_alerts),
            NotificationManager.IMPORTANCE_HIGH
        ).apply { description = context.getString(R.string.notification_channel_alerts_desc) }

        val reports = NotificationChannel(
            CHANNEL_REPORTS,
            context.getString(R.string.notification_channel_reports),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = context.getString(R.string.notification_channel_reports_desc) }

        manager.createNotificationChannel(alerts)
        manager.createNotificationChannel(reports)
    }

    /**
     * Android 13（API 33）起通知需要运行时权限。
     * 低于 33 由系统授予，直接返回 true。
     */
    fun hasPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true

        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 发出通知并上报 `notification_received`。
     *
     * @param type [Dictionary.NotificationType] 中的取值
     * @param isForeground 当前 App 是否在前台（由调用方传入，
     *   因为本 Helper 不持有 Activity 生命周期）
     */
    fun notify(
        context: Context,
        type: String,
        title: String,
        text: String,
        isForeground: Boolean = false,
        channel: String = CHANNEL_ALERTS
    ): Boolean {
        // 无权限时发送会被系统静默丢弃，此时上报"已送达"会污染触达率
        if (!hasPermission(context)) return false

        ensureChannels(context)

        val sentAt = System.currentTimeMillis()
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_TYPE, type)
            putExtra(EXTRA_SENT_AT, sentAt)
        }

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)

        val pending = PendingIntent.getActivity(context, type.hashCode(), intent, flags)

        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_battery)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(sequence++, notification)
        }.onFailure {
            return false
        }

        Analytics.track(
            Dictionary.Event.NOTIFICATION_RECEIVED,
            mapOf(
                "notification_type" to type,
                "is_foreground" to isForeground
            )
        )

        return true
    }

    /**
     * 通知被点击。由 MainActivity 从 Intent extra 还原后调用。
     *
     * @param sentAt 通知发出时间（毫秒）；为 0 表示未知，此时不上报延迟属性
     */
    fun trackOpened(type: String, sentAt: Long) {
        val props = HashMap<String, Any?>().apply {
            put("notification_type", type)
            if (sentAt > 0L) {
                put("time_since_received_ms", System.currentTimeMillis() - sentAt)
            }
        }

        Analytics.track(Dictionary.Event.NOTIFICATION_CLICKED, props)
    }
}
