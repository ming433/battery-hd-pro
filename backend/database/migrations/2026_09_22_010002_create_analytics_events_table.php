<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Support\Facades\DB;

return new class extends Migration
{
    /**
     * 事件明细表（按月 RANGE 分区，保留 13 个月，PRD 10.4.3）。
     *
     * 分区表限制：① 主键必须包含分区键 → PRIMARY KEY (id, server_timestamp)；
     * ② 不支持外键与全文索引 → 设备关联由应用层保证；
     * ③ 末尾保留 pmax 兜底分区，避免无分区可写时报 1526 错误，
     *    每月由 analytics:ensure-partitions 命令 REORGANIZE 出新月分区。
     */
    public function up(): void
    {
        DB::statement(<<<'SQL'
            CREATE TABLE `analytics_events` (
                `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
                `batch_id`         CHAR(36)      NOT NULL,
                `event_index`      SMALLINT UNSIGNED NOT NULL DEFAULT 0,
                `session_id`       CHAR(36)      NULL,
                `device_id`        BIGINT UNSIGNED NULL,
                `event_name`       VARCHAR(64)   NOT NULL,
                `properties`       JSON          NULL,
                `screen_name`      VARCHAR(32)   NULL,
                `placement_id`     VARCHAR(64)   NULL,
                `plan_id`          VARCHAR(32)   NULL,
                `ad_unit_id`       VARCHAR(128)  NULL,
                `client_timestamp` BIGINT        NULL,
                `server_timestamp` DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
                `app_version`      VARCHAR(20)   NULL,
                PRIMARY KEY (`id`, `server_timestamp`),
                KEY `idx_event_time`     (`event_name`, `server_timestamp`),
                KEY `idx_device_session` (`device_id`, `session_id`),
                KEY `idx_placement_time` (`placement_id`, `server_timestamp`),
                KEY `idx_batch`          (`batch_id`)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            PARTITION BY RANGE COLUMNS(`server_timestamp`) (
                PARTITION `pmax` VALUES LESS THAN (MAXVALUE)
            )
        SQL);
    }

    public function down(): void
    {
        DB::statement('DROP TABLE IF EXISTS `analytics_events`');
    }
};
