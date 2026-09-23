@extends('admin.layouts.app')

@section('content')
    <h1 class="text-2xl font-semibold mb-4">全局设置</h1>

    @if($pendingReviews->isNotEmpty())
        <div class="mb-4 bg-amber-50 border border-amber-300 text-amber-800 p-3 rounded text-sm">
            <strong>有 {{ $pendingReviews->count() }} 项高危变更待复核：</strong>
            @foreach($pendingReviews as $r)
                <span class="inline-block mr-2">{{ $r->field }}（提交人：{{ $r->requestedBy->name ?? '—' }}）</span>
            @endforeach
            <a href="{{ route('admin.reviews.index') }}" class="underline">前往复核 →</a>
        </div>
    @endif

    <div class="bg-white p-6 rounded shadow max-w-xl">
        <form method="POST" action="{{ route('admin.global-settings.update') }}">
            @csrf
            @method('PUT')

            <label class="flex items-center justify-between py-2 border-b">
                <span>广告总开关 ads_enabled</span>
                <input type="hidden" name="ads_enabled" value="0">
                <input type="checkbox" name="ads_enabled" value="1" @checked(old('ads_enabled', $global->ads_enabled))>
            </label>

            <div class="py-3 border-b">
                <label class="flex items-center justify-between">
                    <span>紧急熔断 kill_switch</span>
                    <input type="hidden" name="kill_switch" value="0">
                    <input type="checkbox" name="kill_switch" value="1" @checked(old('kill_switch', $global->kill_switch))>
                </label>
                @if(isset($pendingValues['kill_switch']))
                    <p class="text-xs text-amber-700 mt-1">待复核新值：{{ $pendingValues['kill_switch'] ? '触发' : '正常' }}</p>
                @endif
                <p class="text-xs text-slate-500 mt-1">关闭全量广告请求，需双人复核。仅紧急止血时开启。</p>
            </div>

            <div class="py-2 border-b flex items-center justify-between">
                <span>新用户免广告时长(分钟)</span>
                <input type="number" name="first_launch_grace_minutes" value="{{ old('first_launch_grace_minutes', $global->first_launch_grace_minutes) }}"
                       class="border rounded px-2 py-1 w-24">
            </div>

            <div class="py-3 border-b bg-red-50 rounded px-3 my-2">
                <label class="flex items-center justify-between">
                    <span class="font-semibold text-red-700">⚠ 儿童向标识 tag_for_child_directed</span>
                    <input type="hidden" name="tag_for_child_directed" value="0">
                    <input type="checkbox" name="tag_for_child_directed" value="1" @checked(old('tag_for_child_directed', $global->tag_for_child_directed))>
                </label>
                @if(isset($pendingValues['tag_for_child_directed']))
                    <p class="text-xs text-amber-700 mt-1">待复核新值：{{ $pendingValues['tag_for_child_directed'] ? '是' : '否' }}</p>
                @endif
                <p class="text-xs text-red-700 mt-1">设错（把成人应用标为儿童向）会被 AdMob <strong>直接停号</strong>。需双人复核。仅在应用确实面向儿童时开启。</p>
            </div>

            <div class="py-3 border-b bg-red-50 rounded px-3 my-2">
                <label class="flex items-center justify-between">
                    <span class="font-semibold text-red-700">⚠ 内容分级 max_ad_content_rating</span>
                    <select name="max_ad_content_rating" class="border rounded px-2 py-1">
                        @foreach(['G','PG','T','MA'] as $r)
                            <option value="{{ $r }}" @selected(old('max_ad_content_rating', $global->max_ad_content_rating) == $r)>{{ $r }}</option>
                        @endforeach
                    </select>
                </label>
                @if(isset($pendingValues['max_ad_content_rating']))
                    <p class="text-xs text-amber-700 mt-1">待复核新值：{{ $pendingValues['max_ad_content_rating'] }}</p>
                @endif
                <p class="text-xs text-red-700 mt-1">分级设错（如成人内容标为 G）会被 AdMob <strong>直接停号</strong>。需双人复核。</p>
            </div>

            <label class="flex items-center justify-between py-2 border-b">
                <span>npa 默认（非个性化广告降级）</span>
                <input type="hidden" name="npa_default" value="0">
                <input type="checkbox" name="npa_default" value="1" @checked(old('npa_default', $global->npa_default))>
            </label>

            <div class="py-2 border-b flex items-center justify-between">
                <span>配置缓存 TTL(秒)</span>
                <input type="number" name="config_ttl_seconds" value="{{ old('config_ttl_seconds', $global->config_ttl_seconds) }}"
                       class="border rounded px-2 py-1 w-24" min="30">
            </div>

            <div class="mt-4">
                <button class="bg-slate-900 text-white px-4 py-2 rounded">保存</button>
            </div>
        </form>
    </div>
@endsection
