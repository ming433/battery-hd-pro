@extends('admin.layouts.app')

@section('title', '数据分析看板')

@php
    // 整体留存率：跨 cohort 汇总后计算（单日 cohort 样本太小时波动极大）
    $sumRate = function ($rows) {
        $size = $rows->sum('cohort_size');
        $retained = $rows->sum('retained');
        return $size > 0 ? round($retained / $size * 100, 2) : 0.0;
    };
    $fmtMs = function (?float $ms) {
        if (! $ms) return '—';
        $s = (int) round($ms / 1000);
        return $s >= 3600
            ? sprintf('%dh %dm', intdiv($s, 3600), intdiv($s % 3600, 60))
            : sprintf('%dm %ds', intdiv($s, 60), $s % 60);
    };
@endphp

@section('content')
<div class="mb-5 flex items-center justify-between">
    <div>
        <h1 class="text-xl font-semibold">数据分析看板</h1>
        <p class="text-sm text-slate-500 mt-1">
            {{ $from->format('Y-m-d') }} ~ {{ $to->format('Y-m-d') }} · 口径见 PRD 10.6
        </p>
    </div>
    <form method="GET" class="flex items-center gap-2 text-sm">
        <label for="days" class="text-slate-600">区间</label>
        <select id="days" name="days" onchange="this.form.submit()"
                class="border border-slate-300 rounded px-2 py-1">
            @foreach ([1 => '今日', 7 => '近 7 天', 14 => '近 14 天', 30 => '近 30 天', 90 => '近 90 天'] as $v => $label)
                <option value="{{ $v }}" @selected($days === $v)>{{ $label }}</option>
            @endforeach
        </select>
    </form>
</div>

{{-- ── 系统健康度：异常时业务数据不可信，必须置顶 ───────────────────── --}}
<section class="mb-8">
    <h2 class="text-base font-semibold mb-2">埋点系统健康度 <span class="text-xs font-normal text-slate-500">PRD 10.4.8</span></h2>
    <div class="grid grid-cols-2 md:grid-cols-4 gap-3">
        <x-metric-card label="事件总量" :value="number_format($health['events_total'])" />
        <x-metric-card label="拒收率" :value="$health['reject_rate'].'%'" :warn="$health['reject_rate'] > 1" />
        <x-metric-card label="未知事件" :value="$health['unknown_events']" :warn="$health['unknown_events'] > 0" />
        <x-metric-card label="时钟偏移异常" :value="$health['clock_skew']" :warn="$health['clock_skew'] > 0" />
        <x-metric-card label="枚举违规" :value="$health['enum_violations']" :warn="$health['enum_violations'] > 0" />
        <x-metric-card label="设备总数" :value="number_format($health['devices_total'])" />
        <x-metric-card label="可疑设备占比" :value="$health['suspect_rate'].'%'" :warn="$health['suspect_rate'] > 1" />
        <x-metric-card label="熔断次数" :value="$anomalies['circuit_breaker_tripped']" />
    </div>
</section>

{{-- ── 1. 活跃与留存 ─────────────────────────────────────────────── --}}
<section class="mb-8">
    <h2 class="text-base font-semibold mb-2">DAU / MAU 与留存</h2>
    <div class="grid grid-cols-2 md:grid-cols-4 gap-3 mb-3">
        <x-metric-card label="今日 DAU" :value="number_format($dau->last()->dau ?? 0)" />
        <x-metric-card label="30 日 MAU" :value="number_format($mau)" />
        <x-metric-card label="次日留存" :value="$sumRate($d1).'%'" />
        <x-metric-card label="7 日留存" :value="$sumRate($d7).'%'" />
    </div>
    <div class="bg-white rounded shadow overflow-hidden">
        <table class="w-full text-sm">
            <thead class="bg-slate-50 text-slate-600">
                <tr><th class="px-3 py-2 text-left">日期</th><th class="px-3 py-2 text-right">DAU</th></tr>
            </thead>
            <tbody>
                @forelse ($dau as $row)
                    <tr class="border-t">
                        <td class="px-3 py-1.5">{{ $row->date }}</td>
                        <td class="px-3 py-1.5 text-right">{{ number_format($row->dau) }}</td>
                    </tr>
                @empty
                    <tr><td colspan="2" class="px-3 py-4 text-center text-slate-400">暂无数据</td></tr>
                @endforelse
            </tbody>
        </table>
    </div>
