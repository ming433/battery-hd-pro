<?php

namespace App\Console\Commands;

use Illuminate\Console\Command;
use Illuminate\Support\Facades\DB;

/**
 * 异常设备识别（PRD 10.4.2 / 验收标准第 10 条）。
 *
 * 埋点一旦被刷量污染，DAU、留存、转化率全线失真，且**事后无法从明细里分辨真伪**，
 * 所以必须在计算环节之前把可疑设备筛出来：核心指标计算时剔除 is_suspect。
 *
 * 两条判定规则：
 *   ① 单设备单日事件量 > P99.9（统计意义上的极端值，不是拍脑袋的固定阈值）
 *   ② 注册超过 N 天但从未产生过任何 T1 核心事件（僵尸/伪造注册）
 *
 * 判定是**可回滚**的：设备恢复正常后下一轮会解除标记，
 * 避免一次误判把真实用户永久排除在报表之外。
 */
class FlagSuspectDevices extends Command
{
    protected $signature = 'analytics:flag-suspect-devices
                            {--days=1 : 统计窗口（天）}
                            {--dry-run : 只输出判定结果，不写库}';

    protected $description = '识别并标记异常设备（is_suspect），用于核心指标防污染';

    public function handle(): int
    {
        $days = max(1, (int) $this->option('days'));
        $dryRun = (bool) $this->option('dry-run');

        $from = now()->subDays($days)->startOfDay();
        $to = now();

        $counts = DB::table('analytics_events')
            ->selectRaw('device_id, COUNT(*) AS cnt')
            ->whereBetween('server_timestamp', [$from, $to])
            ->groupBy('device_id')
            ->pluck('cnt', 'device_id');

        if ($counts->isEmpty()) {
            $this->warn('统计窗口内没有事件，跳过。');

            return self::SUCCESS;
        }

        $threshold = $this->percentile($counts->values()->all(), 99.9);
        $this->line(sprintf('窗口 %s ~ %s：活跃设备 %d，P99.9 阈值 %d 事件/日',
            $from->toDateString(), $to->toDateString(), $counts->count(), $threshold));

        // ① 事件量超 P99.9
        $volumeSuspects = $counts->filter(fn ($cnt) => $cnt > $threshold)->keys()->all();

        // ② 注册超过 N 天但无核心事件
        $coreEvents = $this->coreEventNames();
        $graceDays = (int) config('analytics.monitor.suspect_grace_days', 3);

        $idleSuspects = DB::table('analytics_devices as d')
            ->where('d.first_seen_at', '<', now()->subDays($graceDays))
            ->whereNotExists(function ($q) use ($coreEvents) {
                $q->select(DB::raw(1))
                    ->from('analytics_events as e')
                    ->whereColumn('e.device_id', 'd.id')
                    ->whereIn('e.event_name', $coreEvents);
            })
            ->pluck('d.id')
            ->map(fn ($id) => (int) $id)
            ->all();

        $suspects = array_values(array_unique(array_merge($volumeSuspects, $idleSuspects)));

        $this->line(sprintf('判定结果：事件量异常 %d 台，无核心事件 %d 台，合计 %d 台',
            count($volumeSuspects), count($idleSuspects), count($suspects)));

        if ($dryRun) {
            $this->warn('--dry-run：未写库。');

            return self::SUCCESS;
        }

        if ($suspects !== []) {
            DB::table('analytics_devices')->whereIn('id', $suspects)
                ->update(['is_suspect' => true]);
        }

        // 解除已恢复设备的标记，避免误判永久化
        $cleared = DB::table('analytics_devices')
            ->where('is_suspect', true)
            ->whereNotIn('id', $suspects === [] ? [0] : $suspects)
            ->update(['is_suspect' => false]);

        $this->info(sprintf('已标记 %d 台，解除标记 %d 台。', count($suspects), $cleared));

        return self::SUCCESS;
    }

    /**
     * 线性插值百分位（与 Excel PERCENTILE.INC 同口径）。
     *
     * 用 P99.9 而非固定阈值：事件量的合理范围会随版本与运营活动变化，
     * 写死阈值要么误杀真实重度用户，要么放过刷量。
     */
    private function percentile(array $values, float $p): int
    {
        sort($values, SORT_NUMERIC);
        $n = count($values);
        if ($n === 1) {
            return (int) ceil($values[0]);
        }

        $rank = ($p / 100) * ($n - 1);
        $lower = (int) floor($rank);
        $upper = min($lower + 1, $n - 1);
        $weight = $rank - $lower;

        return (int) ceil($values[$lower] + $weight * ($values[$upper] - $values[$lower]));
    }

    /** T1 核心事件名（PRD 10.2.1：北极星事件，真实用户必然会产生） */
    private function coreEventNames(): array
    {
        return array_keys(array_filter(
            config('analytics.events', []),
            fn ($def) => ($def['tier'] ?? null) === 'T1'
        ));
    }
}
