@extends('admin.layouts.app')

@section('content')
    <h1 class="text-2xl font-semibold mb-4">{{ $unit ? '编辑广告单元' : '新建广告单元' }}</h1>

    <div class="bg-white p-6 rounded shadow max-w-lg">
        <div class="mb-4 bg-red-50 border border-red-300 text-red-800 p-3 rounded text-sm">
            <strong>⚠ 高危字段：</strong>ad_unit_id 变更需<strong>双人复核</strong>。提交后不立即生效，
            由另一名管理员在「双人复核」页确认后才写入并清缓存；填错（或被策略误改）会导致 AdMob 停号。
        </div>

        <form method="POST" action="{{ $unit ? route('admin.units.update', $unit) : route('admin.units.store') }}">
            @csrf
            @if($unit) @method('PUT') @endif

            <div class="mb-4">
                <label class="block text-sm mb-1">广告位</label>
                <select name="placement_id" class="w-full border rounded px-3 py-2" required>
                    @foreach($placements as $p)
                        <option value="{{ $p->id }}" @selected(old('placement_id', $unit->placement_id ?? '') == $p->id)>{{ $p->placement_key }}（{{ $p->name }}）</option>
                    @endforeach
                </select>
            </div>

            <div class="mb-4">
                <label class="block text-sm mb-1">平台</label>
                <select name="platform" class="w-full border rounded px-3 py-2">
                    @foreach(['android','ios'] as $pf)
                        <option value="{{ $pf }}" @selected(old('platform', $unit->platform ?? 'android') == $pf)>{{ $pf }}</option>
                    @endforeach
                </select>
            </div>

            <div class="mb-4">
                <label class="block text-sm mb-1">国家码（留空 = 全部国家）</label>
                <input type="text" name="country_code" value="{{ old('country_code', $unit->country_code ?? '') }}"
                       class="w-full border rounded px-3 py-2 uppercase" maxlength="2" placeholder="如 ID / VN / TH">
            </div>

            <div class="mb-4">
                <label class="block text-sm mb-1">ad_unit_id</label>
                <input type="text" name="ad_unit_id" value="{{ old('ad_unit_id', $unit->ad_unit_id ?? '') }}"
                       class="w-full border rounded px-3 py-2 font-mono text-xs"
                       placeholder="ca-app-pub-xxxxxxxxxxxxxxxx/xxxxxxxxxx" required>
                <p class="text-xs text-slate-500 mt-1">格式：ca-app-pub-16位数字/10位数字（与 Resolver 校验一致）。</p>
            </div>

            <div class="mb-4 flex items-center gap-4">
                <label class="flex items-center text-sm">
                    <input type="hidden" name="enabled" value="0">
                    <input type="checkbox" name="enabled" value="1" class="mr-2" @checked(old('enabled', $unit->enabled ?? true))> 启用
                </label>
                <label class="flex items-center text-sm">
                    network <input type="text" name="network" value="{{ old('network', $unit->network ?? 'admob') }}"
                                  class="border rounded px-2 py-1 w-28 ml-2">
                </label>
                <label class="flex items-center text-sm">
                    priority <input type="number" name="priority" value="{{ old('priority', $unit->priority ?? 0) }}"
                                   class="border rounded px-2 py-1 w-20 ml-2">
                </label>
            </div>

            <div class="mb-4">
                <label class="block text-sm mb-1">ecpm_floor（可选）</label>
                <input type="number" step="0.0001" name="ecpm_floor" value="{{ old('ecpm_floor', $unit->ecpm_floor ?? '') }}"
                       class="w-full border rounded px-3 py-2">
            </div>

            <button class="bg-slate-900 text-white px-4 py-2 rounded">保存</button>
            <a href="{{ route('admin.units.index') }}" class="ml-3 text-slate-500">取消</a>
        </form>
    </div>
@endsection
