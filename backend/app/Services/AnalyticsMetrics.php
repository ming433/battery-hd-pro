<?php

namespace App\Services;

use Carbon\CarbonInterface;
use Illuminate\Support\Collection;
use Illuminate\Support\Facades\DB;

/**
 * 数据分析看板聚合（PRD 10.6）。
 *
 * ── 查询性能 ────────────────────────────────────────────────
 * `analytics_events` 是按 `server_timestamp` 做 RANGE 分区的大表。
 * 所有查询**必须**带时间范围条件，否则会扫全部分区（PRD 10.4.3）。
 * 本类每个方法都强制传入 $from / $to，就是为了杜绝无裁剪的查询。
 *
 * ── 口径说明 ────────────────────────────────────────────────
 * 口径与 PRD 10.6 逐条对齐，尤其两处易错点：
 *   ① DAU 以「当日任一事件」计活跃，不是只有 app_launched 才算
 *      （否则仅后台恢复的用户会被漏计）；
 *   ② eCPM 的 revenue_micros 是百万分之一货币单位，必须 ÷ 10⁶。
 */
class AnalyticsMetrics
{
    /** 展示型事件（填充率分子、eCPM 分母） */
    private const IMPRESSION_EVENTS = ['banner_shown', 'interstitial_shown'];

    /** 转化漏斗顺序（PRD 10.6：拦截曝光 → Pro 浏览 → 发起 → 完成） */
    private const FUNNEL = [
        'feature_gate_shown',
        'pro_page_viewed',
        'purchase_initiated',
        'purchase_completed',
    ];

    // ---------------------------------------------------- 活跃与留存

    /**
     * 日活：以 device_id 去重，当日任一事件即计为活跃。
     *
     * @return Collection<int, array{date: string, dau: int}>
     */
    public function dailyActiveUsers(CarbonInterface $from, CarbonInterface $to): Collection
    {
        return DB::table('analytics_events')
            ->selectRaw('DATE(server_timestamp) AS date, COUNT(DISTINCT device_id) AS dau')
            ->whereBetween('server_timestamp', [$from, $to])
            ->groupByRaw('DATE(server_timestamp)')
            ->orderBy('date')
            ->get();
    }

    /** 当前 MAU（滚动 30 天去重设备数） */
    public function monthlyActiveUsers(CarbonInterface $from, CarbonInterface $to): int
    {
        return (int) DB::table('analytics_events')
            ->whereBetween('server_timestamp', [$from, $to])
            ->distinct()
            ->count('device_id');
    }

    /** 新增设备数（按首见日分群），留存的分母 */
    public function newDevices(CarbonInterface $from, CarbonInterface $to): Collection
    {
        return DB::table('analytics_devices')
            ->selectRaw('DATE(first_seen_at) AS date, COUNT(*) AS total')
            ->whereBetween('first_seen_at', [$from, $to])
            ->groupByRaw('DATE(first_seen_at)')
            ->orderBy('date')
            ->get();
    }

    /**
     * 留存：按 first_seen_at 分群，统计第 N 日仍活跃的设备占比。
     *
     * @param int $offsetDay 1=次日，7=7日，30=30日
     * @return Collection<int, array{cohort: string, cohort_size: int, retained: int, rate: float}>
     */
    public function retention(CarbonInterface $from, CarbonInterface $to, int $offsetDay): Collection
    {
        // 用 DATE_ADD 做日期偏移，避免在 PHP 侧拼日期字符串导致时区不一致
        $rows = DB::table('analytics_devices as d')
            ->selectRaw(
                'DATE(d.first_seen_at) AS cohort,
                 COUNT(DISTINCT d.id) AS cohort_size,
                 COUNT(DISTINCT e.device_id) AS retained'
            )
            ->leftJoin('analytics_events as e', function ($join) use ($offsetDay) {
                $join->on('e.device_id', '=', 'd.id')
                    ->whereRaw(
                        'e.server_timestamp >= DATE_ADD(DATE(d.first_seen_at), INTERVAL ? DAY)
                         AND e.server_timestamp <  DATE_ADD(DATE(d.first_seen_at), INTERVAL ? DAY)',
                        [$offsetDay, $offsetDay + 1]
                    );
            })
            ->whereBetween('d.first_seen_at', [$from, $to])
            ->groupByRaw('DATE(d.first_seen_at)')
            ->orderBy('cohort')
            ->get();

        return $rows->map(function ($row) {
            $size = (int) $row->cohort_size;

            return [
                'cohort'      => $row->cohort,
                'cohort_size' => $size,
                'retained'    => (int) $row->retained,
                'rate'        => $size > 0 ? round(((int) $row->retained) / $size * 100, 2) : 0.0,
            ];
        });
    }

