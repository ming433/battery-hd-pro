@extends('admin.layouts.app')

@section('content')
    <h1 class="text-2xl font-semibold mb-4">{{ $placement ? '编辑广告位' : '新建广告位' }}</h1>

    <div class="bg-white p-6 rounded shadow max-w-lg">
        <form method="POST" action="{{ $placement ? route('admin.placements.update', $placement) : route('admin.placements.store') }}">
            @csrf
            @if($placement) @method('PUT') @endif

            <div class="mb-4">
                <label class="block text-sm mb-1">placement_key（稳定标识，创建后不可修改）</label>
                <input type="text" name="placement_key" value="{{ old('placement_key', $placement->placement_key ?? '') }}"
                       class="w-full border rounded px-3 py-2 font-mono"
                       {{ $placement ? 'readonly' : '' }}
                       pattern="^[a-z0-9_]+$" required>
                <p class="text-xs text-slate-500 mt-1">仅小写字母/数字/下划线。它是埋点聚合维度，改了看板曲线会断。</p>
            </div>

            <div class="mb-4">
                <label class="block text-sm mb-1">名称</label>
                <input type="text" name="name" value="{{ old('name', $placement->name ?? '') }}"
                       class="w-full border rounded px-3 py-2" required>
            </div>

            <div class="mb-4">
                <label class="block text-sm mb-1">广告格式</label>
                <select name="ad_format" class="w-full border rounded px-3 py-2">
                    @foreach(['banner','interstitial','rewarded','native'] as $f)
                        <option value="{{ $f }}" @selected(old('ad_format', $placement->ad_format ?? '') == $f)>{{ $f }}</option>
                    @endforeach
                </select>
            </div>

            <div class="mb-4">
                <label class="block text-sm mb-1">描述</label>
                <input type="text" name="description" value="{{ old('description', $placement->description ?? '') }}"
                       class="w-full border rounded px-3 py-2">
            </div>

            <div class="mb-4 flex items-center gap-4">
                <label class="flex items-center text-sm">
                    <input type="hidden" name="enabled" value="0">
                    <input type="checkbox" name="enabled" value="1" class="mr-2" @checked(old('enabled', $placement->enabled ?? true))> 启用
                </label>
                <label class="flex items-center text-sm">
                    排序 <input type="number" name="sort_order" value="{{ old('sort_order', $placement->sort_order ?? 0) }}"
                              class="border rounded px-2 py-1 w-20 ml-2">
                </label>
            </div>

            <button class="bg-slate-900 text-white px-4 py-2 rounded">保存</button>
            <a href="{{ route('admin.placements.index') }}" class="ml-3 text-slate-500">取消</a>
        </form>
    </div>
@endsection
