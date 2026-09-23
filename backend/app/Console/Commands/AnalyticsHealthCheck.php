<?php

namespace App\Console\Commands;

use Illuminate\Console\Command;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Facades\Http;
use Illuminate\Support\Facades\Log;
use Illuminate\Support\Facades\Queue;
use Illuminate\Support\Facades\Redis;

/**
 * 埋点系统健康巡检（PRD 10.4.8）。
 *
 * 这一组指标是埋点系统自身的生命线：它们异常意味着**数据本身已经不可信**，
 * 优先级高于任何业务指标——先看这里，再看业务看板。
 *
 * 用法：
 *   php artisan analytics:health-check          人类可读表格
 *   php artisan analytics:health-check --json   供 Prometheus / 告警机器人采集
 *
 * 告警推送：配置 ANALYTICS_ALERT_WEBHOOK 后，超阈值项会 POST 到该地址（如企业微信机器人）。
 */
class AnalyticsHealthCheck extends Command
{
    protected $signature = 'analytics:health-check {--json : 输出 JSON} {--window=60 : 统计窗口（分钟）}';

    protected $description = '检查埋点系统生命线指标：队列积压、写入失败、429 比例、拒收、可疑设备占比';

    public function handle(): int
    {
        $window = max(1, (int) $this->option('window'));
        $since = now()->subMinutes($window);

        $checks = [
            ...$this->queueChecks(),
            ...$this->rejectChecks($since),
            ...$this->throttleChecks(),
            ...$this->volumeChecks($since),
            ...$this->suspectChecks(),
        ];

        $alerts = array_values(array_filter($checks, fn ($c) => $c['alert']));

        if ($this->option('json')) {
            $this->line(json_encode([
                'checked_at' => now()->toIso8601String(),
                'window_min' => $window,
                'checks'     => $checks,
                'alerting'   => $alerts,
                'healthy'    => $alerts === [],
            ], JSON_UNESCAPED_UNICODE | JSON_PRETTY_PRINT));
        } else {
            $this->table(
                ['指标', '当前值', '阈值', '状态'],
                array_map(fn ($c) => [
                    $c['name'],
                    (string) $c['value'],
                    (string) $c['threshold'],
                    $c['alert'] ? '<fg=red>告警</>' : '<fg=green>正常</>',
                ], $checks)
            );

            if ($alerts !== []) {
                $this->error(sprintf('%d 项指标超阈值', count($alerts)));
            } else {
                $this->info('全部指标正常');
            }
        }

        if ($alerts !== []) {
            $this->notify($alerts);
        }

        return self::SUCCESS;
    }

    // ------------------------------------------------------------ 队列

    private function queueChecks(): array
    {
        $backlog = $this->queueBacklog();
        $failed = $this->failedJobs(60);

        return [
            [
                'name'      => '队列积压（analytics）',
                'value'     => $backlog,
                'threshold' => config('analytics.monitor.max_queue_backlog', 1000),
                'alert'     => $backlog > (int) config('analytics.monitor.max_queue_backlog', 1000),
            ],
            [
                'name'      => '近 1h 批量写入失败',
                'value'     => $failed,
                'threshold' => config('analytics.monitor.max_failed_jobs', 10),
                'alert'     => $failed > (int) config('analytics.monitor.max_failed_jobs', 10),
            ],
        ];
    }

    private function queueBacklog(): int
    {
        try {
            return (int) Queue::size('analytics');
        } catch (\Throwable) {
            return -1;
        }
    }

    private function failedJobs(int $minutes): int
    {
        try {
            return DB::table('failed_jobs')
                ->where('failed_at', '>=', now()->subMinutes($minutes))
                ->count();
        } catch (\Throwable) {
            // failed_jobs 表缺失时不要让巡检整体失败（真实异常会被二次错误掩盖）
            return -1;
        }
    }

    // ------------------------------------------------------------ 拒收

