<?php

namespace App\Console\Commands;

use App\Models\AnalyticsDevice;
use Illuminate\Console\Command;
use Illuminate\Support\Facades\DB;

/**
 * 数据清理与合规任务（PRD 10.4.3 / 10.7.3）。
 *
 *   ① 明细保留 13 个月：按月 DROP 分区（先确认已归档聚合表）
 *   ② analytics_events_rejected 保留 30 天
 *   ③ analytics_batches 保留 30 天
 *   ④ 会话兜底：30 分钟无新事件则补写 ended_at（服务端为会话边界唯一权威）
 *   ⑤ 执行 pending_deletion 设备的数据删除
 */
class PruneAnalytics extends Command
{
    protected $signature = 'analytics:prune';
    protected $description = '清理过期埋点数据、补写会话结束时间、执行数据删除请求';

    public function handle(): int
    {
        // ① 过期分区
        $months = (int) config('analytics.retention.events_months', 13);
        $cutoff = now()->subMonths($months)->startOfMonth();

        $partitions = DB::select(
            'SELECT PARTITION_NAME AS name FROM information_schema.PARTITIONS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?
               AND PARTITION_NAME IS NOT NULL AND PARTITION_NAME <> ?',
            ['analytics_events', 'pmax']
        );

        foreach ($partitions as $partition) {
            $name = $partition->name;
            if (! preg_match('/^p(\d{6})$/', $name, $m)) {
                continue;
            }
            $month = \DateTimeImmutable::createFromFormat('Ymd', $m[1].'01');
            if ($month && $month < $cutoff) {
                DB::statement("ALTER TABLE `analytics_events` DROP PARTITION `{$name}`");
                $this->info("已丢弃过期分区 {$name}");
            }
        }

        // ② 被拒事件
        $rejected = DB::table('analytics_events_rejected')
            ->where('created_at', '<', now()->subDays((int) config('analytics.retention.rejected_days', 30)))
            ->delete();
        $this->line("清理被拒事件 {$rejected} 条");

        // ③ 批次记录
        $batches = DB::table('analytics_batches')
            ->where('received_at', '<', now()->subDays((int) config('analytics.dedupe.batch_ttl_days', 30)))
            ->delete();
        $this->line("清理批次记录 {$batches} 条");

        // ④ 会话兜底：最后一个事件之后 30 分钟无新事件即判定结束。
        //
        // 判定基准必须是「该会话最后一个事件的时间」而不是 ended_at 本身：
        // ended_at 在会话结束时才写入，用 `ended_at IS NULL AND ended_at < ?`
        // 做条件恒为假，会话会永远停在未结束状态，会话时长与跳出率全部失真。
        DB::statement(
            'UPDATE analytics_sessions s
               JOIN (
                    SELECT session_id, MAX(server_timestamp) AS last_at
                      FROM analytics_events
                     GROUP BY session_id
                   ) e ON e.session_id = s.session_id
                SET s.ended_at = DATE_ADD(e.last_at, INTERVAL 30 MINUTE), s.updated_at = NOW()
              WHERE s.ended_at IS NULL
                AND e.last_at < DATE_SUB(NOW(), INTERVAL 30 MINUTE)'
        );

        // ⑤ 数据删除请求
        $devices = AnalyticsDevice::query()
            ->whereNotNull('pending_deletion_at')
            ->where('pending_deletion_at', '<', now())
            ->get();

        foreach ($devices as $device) {
            DB::table('analytics_events')->where('device_id', $device->id)->delete();
            DB::table('analytics_events_rejected')->where('device_id', $device->id)->delete();
            DB::table('analytics_sessions')->where('device_id', $device->id)->delete();
            DB::table('analytics_batches')->where('device_id', $device->id)->delete();
            $device->tokens()->delete();
            $device->delete();

            $this->info("已删除设备 {$device->device_uuid} 的数据");
        }

        return self::SUCCESS;
    }
}