</section>

{{-- ── 2. 功能使用 ───────────────────────────────────────────────── --}}
<section class="mb-8">
    <h2 class="text-base font-semibold mb-2">功能使用</h2>
    <div class="grid grid-cols-1 lg:grid-cols-2 gap-4">
        <div class="bg-white rounded shadow overflow-hidden">
            <div class="px-3 py-2 bg-slate-50 text-sm text-slate-600">事件覆盖（去重设备 / 使用率）</div>
            <table class="w-full text-sm">
                <thead class="text-slate-500">
                    <tr><th class="px-3 py-1 text-left">事件</th><th class="px-3 py-1 text-right">设备数</th><th class="px-3 py-1 text-right">次数</th><th class="px-3 py-1 text-right">使用率</th></tr>
                </thead>
                <tbody>
                    @foreach ($features->take(15) as $f)
                        <tr class="border-t">
                            <td class="px-3 py-1 font-mono text-xs">{{ $f['event_name'] }}</td>
                            <td class="px-3 py-1 text-right">{{ number_format($f['uv']) }}</td>
                            <td class="px-3 py-1 text-right">{{ number_format($f['pv']) }}</td>
                            <td class="px-3 py-1 text-right">{{ $f['usage_rate'] }}%</td>
                        </tr>
                    @endforeach
                </tbody>
            </table>
        </div>
        <div class="bg-white rounded shadow overflow-hidden">
            <div class="px-3 py-2 bg-slate-50 text-sm text-slate-600">页面平均停留时长 <span class="text-slate-400">（screen_exited）</span></div>
            <table class="w-full text-sm">
                <thead class="text-slate-500">
                    <tr><th class="px-3 py-1 text-left">页面</th><th class="px-3 py-1 text-right">停留</th><th class="px-3 py-1 text-right">样本</th></tr>
                </thead>
                <tbody>
                    @forelse ($dwell as $d)
                        <tr class="border-t">
                            <td class="px-3 py-1 font-mono text-xs">{{ $d['screen_name'] }}</td>
                            <td class="px-3 py-1 text-right">{{ $fmtMs($d['avg_ms']) }}</td>
                            <td class="px-3 py-1 text-right">{{ number_format($d['views']) }}</td>
                        </tr>
                    @empty
                        <tr><td colspan="3" class="px-3 py-4 text-center text-slate-400">暂无停留数据</td></tr>
                    @endforelse
                </tbody>
            </table>
        </div>
    </div>
</section>

{{-- ── 3. 充电行为 ───────────────────────────────────────────────── --}}
<section class="mb-8">
    <h2 class="text-base font-semibold mb-2">充电行为</h2>
    <div class="grid grid-cols-2 md:grid-cols-4 gap-3">
        <x-metric-card label="充电会话数" :value="number_format($charging['sessions'])" />
        <x-metric-card label="平均充电时长" :value="$fmtMs($charging['avg_duration_ms'])" />
        <x-metric-card label="限值触发次数" :value="number_format($charging['limit_triggers'])" />
        <x-metric-card label="温度警报次数" :value="number_format($charging['temp_alerts'])" />
    </div>
</section>