    // ------------------------------------------------------ 功能使用

    /**
     * 各功能使用率：事件去重设备数 / 区间活跃设备数。
     *
     * @return Collection<int, array{event_name: string, uv: int, pv: int, usage_rate: float}>
     */
    public function featureUsage(CarbonInterface $from, CarbonInterface $to): Collection
    {
        $active = $this->activeDevices($from, $to);

        $rows = DB::table('analytics_events')
            ->selectRaw('event_name, COUNT(DISTINCT device_id) AS uv, COUNT(*) AS pv')
            ->whereBetween('server_timestamp', [$from, $to])
            ->groupBy('event_name')
            ->orderByDesc('uv')
            ->get();

        return $rows->map(fn ($r) => [
            'event_name' => $r->event_name,
            'uv'         => (int) $r->uv,
            'pv'         => (int) $r->pv,
            'usage_rate' => $active > 0 ? round(((int) $r->uv) / $active * 100, 2) : 0.0,
        ]);
    }

    /**
     * 页面停留时长（数据来源：screen_exited.duration_ms）。
     *
     * @return Collection<int, array{screen_name: string, avg_ms: float, views: int}>
     */
    public function screenDwell(CarbonInterface $from, CarbonInterface $to): Collection
    {
        return DB::table('analytics_events')
            ->selectRaw(
                "screen_name,
                 AVG(CAST(properties->>'\$.duration_ms' AS DECIMAL(14,2))) AS avg_ms,
                 COUNT(*) AS views"
            )
            ->where('event_name', 'screen_exited')
            ->whereBetween('server_timestamp', [$from, $to])
            ->whereNotNull('screen_name')
            ->groupBy('screen_name')
            ->orderByDesc('views')
            ->get()
            ->map(fn ($r) => [
                'screen_name' => $r->screen_name,
                'avg_ms'      => round((float) $r->avg_ms, 0),
                'views'       => (int) $r->views,
            ]);
    }

    // -------------------------------------------------------- 充电行为

    /**
     * 充电行为：限值触发次数、温度警报次数、平均充电时长。
     *
     * @return array{limit_triggers: int, temp_alerts: int, sessions: int, avg_duration_ms: float}
     */
    public function charging(CarbonInterface $from, CarbonInterface $to): array
    {
        $counts = DB::table('analytics_events')
            ->selectRaw(
                "SUM(event_name = 'charge_limit_triggered') AS limit_triggers,
                 SUM(event_name = 'temp_alert_triggered')    AS temp_alerts,
                 SUM(event_name = 'charge_session_ended')   AS sessions"
            )
            ->whereIn('event_name', ['charge_limit_triggered', 'temp_alert_triggered', 'charge_session_ended'])
            ->whereBetween('server_timestamp', [$from, $to])
            ->first();

        $avg = DB::table('analytics_events')
            ->selectRaw("AVG(CAST(properties->>'\$.duration_ms' AS DECIMAL(14,2))) AS avg_ms")
            ->where('event_name', 'charge_session_ended')
            ->whereBetween('server_timestamp', [$from, $to])
            ->value('avg_ms');

        return [
            'limit_triggers'   => (int) ($counts->limit_triggers ?? 0),
            'temp_alerts'      => (int) ($counts->temp_alerts ?? 0),
            'sessions'         => (int) ($counts->sessions ?? 0),
            'avg_duration_ms'  => round((float) $avg, 0),
        ];
    }

    // ------------------------------------------------------ 广告表现

