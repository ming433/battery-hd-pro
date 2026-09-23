package com.batteryhd.analytics

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.util.LinkedList

/**
 * 事件本地持久化（SQLite，PRD 10.3.4）。
 *
 * 职责：落盘待上报事件、按 FIFO 淘汰超限事件、离线事件 TTL 清理。
 *
 * **淘汰策略**：队列达到 [maxEvents] 时按 FIFO 丢弃最旧事件，
 * 但 [Dictionary.CRITICAL_EVENTS] 不参与丢弃——这些事件关系到收入对账与转化归因，
 * 丢一条就是永久性的数据缺口。
 *
 * **离线 TTL**：超过 [TTL_DAYS] 的事件视为过期直接清理。客户端时钟可能不准，
 * 上报长时离线后的陈旧数据会污染"当前活跃"类指标。
 */
internal class EventStore(context: Context, initialMaxEvents: Int) :
    SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    /** 本地队列上限，可由远程配置 max_offline_events 动态覆盖 */
    @Volatile
    var maxEvents: Int = initialMaxEvents.coerceIn(100, 5000)
        set(value) { field = value.coerceIn(100, 5000) }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                event_name TEXT NOT NULL,
                properties TEXT NOT NULL,
                client_timestamp INTEGER NOT NULL,
                session_id TEXT NOT NULL,
                retry_count INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_events_created ON events(created_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_events_retry ON events(retry_count)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 → v2 暂无结构变更；后续如需迁移在此按版本逐步处理，避免直接 DROP 丢数据
        if (oldVersion < 2) {
            onCreate(db)
        }
    }

    /** 写入一条事件，返回是否成功 */
    @Synchronized
    fun insert(record: EventRecord): Boolean {
        return try {
            writableDatabase.use { db ->
                val values = ContentValues().apply {
                    put("event_name", record.eventName)
                    put("properties", JSONObject(record.properties.filterValues { it != null }).toString())
                    put("client_timestamp", record.clientTimestamp)
                    put("session_id", record.sessionId)
                    put("retry_count", record.retryCount)
                    put("created_at", record.createdAt)
                }
                db.insertOrThrow("events", null, values)
            }
            enforceLimit()
            true
        } catch (e: Exception) {
            AnalyticsLogger.e("EventStore.insert failed", e)
            false
        }
    }

    /**
     * 取出最多 [limit] 条事件用于上报。
     * 优先取重试次数少的（新事件优先），避免少数坏事件反复占用上报机会。
     */
    @Synchronized
    fun pending(limit: Int): List<EventRecord> {
        return try {
            readableDatabase.use { db ->
                val cursor = db.query(
                    "events", null, null, null, null, null,
                    "retry_count ASC, id ASC", limit.toString()
                )
                cursor.use { c ->
                    val result = LinkedList<EventRecord>()
                    while (c.moveToNext()) {
                        result.add(c.toRecord())
                    }
                    result
                }
            }
        } catch (e: Exception) {
            AnalyticsLogger.e("EventStore.pending failed", e)
            emptyList()
        }
    }

    @Synchronized
    fun count(): Int {
        return try {
            readableDatabase.use { db ->
                db.rawQuery("SELECT COUNT(*) FROM events", null).use {
                    if (it.moveToFirst()) it.getInt(0) else 0
                }
            }
        } catch (e: Exception) {
            0
        }
    }

    /** 上报成功后删除 */
    @Synchronized
    fun delete(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        try {
            writableDatabase.use { db ->
                val placeholders = ids.joinToString(",") { "?" }
                db.delete("events", "id IN ($placeholders)", ids.map { it.toString() }.toTypedArray())
            }
        } catch (e: Exception) {
            AnalyticsLogger.e("EventStore.delete failed", e)
        }
    }

    /** 标记重试次数 +1 */
    @Synchronized
    fun markRetry(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        try {
            writableDatabase.use { db ->
                val placeholders = ids.joinToString(",") { "?" }
                db.execSQL(
                    "UPDATE events SET retry_count = retry_count + 1 WHERE id IN ($placeholders)",
                    // execSQL 的 bindArgs 是 Array<Any>：Kotlin 的数组不变型要求元素类型就是 Any，
                    // 直接在 map 上声明返回类型比逐个 as Any 更清晰
                    ids.map { it.toString() }.toTypedArray<Any>()
                )
            }
        } catch (e: Exception) {
            AnalyticsLogger.e("EventStore.markRetry failed", e)
        }
    }

    /**
     * 超限淘汰：FIFO 丢弃最旧的非关键事件。
     */
    @Synchronized
    fun enforceLimit() {
        try {
            val total = count()
            if (total <= maxEvents) return

            val overflow = total - maxEvents
            writableDatabase.use { db ->
                db.execSQL(
                    """
                    DELETE FROM events WHERE id IN (
                        SELECT id FROM events
                        WHERE event_name NOT IN (${criticalPlaceholders()})
                        ORDER BY created_at ASC
                        LIMIT ?
                    )
                    """.trimIndent(),
                    // 关键事件名先占位，最后是 LIMIT 的溢出条数
                    (criticalArgs().toList() + overflow.toString()).toTypedArray<Any>()
                )
            }
        } catch (e: Exception) {
            AnalyticsLogger.e("EventStore.enforceLimit failed", e)
        }
    }

    /** 清理超过 TTL 的陈旧离线事件 */
    @Synchronized
    fun pruneExpired() {
        try {
            val cutoff = System.currentTimeMillis() - TTL_DAYS * 24 * 60 * 60 * 1000L
            writableDatabase.use { db ->
                db.delete("events", "created_at < ?", arrayOf(cutoff.toString()))
            }
        } catch (e: Exception) {
            AnalyticsLogger.e("EventStore.pruneExpired failed", e)
        }
    }

    /** 用户行使删除权：清空全部本地事件 */
    @Synchronized
    fun clearAll() {
        try {
            writableDatabase.use { db -> db.delete("events", null, null) }
        } catch (e: Exception) {
            AnalyticsLogger.e("EventStore.clearAll failed", e)
        }
    }

    private fun criticalPlaceholders(): String =
        Dictionary.CRITICAL_EVENTS.joinToString(",") { "?" }

    private fun criticalArgs(): Array<String> =
        Dictionary.CRITICAL_EVENTS.toTypedArray()

    private fun Cursor.toRecord(): EventRecord {
        val propsRaw = getString(getColumnIndexOrThrow("properties"))
        val props = mutableMapOf<String, Any?>()
        try {
            val json = JSONObject(propsRaw)
            json.keys().forEach { key -> props[key] = json.opt(key) }
        } catch (e: Exception) {
            AnalyticsLogger.e("Failed to parse properties: $propsRaw", e)
        }

        return EventRecord(
            id = getLong(getColumnIndexOrThrow("id")),
            eventName = getString(getColumnIndexOrThrow("event_name")),
            properties = props,
            clientTimestamp = getLong(getColumnIndexOrThrow("client_timestamp")),
            sessionId = getString(getColumnIndexOrThrow("session_id")),
            retryCount = getInt(getColumnIndexOrThrow("retry_count")),
            createdAt = getLong(getColumnIndexOrThrow("created_at"))
        )
    }

    companion object {
        const val DB_NAME = "bhd_analytics.db"
        const val DB_VERSION = 1
        /** 离线事件有效期：7 天（PRD limits.event_ttl_days） */
        const val TTL_DAYS = 7
    }
}
