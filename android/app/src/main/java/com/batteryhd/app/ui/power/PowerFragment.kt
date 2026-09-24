package com.batteryhd.app.ui.power

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.view.View
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.batteryhd.analytics.Analytics
import com.batteryhd.analytics.Dictionary
import com.batteryhd.app.BatteryHdApp
import com.batteryhd.app.R
import com.batteryhd.app.databinding.FragmentPowerBinding

/**
 * 功耗分析：应用耗电排行。
 *
 * 依赖 PACKAGE_USAGE_STATS 权限——这是**系统级权限**，不能通过运行时弹窗申请，
 * 必须跳转系统设置页由用户手动授予。因此埋点采用
 * requested / denied 成对设计，才能算出真正的"权限拒绝率"（PRD 10.2.5 要求分母）。
 */
class PowerFragment : Fragment(R.layout.fragment_power) {

    private var _binding: FragmentPowerBinding? = null
    private val binding get() = _binding!!

    private val app: BatteryHdApp get() = requireContext().applicationContext as BatteryHdApp
    private val adapter = AppRankAdapter()

    private var timeRange: String = Dictionary.TimeRange.DAY

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentPowerBinding.bind(view)

        binding.rvRanking.layoutManager = LinearLayoutManager(requireContext())
        binding.rvRanking.adapter = adapter

        binding.btnGrant.setOnClickListener { requestUsagePermission() }

        binding.rgRange.setOnCheckedStateChangeListener { _, checkedIds ->
            timeRange = if (checkedIds.contains(R.id.rbWeek)) {
                Dictionary.TimeRange.WEEK
            } else {
                Dictionary.TimeRange.DAY
            }
            loadRanking()
        }

        updatePermissionState()
        loadRanking()
    }

    override fun onResume() {
        super.onResume()
        // 从系统设置页返回：此时才能判断用户是否真的授予了权限
        if (_binding != null) updatePermissionState()
    }

    private fun updatePermissionState() {
        val granted = hasUsageStatsPermission()
        binding.permissionBanner.visibility = if (granted) View.GONE else View.VISIBLE
        binding.rvRanking.visibility = if (granted) View.VISIBLE else View.GONE
        if (!granted) {
            Analytics.track(
                Dictionary.Event.FEATURE_UNAVAILABLE,
                mapOf(
                    "feature_name" to Dictionary.FeatureName.DETAILED_POWER_ANALYSIS,
                    "missing_permission" to Dictionary.Permission.USAGE_STATS
                )
            )
        }
    }

    private fun requestUsagePermission() {
        Analytics.track(
            Dictionary.Event.PERMISSION_REQUESTED,
            mapOf(
                "permission_name" to Dictionary.Permission.USAGE_STATS,
                "context" to Dictionary.Permission.Context.FEATURE_USE
            )
        )
        runCatching {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }.onFailure {
            // 部分 ROM 没有该设置页，降级到应用详情页
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = android.net.Uri.parse("package:${requireContext().packageName}")
                    }
                )
            }
        }
    }

    private fun loadRanking() {
        if (!hasUsageStatsPermission()) {
            // 无权限时上报"被拒绝"，分母已在 PERMISSION_REQUESTED 处记录
            Analytics.track(
                Dictionary.Event.PERMISSION_DENIED,
                mapOf(
                    "permission_name" to Dictionary.Permission.USAGE_STATS,
                    "context" to Dictionary.Permission.Context.FEATURE_USE
                )
            )
            return
        }

        val rows = queryTopApps(timeRange)
        adapter.submit(rows)

        Analytics.track(
            Dictionary.Event.POWER_RANKING_VIEWED,
            mapOf(
                "time_range" to timeRange,
                "apps_shown" to rows.size
            )
        )
    }

    /**
     * 查询耗电排行。
     * 真实项目应使用 UsageStatsManager 的前台时长 + 电量估算模型；
     * 这里用前台时长占比近似，保证链路可跑通。
     */
    private fun queryTopApps(range: String): List<Pair<String, Float>> {
        val usm = requireContext().getSystemService(Context.USAGE_STATS_SERVICE)
            as? android.app.usage.UsageStatsManager ?: return emptyList()

        val now = System.currentTimeMillis()
        val window = if (range == Dictionary.TimeRange.WEEK) 7 * 24 * 3600_000L else 24 * 3600_000L

        val stats = runCatching {
            usm.queryUsageStats(android.app.usage.UsageStatsManager.INTERVAL_DAILY, now - window, now)
        }.getOrNull() ?: return emptyList()

        val total = stats.sumOf { it.totalTimeInForeground.toLong() }
        if (total <= 0) return emptyList()

        return stats
            .filter { it.totalTimeInForeground > 0 }
            .sortedByDescending { it.totalTimeInForeground }
            .take(10)
            .map { stat ->
                // 合规：只展示应用名与分类，永不上报名（PRD 10.6.1）
                val label = appLabel(stat.packageName)
                val percent = stat.totalTimeInForeground.toFloat() / total * 100f
                label to percent
            }
    }

    private fun appLabel(packageName: String): String {
        return runCatching {
            val pm = requireContext().packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        }.getOrDefault(packageName)
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = requireContext().getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            ?: return false
        val mode = runCatching {
            // checkOpNoThrow 自 API 30 起废弃，替代为 unsafeCheckOpNoThrow；
            // minSdk 26 仍需兼容旧分支
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    requireContext().packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    requireContext().packageName
                )
            }
        }.getOrDefault(AppOpsManager.MODE_ERRORED)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
