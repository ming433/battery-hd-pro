package com.batteryhd.app.ui.settings

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.fragment.app.Fragment
import com.batteryhd.analytics.Analytics
import com.batteryhd.analytics.ConsentManager
import com.batteryhd.analytics.Dictionary
import com.batteryhd.app.BatteryHdApp
import com.batteryhd.app.BuildConfig
import com.batteryhd.app.R
import com.batteryhd.app.databinding.FragmentSettingsBinding
import com.batteryhd.app.notification.NotificationHelper
import com.batteryhd.app.ui.MainActivity

/** 设置：埋点开关、提醒开关、通知权限、数据删除、订阅入口 */
class SettingsFragment : Fragment(R.layout.fragment_settings) {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val app: BatteryHdApp get() = requireContext().applicationContext as BatteryHdApp

    /**
     * 通知权限请求。
     *
     * 结果要同时上报两个事件，不是重复：
     * - `permission_requested` / `permission_denied` 是通用权限链路，用于算**权限拒绝率**；
     * - `notification_permission_result` 是通知专用事件，用于分析通知触达的前置条件。
     * 只报后者会让"通知权限拒绝率"无法与其余权限横向对比。
     */
    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val contextValue = Dictionary.Permission.Context.SETTINGS

        Analytics.track(
            Dictionary.Event.NOTIFICATION_PERMISSION_RESULT,
            mapOf("granted" to granted, "context" to contextValue)
        )

        if (!granted) {
            Analytics.track(
                Dictionary.Event.PERMISSION_DENIED,
                mapOf(
                    "permission_name" to Dictionary.Permission.NOTIFICATION,
                    "context" to contextValue
                )
            )
            Toast.makeText(requireContext(), R.string.settings_notification_denied, Toast.LENGTH_SHORT).show()
        }

        refreshPermissionUi()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentSettingsBinding.bind(view)

        val consent = ConsentManager(requireContext())
        binding.switchAnalytics.isChecked = consent.isAnalyticsEnabled()

        binding.switchAnalytics.setOnCheckedChangeListener { _, isChecked ->
            // 版本号要在写入前取，避免把"新版本号"和"旧版本下的同意"绑在一起
            val policyVersion = ConsentManager.DEFAULT_POLICY_VERSION

            consent.setConsent(
                analytics = isChecked,
                personalizedAds = isChecked,
                policyVersion = policyVersion
            )

            // SDK 可能未初始化（用户此前拒绝），此时调用是 no-op，安全
            Analytics.setAnalyticsEnabled(isChecked)

            // 同意与撤回都要留凭证：只记"同意"不记"撤回"，
            // 服务端会永远认为用户仍处于同意状态，合规审计时无法自证。
            Analytics.syncConsent(
                action = if (isChecked) Analytics.ConsentAction.GRANTED else Analytics.ConsentAction.WITHDRAWN,
                analyticsEnabled = isChecked,
                personalizedAds = isChecked,
                policyVersion = policyVersion
            )
        }

        setupReminders()
        refreshPermissionUi()

        binding.btnNotificationPermission.setOnClickListener { askNotificationPermission() }

        binding.btnDeleteData.setOnClickListener {
            // PRD 10.6.3：用户删除权。"关闭开关"不等于"删除已收集数据"。
            Analytics.deleteLocalData()
            consent.resetForDataDeletion()
            binding.switchAnalytics.isChecked = false
            Toast.makeText(requireContext(), R.string.settings_delete_done, Toast.LENGTH_SHORT).show()
        }

        binding.btnPro.setOnClickListener {
            (activity as? MainActivity)?.openPro(Dictionary.Source.SETTINGS)
        }

        binding.tvVersion.text = getString(R.string.settings_version, BuildConfig.VERSION_NAME)
    }

    // ------------------------------------------------------------ 提醒开关

    private fun setupReminders() {
        bindReminderSwitch(binding.switchReminderChargeLimit, app.prefs.reminderChargeLimit) {
            app.prefs.reminderChargeLimit = it
            Dictionary.ReminderType.CHARGE_LIMIT
        }
        bindReminderSwitch(binding.switchReminderTempAlert, app.prefs.reminderTempAlert) {
            app.prefs.reminderTempAlert = it
            Dictionary.ReminderType.TEMP_ALERT
        }
        bindReminderSwitch(binding.switchReminderWeeklyReport, app.prefs.reminderWeeklyReport) {
            app.prefs.reminderWeeklyReport = it
            Dictionary.ReminderType.WEEKLY_REPORT
        }
        bindReminderSwitch(binding.switchReminderCalibration, app.prefs.reminderCalibration) {
            app.prefs.reminderCalibration = it
            Dictionary.ReminderType.CALIBRATION
        }
    }

    /**
     * 绑定提醒开关并上报 `reminder_setting_changed`。
     *
     * 只监听用户操作（setOnCheckedChangeListener 会在初始化 setValue 时也触发，
     * 因此这里先 setChecked 再挂监听，避免把"渲染初始状态"误报成一次变更）。
     */
    private fun bindReminderSwitch(
        switch: com.google.android.material.materialswitch.MaterialSwitch,
        initial: Boolean,
        persist: (Boolean) -> String
    ) {
        switch.isChecked = initial

        switch.setOnCheckedChangeListener { _, isChecked ->
            val type = persist(isChecked)
            Analytics.track(
                Dictionary.Event.REMINDER_SETTING_CHANGED,
                mapOf(
                    "reminder_type" to type,
                    "enabled" to isChecked
                )
            )
        }
    }

    // ---------------------------------------------------------- 通知权限

    private fun refreshPermissionUi() {
        // Android 13 以下由系统授予，无需申请入口
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            binding.btnNotificationPermission.visibility = View.GONE
            return
        }

        val granted = NotificationHelper.hasPermission(requireContext())
        binding.btnNotificationPermission.visibility = if (granted) View.GONE else View.VISIBLE
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

        // 请求前先记分母：permission_requested 是权限拒绝率的分母，不参与采样
        val rationale = ActivityCompat.shouldShowRequestPermissionRationale(
            requireActivity(), Manifest.permission.POST_NOTIFICATIONS
        )

        Analytics.track(
            Dictionary.Event.PERMISSION_REQUESTED,
            mapOf(
                "permission_name" to Dictionary.Permission.NOTIFICATION,
                "context" to Dictionary.Permission.Context.SETTINGS,
                "is_rationale_shown" to rationale
            )
        )

        requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
