package com.batteryhd.app.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.fragment.app.Fragment
import com.batteryhd.analytics.Analytics
import com.batteryhd.analytics.Dictionary
import com.batteryhd.app.BatteryHdApp
import com.batteryhd.app.R
import com.batteryhd.app.databinding.ActivityMainBinding
import com.batteryhd.app.notification.NotificationHelper
import com.batteryhd.app.ui.home.HomeFragment
import com.batteryhd.app.ui.charge.ChargeFragment
import com.batteryhd.app.ui.monitor.MonitorFragment
import com.batteryhd.app.ui.power.PowerFragment
import com.batteryhd.app.ui.saving.SavingFragment
import com.batteryhd.app.ui.settings.SettingsFragment

/**
 * 主界面：底部 5 个 tab + 容器。
 *
 * "设置" 不在底栏（BottomNavigationView 最多 5 项），从右上角入口进入，
 * 进入时隐藏底栏并加入返回栈，返回后恢复。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var currentScreen: String = Dictionary.Screen.HOME_DASHBOARD
    private var settingsOpen = false

    private val app: BatteryHdApp get() = application as BatteryHdApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        setupBackHandling()
        setupBottomNav()

        settingsOpen = supportFragmentManager.backStackEntryCount > 0
        showSettingsNavigation(settingsOpen)
        syncBottomNavVisibility()

        if (savedInstanceState == null) {
            showTab(R.id.nav_home)
            binding.bottomNav.selectedItemId = R.id.nav_home
        }

        handleNotificationIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        // 切换语言会重建 Activity。设置页在返回栈里时，底栏保持隐藏，顶栏保留返回。
        settingsOpen = supportFragmentManager.backStackEntryCount > 0
        syncBottomNavVisibility()
        showSettingsNavigation(settingsOpen)
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_settings)?.isVisible = !settingsOpen
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // SINGLE_TOP 下通知点击走这里，而不是 onCreate
        handleNotificationIntent(intent)
    }

    /**
     * 通知点击回传（PRD 10.2.4 H：notification_clicked）。
     *
     * 读完立即清掉 extra：Intent 会在配置变更（旋转屏幕）时重建并再次走到这里，
     * 不清会把一次点击记成多次。
     */
    private fun handleNotificationIntent(intent: Intent?) {
        val type = intent?.getStringExtra(NotificationHelper.EXTRA_TYPE) ?: return
        val sentAt = intent.getLongExtra(NotificationHelper.EXTRA_SENT_AT, 0L)

        intent.removeExtra(NotificationHelper.EXTRA_TYPE)
        intent.removeExtra(NotificationHelper.EXTRA_SENT_AT)

        NotificationHelper.trackOpened(type, sentAt)
    }

    // ------------------------------------------------------------ 导航

    private fun setupBottomNav() {
        // 配置变更（含应用内切换语言）会恢复已选中的 tab，并回调这个监听。
        // 若此时设置页还在返回栈里，直接 showTab 会把它弹出。等首帧后再接受点击。
        var acceptSelection = false
        binding.bottomNav.setOnItemSelectedListener { item ->
            if (acceptSelection) showTab(item.itemId)
            true
        }
        binding.bottomNav.post { acceptSelection = true }
        // 重复点击同一 tab 不重建 Fragment，避免埋点重复与 UI 闪烁
        binding.bottomNav.setOnItemReselectedListener { /* no-op */ }
    }

    private fun showTab(itemId: Int) {
        settingsOpen = false
        showSettingsNavigation(false)
        binding.bottomNav.visibility = View.VISIBLE
        supportFragmentManager.popBackStack()

        val fragment = when (itemId) {
            R.id.nav_monitor -> MonitorFragment()
            R.id.nav_charge -> ChargeFragment()
            R.id.nav_power -> PowerFragment()
            R.id.nav_saving -> SavingFragment()
            else -> HomeFragment()
        }

        replaceFragment(fragment)
        trackScreen(screenForTab(itemId))
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return onToolbarItemSelected(item) || super.onOptionsItemSelected(item)
    }

    private fun onToolbarItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                openSettings()
                true
            }
            android.R.id.home -> {
                if (settingsOpen) leaveSettings()
                true
            }
            else -> false
        }
    }

    /**
     * 由各 Fragment 调用：跳转到 Pro 升级页。
     *
     * @param featureName 若本次跳转源于**某个 Pro 功能被拦截**（如"无限校准"次数用尽），
     *   传入 [Dictionary.FeatureName] 的具体值，会额外上报 `feature_gate_shown`；
     *   仅是入口曝光（首页横幅、设置项）则传 null，只上报 `pro_page_viewed`。
     *
     * 区分的意义：漏斗是 `feature_gate_shown → pro_page_viewed → purchase_initiated → purchase_completed`。
     * 若不分清，入口曝光会被算成拦截曝光，拦截率虚高，误判为"功能门槛太激进"。
     */
    fun openPro(source: String, featureName: String? = null) {
        val currentPlan = if (app.prefs.isPro) Dictionary.CurrentPlan.PRO else Dictionary.CurrentPlan.FREE

        if (featureName != null) {
            Analytics.track(
                Dictionary.Event.FEATURE_GATE_SHOWN,
                mapOf(
                    "feature_name" to featureName,
                    "current_plan" to currentPlan
                )
            )
        }
        Analytics.track(
            Dictionary.Event.PRO_PAGE_VIEWED,
            mapOf(
                "source" to source,
                "current_plan" to currentPlan
            )
        )
        startActivity(ProActivity.intent(this, source))
    }

    /**
     * 页面埋点。
     * SDK 的 trackScreen 会自动维护 previous_screen 与 session_depth，
     * 这里额外记录 currentScreen 只为避免重复上报同一页面。
     */
    fun trackScreen(screen: String) {
        if (currentScreen == screen) return
        Analytics.trackScreen(screen, currentScreen)
        currentScreen = screen
    }

    private fun replaceFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .commit()
    }

    private fun openSettings() {
        if (settingsOpen) return
        settingsOpen = true
        binding.bottomNav.visibility = View.GONE
        showSettingsNavigation(true)
        invalidateOptionsMenu()
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, SettingsFragment())
            .addToBackStack(TAG_SETTINGS)
            .commit()
        trackScreen(Dictionary.Screen.SETTINGS)
    }

    private fun leaveSettings() {
        if (!settingsOpen) return
        settingsOpen = false
        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStack()
        }
        binding.bottomNav.visibility = View.VISIBLE
        showSettingsNavigation(false)
        invalidateOptionsMenu()
        trackScreen(screenForTab(binding.bottomNav.selectedItemId))
    }

    private fun showSettingsNavigation(inSettings: Boolean) {
        if (inSettings) {
            val icon = AppCompatResources.getDrawable(
                this,
                androidx.appcompat.R.drawable.abc_ic_ab_back_material
            )?.mutate()
            icon?.setTint(Color.WHITE)
            binding.toolbar.navigationIcon = icon
            binding.toolbar.navigationContentDescription =
                getString(androidx.appcompat.R.string.abc_action_bar_up_description)
            binding.toolbar.setNavigationOnClickListener { leaveSettings() }
            binding.toolbar.title = getString(R.string.settings_title)
        } else {
            binding.toolbar.navigationIcon = null
            binding.toolbar.setNavigationOnClickListener(null)
            binding.toolbar.title = getString(R.string.app_name)
        }
    }

    private fun screenForTab(itemId: Int): String = when (itemId) {
        R.id.nav_monitor -> Dictionary.Screen.BATTERY_MONITOR
        R.id.nav_charge -> Dictionary.Screen.CHARGE_PROTECTION
        R.id.nav_power -> Dictionary.Screen.POWER_ANALYSIS
        R.id.nav_saving -> Dictionary.Screen.SMART_SAVING
        else -> Dictionary.Screen.HOME_DASHBOARD
    }

    fun navigateToCharge() {
        binding.bottomNav.selectedItemId = R.id.nav_charge
    }

    fun navigateToPower() {
        binding.bottomNav.selectedItemId = R.id.nav_power
    }

    private fun syncBottomNavVisibility() {
        binding.bottomNav.visibility = if (settingsOpen) View.GONE else View.VISIBLE
    }

    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (settingsOpen || supportFragmentManager.backStackEntryCount > 0) {
                    leaveSettings()
                } else {
                    finish()
                }
            }
        })
    }

    companion object {
        private const val TAG_SETTINGS = "settings"
    }
}