{{-- ── 4. 广告表现 ───────────────────────────────────────────────── --}}
<section class="mb-8">
    <h2 class="text-base font-semibold mb-2">广告表现</h2>
    <div class="grid grid-cols-2 md:grid-cols-5 gap-3 mb-3">
        <x-metric-card label="请求量" :value="number_format($ads['totals']['requests'])" />
        <x-metric-card label="展示量" :value="number_format($ads['totals']['impressions'])" />
        <x-metric-card label="收入" :value="number_format($ads['totals']['revenue'], 4)" />
        <x-metric-card label="eCPM" :value="number_format($ads['totals']['ecpm'], 4)" />
        <x-metric-card label="ARPU" :value="number_format($ads['totals']['arpu'], 6)" />
    </div>
    <div class="bg-white rounded shadow overflow-x-auto">
        <table class="w-full text-sm">
            <thead class="bg-slate-50 text-slate-600">
                <tr>
                    <th class="px-3 py-2 text-left">广告位</th>
                    <th class="px-3 py-2 text-right">请求</th>
                    <th class="px-3 py-2 text-right">展示</th>
                    <th class="px-3 py-2 text-right">填充率</th>
                    <th class="px-3 py-2 text-right">点击率</th>
                    <th class="px-3 py-2 text-right">失败率</th>
                    <th class="px-3 py-2 text-right">收入</th>
                    <th class="px-3 py-2 text-right">eCPM</th>
                </tr>
            </thead>
            <tbody>
                @forelse ($ads['rows'] as $r)
                    <tr class="border-t">
                        <td class="px-3 py-1.5 font-mono text-xs">{{ $r['placement_id'] }}</td>
                        <td class="px-3 py-1.5 text-right">{{ number_format($r['requests']) }}</td>
                        <td class="px-3 py-1.5 text-right">{{ number_format($r['impressions']) }}</td>
                        <td class="px-3 py-1.5 text-right">{{ $r['fill_rate'] }}%</td>
                        <td class="px-3 py-1.5 text-right">{{ $r['ctr'] }}%</td>
                        <td class="px-3 py-1.5 text-right {{ $r['fail_rate'] > 30 ? 'text-red-600' : '' }}">{{ $r['fail_rate'] }}%</td>
                        <td class="px-3 py-1.5 text-right">{{ number_format($r['revenue'], 4) }}</td>
                        <td class="px-3 py-1.5 text-right">{{ number_format($r['ecpm'], 4) }}</td>
                    </tr>
                @empty
                    <tr><td colspan="8" class="px-3 py-4 text-center text-slate-400">暂无广告数据</td></tr>
                @endforelse
            </tbody>
        </table>
    </div>
    <p class="mt-1 text-xs text-slate-500">
        填充率 = banner_shown / ad_requested；eCPM = (Σ revenue_micros ÷ 1,000,000) ÷ 展示量 × 1000。
        revenue_micros 为百万分之一货币单位，漏除会让 eCPM 放大 10⁶ 倍。
    </p>
</section>

{{-- ── 5. 转化漏斗 ───────────────────────────────────────────────── --}}
<section class="mb-8">
    <h2 class="text-base font-semibold mb-2">转化漏斗 <span class="text-xs font-normal text-slate-500">整体 {{ $funnel['overall_rate'] }}%</span></h2>
    <div class="bg-white rounded shadow overflow-hidden">
        <table class="w-full text-sm">
            <thead class="bg-slate-50 text-slate-600">
                <tr><th class="px-3 py-2 text-left">环节</th><th class="px-3 py-2 text-right">设备数</th><th class="px-3 py-2 text-right">单步转化</th><th class="px-3 py-2 text-right">整体转化</th></tr>
            </thead>
            <tbody>
                @foreach ($funnel['steps'] as $s)
                    <tr class="border-t">
                        <td class="px-3 py-1.5 font-mono text-xs">{{ $s['event_name'] }}</td>
                        <td class="px-3 py-1.5 text-right">{{ number_format($s['uv']) }}</td>
                        <td class="px-3 py-1.5 text-right">{{ $s['step_rate'] }}%</td>
                        <td class="px-3 py-1.5 text-right">{{ $s['total_rate'] }}%</td>
                    </tr>
                @endforeach
            </tbody>
        </table>
    </div>
    <p class="mt-1 text-xs text-slate-500">收入与订阅状态以服务端 RTDN 为准，此漏斗仅用于行为分析。</p>
</section>

{{-- ── 6. 异常监控 ───────────────────────────────────────────────── --}}
<section class="mb-8">
    <h2 class="text-base font-semibold mb-2">异常监控</h2>
    <div class="grid grid-cols-2 md:grid-cols-4 gap-3">
        <x-metric-card label="权限拒绝率" :value="$anomalies['permission_deny_rate'].'%'" :warn="$anomalies['permission_deny_rate'] > 50" />
        <x-metric-card label="广告失败率" :value="$anomalies['ad_fail_rate'].'%'" :warn="$anomalies['ad_fail_rate'] > 30" />
        <x-metric-card label="配置异常率" :value="$anomalies['ad_config_invalid_rate'].'%'" :warn="$anomalies['ad_config_invalid_rate'] > 1" />
        <x-metric-card label="功能不可用次数" :value="number_format($anomalies['feature_unavailable'])" />
    </div>
    <p class="mt-1 text-xs text-slate-500">
        拒绝率 = permission_denied / permission_requested（分母事件不参与采样，否则拒绝率失真）。
    </p>
</section>
@endsection
