<?php

namespace App\Jobs;

use Illuminate\Bus\Queueable;
use Illuminate\Contracts\Queue\ShouldQueue;
use Illuminate\Foundation\Bus\Dispatchable;
use Illuminate\Queue\InteractsWithQueue;
use Illuminate\Queue\SerializesModels;
use Illuminate\Support\Facades\DB;

/**
 * 批量落库（PRD 10.4.5）。
 *
 * 按批次分发单个 Job（而非按事件分发，避免队列压力放大 20 倍）：
 *   ├── 批量写入 analytics_events
 *   ├── 更新 analytics_sessions 聚合（服务端为会话边界唯一权威）
 *   ├── 更新设备活跃时间
 *   └── 记录批次去重行（持久化审计）
 */
class ProcessAnalyticsBatch implements ShouldQueue
{
    use Dispatchable, InteractsWithQueue, Queueable, SerializesModels;

    public int $tries = 3;
    public int $timeout = 60;

    /**
     * 批量插入要求每行列结构完全一致：取所有行键的并集，缺失列补 null。
     */
    private function normalizeRows(array $rows): array
    {
        $columns = [];

        foreach ($rows as $row) {
            foreach (array_keys($row) as $key) {
                $columns[$key] = true;
            }
        }

        $columns = array_keys($columns);

        return array_map(function (array $row) use ($columns) {
            $normalized = [];

            foreach ($columns as $column) {
                $normalized[$column] = $row[$column] ?? null;
            }

            return $normalized;
        }, $rows);
    }

    public function __construct(private readonly array $payload) {}

    public function handle(): void
    {
        $events   = $this->payload['events'] ?? [];
        $deviceId = $this->payload['device_id'] ?? null;
        $batchId  = $this->payload['batch_id'] ?? null;

        if ($events === []) {
            return;
        }

        $now = now();
        $rows = [];
        $sessionDelta = [];
        $screenDelta = [];

        foreach ($events as $event) {
            $row = [
                'batch_id'         => $batchId,
                'event_index'      => $event['event_index'] ?? 0,
                'session_id'       => $this->payload['session_id'] ?? null,
                'device_id'        => $deviceId,
                'event_name'       => $event['event_name'],
                'properties'       => json_encode($event['properties'] ?? [], JSON_UNESCAPED_UNICODE),
                'client_timestamp' => $event['client_timestamp'] ?? null,
                'server_timestamp' => $now,
                'app_version'      => $this->payload['app_version'] ?? null,
            ];

            foreach ($event['columns'] ?? [] as $column => $value) {
                $row[$column] = $value;
            }

            $rows[] = $row;

            $sessionId = $this->payload['session_id'] ?? null;
            if ($sessionId) {
                $sessionDelta[$sessionId] = ($sessionDelta[$sessionId] ?? 0) + 1;
                if ($event['event_name'] === 'screen_viewed') {
                    $screenDelta[$sessionId] = ($screenDelta[$sessionId] ?? 0) + 1;
                }
            }
        }

        DB::transaction(function () use ($rows, $batchId, $deviceId, $now, $sessionDelta, $screenDelta) {
            // 1) 批次幂等闸门 —— 必须放在事件入库之前
            //    Job 有 3 次重试，而 analytics_events 上没有唯一键（分区表限制），
            //    若先插事件再去重，任何一次重试都会把整批事件再写一遍，指标系统性虚高。
            //    INSERT IGNORE 返回 0 表示本批已处理过，直接跳过。
            if ($batchId) {
                $claimed = DB::affectingStatement(
                    'INSERT IGNORE INTO analytics_batches
                        (batch_id, device_id, event_count, app_version, received_at)
                     VALUES (?, ?, ?, ?, ?)',
                    [
                        $batchId,
                        $deviceId,
                        count($rows),
                        $this->payload['app_version'] ?? null,
                        $now,
                    ]
                );

                if ($claimed === 0) {
                    return;   // 重复投递（重试/重放），不再写入
                }
            }

            // 2) 事件明细批量入库
            //    注意：不同事件抽出的列不同（如仅广告事件有 placement_id），各行键集合不一致。
            //    直接 insert 会让 MySQL 报 1136（Column count doesn't match value count），
            //    因为批量插入的列清单取自第一行。必须先按并集补齐成统一结构。
            DB::table('analytics_events')->insert($this->normalizeRows($rows));

            // 3) 会话聚合（ON DUPLICATE KEY UPDATE 累加，取首末事件时间）
            foreach ($sessionDelta as $sessionId => $count) {
                DB::statement(
                    'INSERT INTO analytics_sessions
                        (session_id, device_id, started_at, ended_at, screen_count, event_count, app_version, created_at, updated_at)
                     VALUES (?, ?, ?, ?, ?, ?, ?, NOW(), NOW())
                     ON DUPLICATE KEY UPDATE
                        started_at   = LEAST(started_at, VALUES(started_at)),
                        ended_at     = GREATEST(IFNULL(ended_at, VALUES(ended_at)), VALUES(ended_at)),
                        screen_count = screen_count + VALUES(screen_count),
                        event_count  = event_count + VALUES(event_count),
                        updated_at   = NOW()',
                    [
                        $sessionId,
                        $deviceId,
                        $now,
                        $now,
                        $screenDelta[$sessionId] ?? 0,
                        $count,
                        $this->payload['app_version'] ?? null,
                    ]
                );
            }

            // 4) 设备活跃时间
            if ($deviceId) {
                DB::table('analytics_devices')->where('id', $deviceId)->update(['last_seen_at' => $now]);
            }
        });
    }
}
