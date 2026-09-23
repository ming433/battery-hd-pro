@extends('admin.layouts.app')

@section('content')
    <h1 class="text-2xl font-semibold mb-4">{{ $policy ? '编辑策略' : '新建策略' }}</h1>

    <div class="bg-white p-6 rounded shadow max-w-2xl">
        @php
            $cfg = $policy ? ($policy->config_json ?? []) : [];
            $fc = $cfg['frequency_cap'] ?? [];
            $cb = $cfg['circuit_breaker'] ?? [];
            $scenes = $cfg['trigger_scenes'] ?? [];
        @endphp

        <form method="POST" action="{{ $policy ? route('admin.policies.update', $policy) : route('admin.policies.store') }}">
            @csrf
            @if($policy) @method('PUT') @endif

            <div class="mb-4">
                <label class="block text-sm mb-1">广告位</label>
                <select name="placement_id" class="w-full border rounded px-3 py-2" required>
                    @foreach($placements as $p)
                        <option value="{{ $p->id }}" @selected(old('placement_id', $policy->placement_id ?? '') == $p->id)>{{ $p->placement_key }}（{{ $p->name }}）</option>
                    @endforeach
                </select>
            </div>

            <div class="grid grid-cols-3 gap-4 mb-4">
                <div>
                    <label class="block text-sm mb-1">国家码（空=全部）</label>
                    <input type="text" name="country_code" value="{{ old('country_code', $policy->country_code ?? '') }}"
                           class="w-full border rounded px-3 py-2 uppercase" maxlength="2">
                </div>
                <div>
                    <label class="block text-sm mb-1">用户分群</label>
                    <select name="user_segment" class="w-full border rounded px-3 py-2">
                        @foreach(['all','free','pro','new'] as $s)
                            <option value="{{ $s }}" @selected(old('user_segment', $policy->user_segment ?? 'all') == $s)>{{ $s }}</option>
                        @endforeach
                    </select>
                </div>
                <div>
                    <label class="block text-sm mb-1">优先级</label>
                    <input type="number" name="priority" value="{{ old('priority', $policy->priority ?? 0) }}"
                           class="w-full border rounded px-3 py-2">
                </div>
            </div>

            <div class="grid grid-cols-2 gap-4 mb-4">
                <div>
                    <label class="block text-sm mb-1">版本下限（可选）</label>
                    <input type="text" name="app_version_min" value="{{ old('app_version_min', $policy->app_version_min ?? '') }}"
                           class="w-full border rounded px-3 py-2" placeholder="1.2.0">
                </div>
                <div>
                    <label class="block text-sm mb-1">版本上限（可选）</label>
                    <input type="text" name="app_version_max" value="{{ old('app_version_max', $policy->app_version_max ?? '') }}"
                           class="w-full border rounded px-3 py-2" placeholder="2.0.0">
                </div>
            </div>

            <fieldset class="border rounded p-4 mb-4">
                <legend class="text-sm font-semibold px-1">频次上限（frequency_cap）</legend>
                <div class="grid grid-cols-3 gap-4">
                    <div><label class="block text-xs mb-1">每会话</label>
                        <input type="number" name="fc_per_session" value="{{ old('fc_per_session', $fc['per_session'] ?? '') }}" class="w-full border rounded px-2 py-1"></div>
                    <div><label class="block text-xs mb-1">每日</label>
                        <input type="number" name="fc_per_day" value="{{ old('fc_per_day', $fc['per_day'] ?? '') }}" class="w-full border rounded px-2 py-1"></div>
                    <div><label class="block text-xs mb-1">最小间隔(秒)</label>
                        <input type="number" name="fc_min_interval_seconds" value="{{ old('fc_min_interval_seconds', $fc['min_interval_seconds'] ?? '') }}" class="w-full border rounded px-2 py-1"></div>
                </div>
            </fieldset>

            <fieldset class="border rounded p-4 mb-4">
                <legend class="text-sm font-semibold px-1">触发场景白名单（trigger_scenes）</legend>
                <div class="flex flex-wrap gap-3">
                    @foreach($triggerScenes as $s)
                        <label class="flex items-center text-sm">
                            <input type="checkbox" name="trigger_scenes[]" value="{{ $s }}"
                                   class="mr-1" @checked(in_array($s, old('trigger_scenes', $scenes)))> {{ $s }}
                        </label>
                    @endforeach
                </div>
            </fieldset>

            <div class="grid grid-cols-2 gap-4 mb-4">
                <div><label class="block text-sm mb-1">加载超时(ms)</label>
                    <input type="number" name="load_timeout_ms" value="{{ old('load_timeout_ms', $cfg['load_timeout_ms'] ?? '') }}" class="w-full border rounded px-3 py-2"></div>
                <div><label class="block text-sm mb-1">重试次数</label>
                    <input type="number" name="retry_count" value="{{ old('retry_count', $cfg['retry_count'] ?? '') }}" class="w-full border rounded px-3 py-2"></div>
            </div>

            <fieldset class="border rounded p-4 mb-4">
                <legend class="text-sm font-semibold px-1">熔断（circuit_breaker）</legend>
                <div class="grid grid-cols-2 gap-4">
                    <div><label class="block text-xs mb-1">失败阈值</label>
                        <input type="number" name="cb_fail_threshold" value="{{ old('cb_fail_threshold', $cb['fail_threshold'] ?? '') }}" class="w-full border rounded px-2 py-1"></div>
                    <div><label class="block text-xs mb-1">冷却(秒)</label>
                        <input type="number" name="cb_cooldown_seconds" value="{{ old('cb_cooldown_seconds', $cb['cooldown_seconds'] ?? '') }}" class="w-full border rounded px-2 py-1"></div>
                </div>
            </fieldset>

            <label class="flex items-center text-sm mb-4">
                <input type="hidden" name="enabled" value="0">
                <input type="checkbox" name="enabled" value="1" class="mr-2" @checked(old('enabled', $policy->enabled ?? true))> 启用
            </label>

            <button class="bg-slate-900 text-white px-4 py-2 rounded">保存</button>
            <a href="{{ route('admin.policies.index') }}" class="ml-3 text-slate-500">取消</a>
        </form>
    </div>
@endsection
