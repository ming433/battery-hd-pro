<?php

namespace App\Console\Commands;

use Illuminate\Console\Command;
use Illuminate\Support\Facades\DB;

/**
 * 预建事件表月分区（PRD 10.4.3）。
 *
 * 建表时仅有一个 pmax 兜底分区，本命令按月从 pmax 中 REORGANIZE 出新月分区，
 * 保证写入永远落在具体分区（便于按月 DROP 归档）。
 */
class EnsureAnalyticsPartitions extends Command
{
    protected $signature = 'analytics:ensure-partitions {--months=3}';
    protected $description = '为 analytics_events 预建未来若干个月的分区';

    public function handle(): int
    {
        $months = max(1, (int) $this->option('months'));
        $start = now()->startOfMonth();

        for ($i = 0; $i < $months; $i++) {
            $month = $start->copy()->addMonths($i);
            $name = bhd_partition_name($month);
            $boundary = $month->copy()->addMonth()->format('Y-m-d');

            $exists = DB::selectOne(
                'SELECT PARTITION_NAME AS name FROM information_schema.PARTITIONS
                 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND PARTITION_NAME = ? LIMIT 1',
                ['analytics_events', $name]
            );

            if ($exists) {
                $this->line("分区 {$name} 已存在，跳过");
                continue;
            }

            DB::statement(
                "ALTER TABLE `analytics_events` REORGANIZE PARTITION `pmax` INTO (
                    PARTITION `{$name}` VALUES LESS THAN ('{$boundary}'),
                    PARTITION `pmax`    VALUES LESS THAN (MAXVALUE)
                )"
            );

            $this->info("已创建分区 {$name}（上界 {$boundary}）");
        }

        return self::SUCCESS;
    }
}