    /**
     * 广告表现：按 placement_id 聚合展示量、点击率、填充率、eCPM。
     *
     * 口径（PRD 10.6）：
     *   填充率 = banner_shown / ad_requested
     *   eCPM   = (Σ revenue_micros ÷ 1,000,000) ÷ 展示量 × 1000
     *   ARPU   = 区间总收入 ÷ 活跃设备数
     *
     * 注意：`revenue_micros` 单位是百万分之一货币单位，漏掉 ÷10⁶ 会让
     * eCPM 放大 100 万倍（PRD 附录 B 记录的初版缺陷）。
     *
     * @return array{rows: Collection, totals: array}
     */
    public function ads(CarbonInterface $from, CarbonInterface $to): array
    {
        $events = array_merge(
            ['ad_requested', 'banner_clicked', 'ad_revenue_paid', 'ad_load_failed'],
            self::IMPRESSION_EVENTS
        );

        $rows = DB::table('analytics_events')
            ->selectRaw(
                "placement_id,
                 SUM(event_name = 'ad_requested')        AS requests,
                 SUM(event_name = 'banner_shown')        AS banners,
                 SUM(event_name = 'interstitial_shown')  AS interstitials,
                 SUM(event_name = 'banner_clicked')      AS clicks,
                 SUM(event_name = 'ad_load_failed')      AS failures,
                 SUM(CASE WHEN event_name = 'ad_revenue_paid'
                          THEN CAST(properties->>'\$.revenue_micros' AS DECIMAL(20,2))
                          ELSE 0 END)                    AS revenue_micros"
            )
            ->whereIn('event_name', $events)
            ->whereBetween('server_timestamp', [$from, $to])
            ->groupBy('placement_id')
            ->orderByDesc('requests')
            ->get();

        $mapped = $rows->map(function ($r) {
            $requests = (int) $r->requests;
            $impressions = (int) $r->banners + (int) $r->interstitials;
            // PRD 明文口径：填充率用 banner_shown / ad_requested
            $fills = (int) $r->banners;
            $revenue = ((float) $r->revenue_micros) / 1_000_000;

            return [
                'placement_id' => $r->placement_id ?: '(未标记)',
                'requests'     => $requests,
                'impressions'  => $impressions,
                'clicks'       => (int) $r->clicks,
                'failures'     => (int) $r->failures,
                'revenue'      => round($revenue, 4),
                'fill_rate'    => $requests > 0 ? round($fills / $requests * 100, 2) : 0.0,
                'ctr'          => $impressions > 0 ? round(((int) $r->clicks) / $impressions * 100, 3) : 0.0,
                'ecpm'         => $impressions > 0 ? round($revenue / $impressions * 1000, 4) : 0.0,
                'fail_rate'    => $requests > 0 ? round(((int) $r->failures) / $requests * 100, 2) : 0.0,
            ];
        });

        $totalRevenue = $mapped->sum('revenue');
        $totalImpressions = $mapped->sum('impressions');
        $active = $this->activeDevices($from, $to);

        return [
            'rows'   => $mapped,
            'totals' => [
                'requests'    => $mapped->sum('requests'),
                'impressions' => $totalImpressions,
                'clicks'      => $mapped->sum('clicks'),
                'revenue'     => round($totalRevenue, 4),
                'ecpm'        => $totalImpressions > 0
                    ? round($totalRevenue / $totalImpressions * 1000, 4) : 0.0,
                'arpu'        => $active > 0 ? round($totalRevenue / $active, 6) : 0.0,
                'active'      => $active,
            ],
        ];
    }

    // ------------------------------------------------------ 转化漏斗

    /**
     * 订阅转化漏斗。收入与订阅状态以 RTDN 为准，此处仅统计行为漏斗。
     *
     * @return array{steps: array, overall_rate: float}
     */
    public function funnel(CarbonInterface $from, CarbonInterface $to): array
    {
        $counts = DB::table('analytics_events')
            ->selectRaw('event_name, COUNT(DISTINCT device_id) AS uv')
            ->whereIn('event_name', self::FUNNEL)
            ->whereBetween('server_timestamp', [$from, $to])
            ->groupBy('event_name')
            ->pluck('uv', 'event_name');

        $steps = [];
        $previous = null;

        foreach (self::FUNNEL as $i => $name) {
            $uv = (int) ($counts[$name] ?? 0);
            $entry = $i === 0 ? $uv : ($counts[self::FUNNEL[0]] ?? 0);

            $steps[] = [
                'event_name' => $name,
                'uv'         => $uv,
                // 单步转化率相对上一环节；漏斗断裂点一眼可见
                'step_rate'  => $previous !== null && $previous > 0
                    ? round($uv / $previous * 100, 2) : 100.0,
                'total_rate' => $entry > 0 ? round($uv / $entry * 100, 2) : 0.0,
            ];
            $previous = $uv;
        }

        $first = $steps[0]['uv'] ?? 0;
        $last = $steps[count($steps) - 1]['uv'] ?? 0;

        return [
            'steps'        => $steps,
            'overall_rate' => $first > 0 ? round($last / $first * 100, 3) : 0.0,
        ];
    }

