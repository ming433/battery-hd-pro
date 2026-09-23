package com.batteryhd.analytics

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * 会话与标识管理（PRD 10.3.2）。
 *
 * **会话边界的唯一权威是服务端**（Worker 按事件流推导）。客户端生成的 [sessionId]
 * 仅用于把事件关联成组，不承担"会话数"指标的计算职责——否则客户端、
 * 服务端、埋点 SDK 三方会算出三个不同的会话数。
 *
 * 客户端规则（用于对服务端推导结果做交叉验证，不单独对外发布指标）：
 * - 冷启动 / 前台恢复且距上次交互 > [SESSION_TIMEOUT_MS] → 新会话
 * - App 进入后台 → 会话结束（服务端以事件流 + 30 分钟空闲兜底）
 */
internal class SessionManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    /** 匿名设备 ID：首次启动生成 UUID，存于 SharedPreferences。不采集 IMEI/Android ID（PRD 10.6.1） */
    val deviceId: String by lazy {
        prefs.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_DEVICE_ID, it).apply()
        }
    }

    @Volatile
    var sessionId: String = newSessionId()
        private set

    /** 当前会话开始时间（毫秒），用于计算 session_duration_ms */
    @Volatile
    var startedAt: Long = System.currentTimeMillis()
        private set

    /** 会话内的页面访问深度，用于分析用户浏览路径深度 */
    private val depth = AtomicInteger(0)

    private var lastInteractionAt: Long = System.currentTimeMillis()

    val sessionDepth: Int get() = depth.get()

    fun nextDepth(): Int = depth.incrementAndGet()

    /** 距上次交互是否已超时（用于判断是否需要开启新会话） */
    fun isTimedOut(): Boolean =
        System.currentTimeMillis() - lastInteractionAt > SESSION_TIMEOUT_MS

    /** 记录一次用户交互，刷新会话活跃时间 */
    fun touch() {
        lastInteractionAt = System.currentTimeMillis()
    }

    /** 开启新会话，返回新的 sessionId */
    fun startNewSession(): String {
        sessionId = newSessionId()
        depth.set(0)
        startedAt = System.currentTimeMillis()
        lastInteractionAt = startedAt
        return sessionId
    }

    /** 会话序号（设备级累计），服务端用于计算留存与"第 N 次访问" */
    val sessionIndex: Int
        get() = prefs.getInt(KEY_SESSION_INDEX, 0)

    fun incrementSessionIndex() {
        prefs.edit().putInt(KEY_SESSION_INDEX, sessionIndex + 1).apply()
    }

    private fun newSessionId(): String = UUID.randomUUID().toString()

    companion object {
        const val SESSION_TIMEOUT_MS = 30 * 60 * 1000L   // 30 分钟（PRD 10.3.2）
        private const val PREF_NAME = "bhd_session"
        private const val KEY_DEVICE_ID = "device_uuid"
        private const val KEY_SESSION_INDEX = "session_index"
    }
}
