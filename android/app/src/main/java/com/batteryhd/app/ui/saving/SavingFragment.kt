package com.batteryhd.app.ui.saving

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import com.batteryhd.analytics.Analytics
import com.batteryhd.analytics.Dictionary
import com.batteryhd.app.BatteryHdApp
import com.batteryhd.app.R
import com.batteryhd.app.databinding.FragmentSavingBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** 智能省电：模式选择与应用休眠 */
class SavingFragment : Fragment(R.layout.fragment_saving) {

    private var _binding: FragmentSavingBinding? = null
    private val binding get() = _binding!!

    private val app: BatteryHdApp get() = requireContext().applicationContext as BatteryHdApp

    /** 可选的应用分类。合规：只上报分类，绝不上报名 */
    private val categories = arrayOf(
        Dictionary.AppCategory.SOCIAL,
        Dictionary.AppCategory.VIDEO,
        Dictionary.AppCategory.GAME,
        Dictionary.AppCategory.SHOPPING,
        Dictionary.AppCategory.BROWSER,
        Dictionary.AppCategory.IM
    )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentSavingBinding.bind(view)

        binding.switchSaving.isChecked = app.prefs.savingEnabled
        binding.switchSaving.setOnCheckedChangeListener { _, isChecked ->
            val mode = currentMode()
            app.prefs.savingEnabled = isChecked
            app.prefs.savingMode = mode
            if (isChecked) {
                Analytics.track(
                    Dictionary.Event.SMART_SAVING_ENABLED,
                    mapOf("mode" to mode)
                )
            }
        }

        binding.btnPickApps.setOnClickListener { showCategoryPicker() }
        updateSelected()
    }

    private fun currentMode(): String = when {
        binding.rbAggressive.isChecked -> Dictionary.SavingMode.AGGRESSIVE
        binding.rbCustom.isChecked -> Dictionary.SavingMode.CUSTOM
        else -> Dictionary.SavingMode.BALANCED
    }

    private fun showCategoryPicker() {
        val selected = categories.map { it in app.prefs.hibernatedCategories }.toBooleanArray()

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.saving_apps)
            .setMultiChoiceItems(categories, selected) { _, which, isChecked ->
                selected[which] = isChecked
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val chosen = categories.filterIndexed { index, _ -> selected[index] }.toSet()
                app.prefs.hibernatedCategories = chosen

                Analytics.track(
                    Dictionary.Event.APP_HIBERNATION_SET,
                    mapOf(
                        // 合规：只上报名分类。package_name 会在服务端被字典剥离，
                        // 客户端干脆不发——避免"发了但被剥离"的灰色地带。
                        "app_category" to (chosen.firstOrNull() ?: Dictionary.AppCategory.OTHER),
                        "target_app_count" to chosen.size
                    )
                )
                updateSelected()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun updateSelected() {
        val count = app.prefs.hibernatedCategories.size
        if (count == 0) {
            binding.tvSelected.visibility = android.view.View.GONE
        } else {
            binding.tvSelected.visibility = android.view.View.VISIBLE
            binding.tvSelected.text = app.prefs.hibernatedCategories.joinToString(", ")
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