    // -------------------------------------------------------- 异常监控

    /**
     * 异常监控：权限拒绝率、功能不可用率、广告失败率、配置异常率。
     *
     * 权限拒绝率的分母是 permission_requested（PRD 10.2.4 C 特别标注该事件
     * 虽为 T2 但不参与采样，否则拒绝率会因采样而失真）。
     */
    public function anomalies(CarbonInterface $from, CarbonInterface $to): array
    {
        $rows = DB::table('analytics_events')
            ->selectRaw('event_name, COUNT(*) AS cnt, COUNT(DISTINCT device_id) AS uv')
            ->whereIn('event_name', [
                'permission_requested', 'permission_denied',
                'feature_unavailable',
                'ad_requested', 'ad_load_failed',
                'ad_config_fetched', 'ad_config_invalid',
                'ad_circuit_breaker_tripped',
            ])
            ->whereBetween('server_timestamp', [$from, $to])
            ->groupBy('event_name')
            ->pluck('cnt', 'event_name');

        $get = fn (string $n) => (int) ($rows[$n] ?? 0);

        $ratio = fn (int $num, int $den) => $den > 0 ? round($num / $den * 100, 2) : 0.0;

        return [
            'permission_requested'     => $get('permission_requested'),
            'permission_denied'        => $get('permission_denied'),
            'permission_deny_rate'     => $ratio($get('permission_denied'), $get('permission_requested')),
            'feature_unavailable'      => $get('feature_unavailable'),
            'ad_requests'              => $get('ad_requested'),
            'ad_load_failed'           => $get('ad_load_failed'),
            'ad_fail_rate'             => $ratio($get('ad_load_failed'), $get('ad_requested')),
            'ad_config_fetched'        => $get('ad_config_fetched'),
            'ad_config_invalid'        => $get('ad_config_invalid'),
            'ad_config_invalid_rate'   => $ratio($get('ad_config_invalid'), $get('ad_config_fetched')),
            'circuit_breaker_tripped'  => $get('ad_circuit_breaker_tripped'),
        ];
    }

    // ------------------------------------------------------ 系统健康度

    /**
     * 埋点系统自身生命线指标（PRD 10.4.8）。
     *
     * 与业务看板分开：这些指标异常意味着**数据本身不可信**，
     * 必须优先于业务指标排查。
     */
    public function health(CarbonInterface $from, CarbonInterface $to): array
    {
        $rejected = DB::table('analytics_events_rejected')
            ->selectRaw('reason, COUNT(*) AS cnt')
            ->whereBetween('created_at', [$from, $to])
            ->groupBy('reason')
            ->pluck('cnt', 'reason');

        $totalEvents = (int) DB::table('analytics_events')
            ->whereBetween('server_timestamp', [$from, $to])
            ->count();

        $rejectedTotal = (int) $rejected->sum();
        $devices = (int) DB::table('analytics_devices')->count();
        $suspect = (int) DB::table('analytics_devices')->where('is_suspect', true)->count();

        // 单设备事件量 P99.9：用于识别刷量设备
        $p999 = DB::table('analytics_events')
            ->selectRaw('device_id, COUNT(*) AS cnt')
            ->whereBetween('server_timestamp', [$from, $to])
            ->groupBy('device_id')
            ->orderByDesc('cnt')
            ->limit(1000)
            ->get();

        return [
            'events_total'      => $totalEvents,
            'rejected_total'    => $rejectedTotal,
            'reject_rate'       => $totalEvents + $rejectedTotal > 0
                ? round($rejectedTotal / ($totalEvents + $rejectedTotal) * 100, 3) : 0.0,
            'reject_reasons'    => $rejected,
            'unknown_events'    => (int) ($rejected['unknown_event'] ?? 0),
            'clock_skew'        => (int) ($rejected['clock_skew'] ?? 0),
            'enum_violations'   => (int) ($rejected['enum_violation'] ?? 0),
            'devices_total'     => $devices,
            'suspect_devices'   => $suspect,
            'suspect_rate'      => $devices > 0 ? round($suspect / $devices * 100, 3) : 0.0,
            'top_devices'       => $p999->take(10),
        ];
    }

    // ---------------------------------------------------------- 内部

    private function activeDevices(CarbonInterface $from, CarbonInterface $to): int
    {
        return (int) DB::table('analytics_events')
            ->whereBetween('server_timestamp', [$from, $to])
            ->distinct()
            ->count('device_id');
    }
}
