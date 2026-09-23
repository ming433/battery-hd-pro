package com.batteryhd.analytics

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import java.util.Locale

/**
 * 设备与上下文信息采集（PRD 10.2.2 公共属性）。
 *
 * **合规红线（PRD 10.6.1）**：
 * 不采集 IMEI、MAC、Android ID、序列号、精确位置、通讯录、短信、照片。
 * 仅采集分群分析必需的低敏维度：机型、厂商、系统版本、语言、国家、网络类型。
 */
internal class DeviceInfo(private val context: Context) {

    val deviceModel: String get() = Build.MODEL ?: "unknown"

    val manufacturer: String get() = Build.MANUFACTURER ?: "unknown"

    val osVersion: String get() = Build.VERSION.RELEASE ?: "unknown"

    val locale: String
        get() = Locale.getDefault().toLanguageTag().ifBlank { "in" }

    /**
     * 国家码：仅用于服务端下发的 country 兜底。
     * **服务端会按 IP 重新判定并回写**（PRD：country 必须两端同源，否则会出现
     * "看板显示 VN 用户却拿到 ID 配置"的问题）。此处仅为本地估算值。
     */
    val country: String
        get() = Locale.getDefault().country.ifBlank { "" }

    val appVersion: String
        get() = try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName ?: "unknown"
        } catch (e: PackageManager.NameNotFoundException) {
            "unknown"
        }

    val networkType: String
        get() {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return Dictionary.NetworkType.UNKNOWN
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val caps = cm.activeNetwork ?: return Dictionary.NetworkType.OFFLINE
                val nc = cm.getNetworkCapabilities(caps) ?: return Dictionary.NetworkType.UNKNOWN
                return when {
                    nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Dictionary.NetworkType.WIFI
                    nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Dictionary.NetworkType.CELLULAR
                    else -> Dictionary.NetworkType.UNKNOWN
                }
            }
            @Suppress("DEPRECATION")
            val info = cm.activeNetworkInfo ?: return Dictionary.NetworkType.OFFLINE
            @Suppress("DEPRECATION")
            return when (info.type) {
                ConnectivityManager.TYPE_WIFI -> Dictionary.NetworkType.WIFI
                ConnectivityManager.TYPE_MOBILE -> Dictionary.NetworkType.CELLULAR
                else -> Dictionary.NetworkType.UNKNOWN
            }
        }

    /** 是否处于离线状态，用于跳过无效上报尝试 */
    val isOffline: Boolean
        get() = networkType == Dictionary.NetworkType.OFFLINE

    /** 屏幕尺寸分桶（小/正常/大/超大），用于 UI 适配分析，非精确分辨率 */
    val screenBucket: String
        get() {
            val size = context.resources.configuration.screenLayout and Configuration.SCREENLAYOUT_SIZE_MASK
            return when (size) {
                Configuration.SCREENLAYOUT_SIZE_SMALL -> "small"
                Configuration.SCREENLAYOUT_SIZE_NORMAL -> "normal"
                Configuration.SCREENLAYOUT_SIZE_LARGE -> "large"
                Configuration.SCREENLAYOUT_SIZE_XLARGE -> "xlarge"
                else -> "undefined"
            }
        }
}
