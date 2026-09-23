package com.batteryhd.analytics

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPOutputStream

/**
 * HTTP 客户端（零第三方依赖，避免与主 App 的 OkHttp/Retrofit 版本冲突）。
 *
 * 请求体超过 [GZIP_THRESHOLD_BYTES] 时启用 gzip——批量上报 20~50 条事件的 JSON
 * 通常在 5~20KB，压缩后可降至 1/5，对东南亚弱网环境的上报成功率影响显著。
 */
internal class ApiClient(
    private val baseUrl: String,
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 15_000
) {

    sealed class Result {
        data class Success(val body: String, val code: Int) : Result()
        data class Failure(val code: Int?, val message: String, val retryable: Boolean, val retryAfterSeconds: Int?) : Result()
    }

    // ------------------------------------------------------------ 设备注册

    data class RegisterResponse(val token: String, val serverTimestamp: Long)

    fun register(payload: JSONObject): kotlin.Result<RegisterResponse> = runCatching {
        val res = post("/api/v1/analytics/register", payload, authToken = null)
        when (res) {
            is Result.Success -> {
                val json = JSONObject(res.body)
                val data = json.optJSONObject("data") ?: json
                RegisterResponse(
                    token = data.getString("token"),
                    serverTimestamp = data.optLong("server_timestamp", System.currentTimeMillis())
                )
            }
            is Result.Failure -> throw ApiException(res.code, res.message)
        }
    }

    // ------------------------------------------------------------ 事件上报

    /**
     * 批量上报。
     *
     * **幂等关键**：[batchId] 在重试时必须保持不变——服务端以 `batch_id` 做去重，
     * 若每次重试都生成新的 batch_id，超时重试会产生重复入库，
     * 导致所有计数类指标系统性虚高。
     */
    fun uploadEvents(payload: JSONObject, authToken: String): Result =
        post("/api/v1/analytics/events", payload, authToken)

    // ------------------------------------------------------------ 配置拉取

    fun fetchAnalyticsConfig(authToken: String): Result =
        get("/api/v1/config/analytics", authToken)

    /** 广告配置走独立端点（PRD 10.5：与埋点解耦，用户关闭埋点后广告仍可拉取） */
    fun fetchAdConfig(authToken: String, country: String, appVersion: String, isPro: Boolean): Result =
        get("/api/v1/config/ads?country=$country&app_version=$appVersion&is_pro=$isPro", authToken)

    // -------------------------------------------------------- 同意凭证

    /**
     * 上报同意/撤回凭证（PRD 10.7.3）。
     *
     * 与事件上报分开走独立端点，因为**撤回同意后事件通道会被关闭**，
     * 若塞进事件队列，"撤回"这条记录将永远发不出去，
     * 服务端会一直以为用户仍处在已同意状态。
     */
    fun reportConsent(
        authToken: String,
        action: String,
        analytics: Boolean,
        personalizedAds: Boolean,
        policyVersion: String,
        decidedAtMs: Long
    ): Result = post(
        "/api/v1/analytics/consent",
        JSONObject().apply {
            put("action", action)
            put("analytics", analytics)
            put("personalized_ads", personalizedAds)
            put("policy_version", policyVersion)
            put("decided_at", decidedAtMs)
        },
        authToken
    )

    // -------------------------------------------------------------- 底层

    private fun post(path: String, body: JSONObject, authToken: String?): Result {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(baseUrl.trimEnd('/') + path)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                if (authToken != null) setRequestProperty("Authorization", "Bearer $authToken")
                doOutput = true
            }

            val raw = body.toString().toByteArray(Charsets.UTF_8)
            val useGzip = raw.size > GZIP_THRESHOLD_BYTES
            if (useGzip) conn.setRequestProperty("Content-Encoding", "gzip")

            conn.outputStream.use { os ->
                if (useGzip) {
                    GZIPOutputStream(os).use { gzip -> gzip.write(raw) }
                } else {
                    os.write(raw)
                }
            }

            handleResponse(conn)
        } catch (e: Exception) {
            Result.Failure(null, e.message ?: "network error", retryable = true, retryAfterSeconds = null)
        } finally {
            conn?.disconnect()
        }
    }

    private fun get(path: String, authToken: String): Result {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(baseUrl.trimEnd('/') + path)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Authorization", "Bearer $authToken")
            }
            handleResponse(conn)
        } catch (e: Exception) {
            Result.Failure(null, e.message ?: "network error", retryable = true, retryAfterSeconds = null)
        } finally {
            conn?.disconnect()
        }
    }

    private fun handleResponse(conn: HttpURLConnection): Result {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.let {
            BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { br -> br.readText() }
        } ?: ""

        if (code in 200..299) return Result.Success(body, code)

        // 429 需读取 Retry-After，客户端据此延迟重试
        val retryAfter = conn.getHeaderField("Retry-After")?.toIntOrNull()

        return Result.Failure(
            code = code,
            message = body.take(200),
            // 4xx（除 408/429）视为不可重试：请求本身有问题，重试只会重复失败
            retryable = code >= 500 || code == 429 || code == 408,
            retryAfterSeconds = retryAfter
        )
    }

    class ApiException(val code: Int?, message: String) : Exception("HTTP $code: $message")

    companion object {
        private const val GZIP_THRESHOLD_BYTES = 1024

        /** 指数退避：2s → 4s → 8s → 16s → 32s（PRD 10.3.4，最多 5 次） */
        fun backoffDelayMs(retryCount: Int): Long {
            val seconds = (1L shl retryCount.coerceAtMost(5)) * 1000L   // 2,4,8,16,32
            return seconds.coerceAtMost(32_000L)
        }
    }
}

/** 构造事件上报请求体 */
internal fun buildEventPayload(
    batchId: String,
    sessionId: String,
    appVersion: String,
    osVersion: String,
    locale: String,
    events: List<EventRecord>
): JSONObject = JSONObject().apply {
    put("batch_id", batchId)
    put("session_id", sessionId)
    put("app_version", appVersion)
    put("os_version", osVersion)
    put("locale", locale)
    put("events", JSONArray().apply {
        events.forEach { e ->
            put(JSONObject().apply {
                put("event_name", e.eventName)
                put("timestamp", e.clientTimestamp)
                put("properties", JSONObject(e.properties.filterValues { it != null }))
            })
        }
    })
}