    private function rejectChecks(\Carbon\CarbonInterface $since): array
    {
        $rows = DB::table('analytics_events_rejected')
            ->selectRaw('reason, COUNT(*) AS cnt')
            ->where('created_at', '>=', $since)
            ->groupBy('reason')
            ->pluck('cnt', 'reason');

        $get = fn (string $r) => (int) ($rows[$r] ?? 0);

        return [
            [
                'name'      => '未知事件数',
                'value'     => $get('unknown_event'),
                'threshold' => config('analytics.monitor.max_unknown_events', 0),
                'alert'     => $get('unknown_event') > (int) config('analytics.monitor.max_unknown_events', 0),
            ],
            [
                'name'      => '时钟偏移异常数',
                'value'     => $get('clock_skew'),
                'threshold' => config('analytics.monitor.max_clock_skew', 50),
                'alert'     => $get('clock_skew') > (int) config('analytics.monitor.max_clock_skew', 50),
            ],
            [
                'name'      => '枚举违规数',
                'value'     => $get('enum_violation'),
                'threshold' => config('analytics.monitor.max_enum_violations', 100),
                'alert'     => $get('enum_violation') > (int) config('analytics.monitor.max_enum_violations', 100),
            ],
        ];
    }

    // ----------------------------------------------------------- 429

    private function throttleChecks(): array
    {
        $ratio = 0.0;

        try {
            $key = 'bhd:metrics:'.now()->format('Ymd');
            $raw = Redis::hgetall($key);
            $throttled = (int) ($raw['throttled_429'] ?? 0);
            $ok = (int) ($raw['requests_ok'] ?? 0);
            $total = $throttled + $ok;
            $ratio = $total > 0 ? round($throttled / $total * 100, 3) : 0.0;
        } catch (\Throwable) {
            // Redis 不可用时视为无法判定，不误报
        }

        $max = (float) config('analytics.monitor.max_throttle_rate', 1.0);

        return [[
            'name'      => '429 比例（%）',
            'value'     => $ratio,
            'threshold' => $max,
            'alert'     => $ratio > $max,
        ]];
    }

    // -------------------------------------------------------- 事件量异常

    private function volumeChecks(\Carbon\CarbonInterface $since): array
    {
        $top = DB::table('analytics_events')
            ->selectRaw('device_id, COUNT(*) AS cnt')
            ->where('server_timestamp', '>=', $since)
            ->groupBy('device_id')
            ->orderByDesc('cnt')
            ->first();

        $max = (int) config('analytics.monitor.max_events_per_device_per_hour', 2000);
        $value = (int) ($top->cnt ?? 0);

        return [[
            'name'      => '单设备峰值事件量',
            'value'     => $value,
            'threshold' => $max,
            'alert'     => $value > $max,
        ]];
    }

    // ------------------------------------------------------ 可疑设备

    private function suspectChecks(): array
    {
        $total = (int) DB::table('analytics_devices')->count();
        $suspect = (int) DB::table('analytics_devices')->where('is_suspect', true)->count();
        $rate = $total > 0 ? round($suspect / $total * 100, 3) : 0.0;
        $max = (float) config('analytics.monitor.max_suspect_rate', 5.0);

        return [[
            'name'      => '可疑设备占比（%）',
            'value'     => $rate,
            'threshold' => $max,
            'alert'     => $rate > $max,
        ]];
    }

    // ------------------------------------------------------------ 告警

    private function notify(array $alerts): void
    {
        $lines = array_map(
            fn ($c) => sprintf('• %s：%s（阈值 %s）', $c['name'], $c['value'], $c['threshold']),
            $alerts
        );

        Log::warning('[analytics-health] '.implode(' | ', $lines));

        $webhook = config('analytics.monitor.alert_webhook');

        if (! $webhook) {
            return;
        }

        try {
            Http::timeout(5)->post($webhook, [
                'msgtype' => 'text',
                'text'    => ['content' => "[BatteryHD 埋点告警]\n".implode("\n", $lines)],
            ]);
        } catch (\Throwable $e) {
            report($e);
        }
    }
}
