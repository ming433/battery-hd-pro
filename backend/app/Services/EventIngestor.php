<?php

namespace App\Services;

use App\Jobs\ProcessAnalyticsBatch;
use App\Models\AnalyticsEventRejected;
use Illuminate\Support\Facades\Redis;

/**
 * 事件上报处理（PRD 10.4.5 / 10.4.6）。
 *
 * 链路：API 鉴权 → 去重 → 校验 → 投递 Job（Redis 队列）→ Worker 批量落库。
 * API 侧不写事件明细表，保障 P95 ≤ 200ms。
 */
class EventIngestor
{
    public function __construct(private readonly EventDictionary $dictionary) {}

    /**
     * 批次幂等判定。返回 true 表示"新批次"，false 表示"重复批次"。
     */
    public function claimBatch(string $batchId): bool
    {
        if (config('analytics.dedupe.driver') === 'database') {
            return \Illuminate\Support\Facades\DB::insert(
                'INSERT IGNORE INTO analytics_batches (batch_id, event_count, received_at) VALUES (?, 0, NOW())',
                [$batchId]
            ) > 0;
        }

        $key = 'bhd:batch:'.$batchId;

        return (bool) Redis::set($key, 1, 'EX', config('analytics.dedupe.redis_ttl', 86400), 'NX');
    }

    /**
     * 校验事件列表。返回 [accepted[], rejected[]]。
     * accepted 项结构：['event_name' => ..., 'properties' => [...], 'columns' => [...], 'client_timestamp' => ...]
     */
    public function validate(array $events, ?int $deviceId, ?string $batchId): array
    {
        $accepted = [];
        $rejected = [];

        $skewSeconds = config('analytics.limits.clock_skew_hours', 24) * 3600;
        $now = now()->timestamp;

        foreach ($events as $index => $event) {
            $name = (string) ($event['event_name'] ?? '');

            if (! $this->dictionary->isKnown($name)) {
                $rejected[] = $this->reject($batchId, $deviceId, $name, 'unknown_event', $event);
                continue;
            }

            $ts = isset($event['timestamp']) ? (int) $event['timestamp'] : null;
            // 客户端时间戳为毫秒
            $tsSeconds = $ts ? intdiv($ts, 1000) : null;

            if ($tsSeconds !== null && abs($tsSeconds - $now) > $skewSeconds) {
                $rejected[] = $this->reject($batchId, $deviceId, $name, 'clock_skew', $event);
                continue;
            }

            $raw = is_array($event['properties'] ?? null) ? $event['properties'] : [];
            [$properties, $stripped] = $this->dictionary->filterProperties($name, $raw);

            // 枚举与类型校验（PRD 10.2.5）：违规值不污染仓库
            [$properties, $violations] = $this->dictionary->validateProperties($name, $properties);

            if ($violations !== []) {
                if (config('analytics.enum_strict', true)) {
                    $rejected[] = $this->reject(
                        $batchId, $deviceId, $name, 'enum_violation',
                        array_merge($event, ['_violations' => $violations])
                    );
                    continue;
                }

                // 降级模式：剔除违规属性后保留事件主体，仅告警
                $stripped = array_merge($stripped, array_column($violations, 'prop'));
            }

            $accepted[] = [
                'event_name'       => $name,
                'event_index'      => $index,
                'properties'       => $properties,
                'columns'          => $this->dictionary->extractColumns($name, $properties),
                'client_timestamp' => $ts,
                'stripped'         => $stripped,
                'violations'       => $violations,
            ];
        }

        if ($rejected !== []) {
            AnalyticsEventRejected::insert($rejected);
        }

        $this->recordViolations($rejected);

        return [$accepted, $rejected];
    }

    /**
     * 违规计数（PRD 10.4.8）。
     *
     * 按维度写入 Redis 计数器供监控采集：
     *   bhd:violation:{reason}:{prop}  当日累计次数（次日零点过期）
     *
     * 典型告警规则：ad_load_failed 的 error_code=1/8（配置错误）突增 → 立即排查后台配置。
     * 监控不可用不影响主流程，故静默吞掉异常。
     */
    protected function recordViolations(array $rejected): void
    {
        if ($rejected === []) {
            return;
        }

        try {
            $key = 'bhd:violation:'.now()->format('Ymd');
            $pipe = Redis::pipeline();

            foreach ($rejected as $item) {
                $reason = $item['reason'] ?? 'unknown';
                $pipe->hincrby($key, $reason, 1);

                $event = $item['event_name'] ?? 'unknown';
                $pipe->hincrby($key.':event', $event.'|'.$reason, 1);
            }

            // 次日零点 + 1h 过期，保留一天余量供排查
            $pipe->expireat($key, now()->endOfDay()->addHour()->timestamp);
            $pipe->expireat($key.':event', now()->endOfDay()->addHour()->timestamp);
            $pipe->execute();
        } catch (\Throwable $e) {
            report($e);
        }
    }

    private function reject(?string $batchId, ?int $deviceId, string $name, string $reason, mixed $payload): array
    {
        return [
            'batch_id'    => $batchId,
            'device_id'   => $deviceId,
            'event_name'  => $name !== '' ? $name : null,
            'reason'      => $reason,
            'payload'     => json_encode(is_array($payload) ? $payload : [], JSON_UNESCAPED_UNICODE),
            'created_at'  => now(),
        ];
    }

    /**
     * 投递批量处理任务（按批次分发，而非按事件，PRD 10.4.5）。
     */
    public function dispatch(array $payload): void
    {
        ProcessAnalyticsBatch::dispatch($payload)->onQueue('analytics');
    }
}
