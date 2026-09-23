<?php

namespace App\Http\Controllers\Admin;

use App\Http\Controllers\Controller;
use App\Services\AnalyticsMetrics;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\Cache;
use Illuminate\View\View;

/**
 * 数据分析看板（PRD 10.6）。
 *
 * 6 大看板：活跃留存 / 功能使用 / 充电行为 / 广告表现 / 转化漏斗 / 异常监控，
 * 外加埋点系统自身健康度（10.4.8）——健康度异常时业务指标不可信，故放在最上方。
 *
 * 初期数据量下直接查明细表即可；量级上来后应由 Metabase 或离线聚合表承担，
 * 这里对每段查询加了短缓存，避免运营反复刷新把数据库拖垮。
 */
class AnalyticsDashboardController extends Controller
{
    public function __construct(private readonly AnalyticsMetrics $metrics) {}

    public function index(Request $request): View
    {
        $days = (int) $request->input('days', 7);
        $days = max(1, min(90, $days));

        $to = now()->endOfDay();
        $from = now()->startOfDay()->subDays($days - 1);

        // 留存需要更长的观察窗口： cohort 起点与活跃窗口分开
        $cohortTo = now()->startOfDay()->subDays(30);
        $cohortFrom = $cohortTo->copy()->subDays($days - 1);

        $cacheKey = sprintf('bhd:metrics:%s:%d', $from->format('Ymd'), $days);

        $data = Cache::remember($cacheKey, 60, fn () => [
            'health'   => $this->metrics->health($from, $to),
            'dau'      => $this->metrics->dailyActiveUsers($from, $to),
            'mau'      => $this->metrics->monthlyActiveUsers(now()->subDays(30), $to),
            'd1'       => $this->metrics->retention($cohortFrom, $cohortTo, 1),
            'd7'       => $this->metrics->retention($cohortFrom, $cohortTo, 7),
            'd30'      => $this->metrics->retention($cohortFrom, $cohortTo, 30),
            'features' => $this->metrics->featureUsage($from, $to),
            'dwell'    => $this->metrics->screenDwell($from, $to),
            'charging' => $this->metrics->charging($from, $to),
            'ads'      => $this->metrics->ads($from, $to),
            'funnel'   => $this->metrics->funnel($from, $to),
            'anomalies'=> $this->metrics->anomalies($from, $to),
        ]);

        return view('admin.analytics.index', array_merge($data, [
            'days' => $days,
            'from' => $from,
            'to'   => $to,
        ]));
    }
}
