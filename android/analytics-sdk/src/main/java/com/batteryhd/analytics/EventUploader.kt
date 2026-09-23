package com.batteryhd.analytics

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 批量上报器 + 重试（PRD 10.3.4）。
 *
 * 触发时机：
 *  - 队列达到 [batchSize] 条
 *  - 定时检查：每 [uploadIntervalSeconds] 秒
 *  - App 进入后台：强制 flush
 *
 * 重试：指数退避 2/4/8/16/32 秒，最多 [maxRetryCount] 次。
 * **重试复用同一 batch_id**，服务端据此去重——这是不产生重复数据的关键。
 *
 * 时钟偏移：客户端时钟可能严重不准（东南亚低端机常见），
 * 服务端会拒收偏移 > 24h 的事件。因此每次成功响应都记录服务端时间并计算 offset，
 * 后续事件时间戳按此校正。
 */
internal class EventUploader(
    private val scope: CoroutineScope,
    private val api: ApiClient,
    private val store: EventStore,
    private val session: SessionManager,
    private val deviceInfo: DeviceInfo,
    private val tokenProvider: () -> String?,
    private val configProvider: () -> AnalyticsRemoteConfig
) {

    private val flushing = AtomicBoolean(false)
    private var periodicJob: Job? = null

    /** 服务端时间 - 客户端时间，用于校正事件时间戳 */
    @Volatile
    private var clockOffsetMs: Long = 0L

    fun updateClockOffset(serverTimestampMs: Long) {
        clockOffsetMs = serverTimestampMs - System.currentTimeMillis()
        AnalyticsLogger.d("Clock offset updated: ${clockOffsetMs}ms")
    }

    fun startPeriodicUpload() {
        periodicJob?.cancel()
        periodicJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(configProvider().uploadIntervalSeconds * 1000L)
                flush()
            }
        }
    }

    fun stopPeriodicUpload() {
        periodicJob?.cancel()
        periodicJob = null
    }

    /** 立即上报（App 进后台时调用） */
    fun flush() {
        if (!flushing.compareAndSet(false, true)) return   // 防止并发重复 flush

        scope.launch(Dispatchers.IO) {
            try {
                uploadOnce()
            } finally {
                flushing.set(false)
            }
        }
    }

    /** 同步执行一次上报（供测试与后台强制刷新使用） */
    internal suspend fun uploadOnce(): Boolean {
        val token = tokenProvider() ?: return false
        val cfg = configProvider()

        if (deviceInfo.isOffline) return false

        store.pruneExpired()

        val events = store.pending(cfg.batchSize)
        if (events.isEmpty()) return false

        val batchId = UUID.randomUUID().toString()

        return try {
            val payload = buildEventPayload(
                batchId = batchId,
                sessionId = session.sessionId,
                appVersion = deviceInfo.appVersion,
                osVersion = deviceInfo.osVersion,
                locale = deviceInfo.locale,
                events = events
            )

            var attempt = 0
            while (attempt <= cfg.maxRetryCount) {
                when (val res = api.uploadEvents(payload, token)) {
                    is ApiClient.Result.Success -> {
                        // 从响应中提取服务端时间用于时钟校正
                        runCatching {
                            val json = org.json.JSONObject(res.body)
                            val serverTs = json.optLong("server_timestamp", 0L)
                            if (serverTs > 0) updateClockOffset(serverTs)
                        }
                        store.delete(events.map { it.id })
                        AnalyticsLogger.d("Uploaded ${events.size} events (batch=$batchId)")
                        return true
                    }

                    is ApiClient.Result.Failure -> {
                        if (!res.retryable) {
                            // 不可重试的错误（如 422 参数错误）：丢弃并置为高重试次数，
                            // 避免坏数据永久卡住队列
                            AnalyticsLogger.w("Non-retryable upload failure: ${res.code} ${res.message}")
                            store.delete(events.map { it.id })
                            return false
                        }

                        if (attempt >= cfg.maxRetryCount) {
                            store.markRetry(events.map { it.id })
                            AnalyticsLogger.w("Upload gave up after ${cfg.maxRetryCount} retries")
                            return false
                        }

                        val waitMs = res.retryAfterSeconds?.times(1000L)
                            ?: ApiClient.backoffDelayMs(attempt)

                        AnalyticsLogger.d("Upload failed (${res.code}), retry in ${waitMs}ms")
                        delay(waitMs)
                        attempt++
                    }
                }
            }
            false
        } catch (e: Exception) {
            store.markRetry(events.map { it.id })
            AnalyticsLogger.e("Upload exception", e)
            false
        }
    }

    /** 校正后的当前时间戳（毫秒） */
    fun correctedTimestamp(): Long = System.currentTimeMillis() + clockOffsetMs
}
