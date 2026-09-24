package com.batteryhd.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.widget.RemoteViews
import com.batteryhd.analytics.Analytics
import com.batteryhd.app.R
import com.batteryhd.app.ui.ConsentActivity
import kotlin.math.roundToInt

/**
 * Battery Coach home screen widget.
 *
 * Displays: battery level %, temperature, charging status.
 * Taps open the app (Home).
 *
 * Update strategy:
 * - updatePeriodMillis = 30 min (system-driven periodic update)
 * - Also updates on battery state changes via ACTION_BATTERY_CHANGED broadcast
 *   (registered dynamically, not aggressive polling)
 */
class BatteryCoachWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        Analytics.track("widget_install", emptyMap())
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        
        when (intent.action) {
            Intent.ACTION_BATTERY_CHANGED,
            Intent.ACTION_POWER_CONNECTED,
            Intent.ACTION_POWER_DISCONNECTED -> {
                updateAllWidgets(context)
            }
        }
    }

    companion object {
        fun updateAllWidgets(context: Context) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val componentName = ComponentName(context, BatteryCoachWidget::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)
            
            for (appWidgetId in appWidgetIds) {
                updateWidget(context, appWidgetManager, appWidgetId)
            }
        }

        private fun updateWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val batteryStatus = getBatteryStatus(context)
            val views = RemoteViews(context.packageName, R.layout.widget_battery_coach)

            views.setTextViewText(
                R.id.tvWidgetLevel,
                context.getString(R.string.home_level, batteryStatus.levelPercent)
            )
            
            views.setTextViewText(
                R.id.tvWidgetTemp,
                context.getString(R.string.home_temp, batteryStatus.temperatureC)
            )

            val statusText = if (batteryStatus.isCharging) {
                context.getString(R.string.widget_charging)
            } else {
                context.getString(R.string.widget_not_charging)
            }
            views.setTextViewText(R.id.tvWidgetStatus, statusText)

            val intent = Intent(context, ConsentActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(android.R.id.background, pendingIntent)
            views.setOnClickPendingIntent(R.id.tvWidgetLevel, pendingIntent)
            views.setOnClickPendingIntent(R.id.tvWidgetTemp, pendingIntent)
            views.setOnClickPendingIntent(R.id.tvWidgetStatus, pendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        private fun getBatteryStatus(context: Context): BatteryStatus {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val levelPercent = if (level >= 0 && scale > 0) {
                (level * 100f / scale).roundToInt()
            } else {
                0
            }

            val tempRaw = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
            val temperatureC = tempRaw / 10f

            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL

            return BatteryStatus(levelPercent, temperatureC, isCharging)
        }
    }

    data class BatteryStatus(
        val levelPercent: Int,
        val temperatureC: Float,
        val isCharging: Boolean
    )
}
