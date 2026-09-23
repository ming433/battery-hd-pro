@extends('admin.layouts.app')

@section('content')
    <h1 class="text-2xl font-semibold mb-4">概览</h1>

    <div class="grid grid-cols-2 md:grid-cols-5 gap-4 mb-6">
        <div class="bg-white p-4 rounded shadow">
            <div class="text-xs text-slate-500">广告位</div>
            <div class="text-2xl font-bold">{{ $stats['placements'] }}</div>
        </div>
        <div class="bg-white p-4 rounded shadow">
            <div class="text-xs text-slate-500">广告单元</div>
            <div class="text-2xl font-bold">{{ $stats['units'] }}</div>
        </div>
        <div class="bg-white p-4 rounded shadow">
            <div class="text-xs text-slate-500">策略</div>
            <div class="text-2xl font-bold">{{ $stats['policies'] }}</div>
        </div>
        <div class="bg-white p-4 rounded shadow">
            <div class="text-xs text-slate-500">待复核</div>
            <div class="text-2xl font-bold {{ $stats['pending'] > 0 ? 'text-red-600' : '' }}">{{ $stats['pending'] }}</div>
        </div>
        <div class="bg-white p-4 rounded shadow">
            <div class="text-xs text-slate-500">当前版本</div>
            <div class="text-2xl font-bold">v{{ $currentVersion }}</div>
        </div>
    </div>

    <div class="grid grid-cols-1 md:grid-cols-2 gap-6">
        <div class="bg-white p-4 rounded shadow">
            <h2 class="font-semibold mb-3">待复核变更</h2>
            @if($pendingReviews->isEmpty())
                <p class="text-sm text-slate-500">暂无待复核项。</p>
            @else
                <table class="w-full text-sm">
                    <thead class="text-left text-slate-500">
                    <tr><th class="py-1">目标</th><th>提交人</th><th>说明</th></tr>
                    </thead>
                    <tbody>
                    @foreach($pendingReviews as $r)
                        <tr class="border-t">
                            <td class="py-1">{{ $r->target_type }}@if($r->field)/{{ $r->field }}@endif</td>
                            <td>{{ $r->requestedBy->name ?? '—' }}</td>
                            <td class="text-slate-500">{{ $r->note }}</td>
                        </tr>
                    @endforeach
                    </tbody>
                </table>
                <a href="{{ route('admin.reviews.index') }}" class="inline-block mt-3 text-sm text-blue-600">前往复核 →</a>
            @endif
        </div>

        <div class="bg-white p-4 rounded shadow">
            <h2 class="font-semibold mb-3">最近审计日志</h2>
            <table class="w-full text-sm">
                <thead class="text-left text-slate-500">
                <tr><th class="py-1">时间</th><th>操作</th><th>目标</th><th>操作人</th></tr>
                </thead>
                <tbody>
                @forelse($recentLogs as $log)
                    <tr class="border-t">
                        <td class="py-1">{{ $log->created_at->format('m-d H:i') }}</td>
                        <td>{{ $log->action }}</td>
                        <td>{{ $log->target_type }}#{{ $log->target_id }}</td>
                        <td>{{ $log->operator_name ?? '—' }}</td>
                    </tr>
                @empty
                    <tr><td colspan="4" class="text-slate-500 py-1">暂无</td></tr>
                @endforelse
                </tbody>
            </table>
        </div>
    </div>

    <div class="mt-6 bg-white p-4 rounded shadow text-sm">
        <h2 class="font-semibold mb-2">全局状态</h2>
        <p>广告总开关：<span class="{{ $global->ads_enabled ? 'text-green-600' : 'text-red-600' }}">{{ $global->ads_enabled ? '开' : '关' }}</span>
            ｜ 紧急熔断 kill_switch：<span class="{{ $global->kill_switch ? 'text-red-600 font-bold' : 'text-green-600' }}">{{ $global->kill_switch ? '已触发' : '正常' }}</span></p>
        <p class="text-slate-500 mt-1">内容分级：{{ $global->max_ad_content_rating }} ｜ 儿童向：{{ $global->tag_for_child_directed ? '是' : '否' }}</p>
        <p class="text-slate-500">最新发布版本：v{{ $latestRelease->version ?? '—' }}（{{ $latestRelease->status ?? '—' }}）</p>
    </div>
@endsection
