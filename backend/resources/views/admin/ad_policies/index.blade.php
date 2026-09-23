@extends('admin.layouts.app')

@section('content')
    <div class="flex items-center justify-between mb-4">
        <h1 class="text-2xl font-semibold">广告策略</h1>
        <a href="{{ route('admin.policies.create') }}" class="bg-slate-900 text-white px-4 py-2 rounded text-sm">新建策略</a>
    </div>

    <div class="bg-white rounded shadow overflow-x-auto">
        <table class="w-full text-sm">
            <thead class="text-left text-slate-500 border-b">
            <tr>
                <th class="px-4 py-2">广告位</th>
                <th class="px-4 py-2">国家</th>
                <th class="px-4 py-2">分群</th>
                <th class="px-4 py-2">版本区间</th>
                <th class="px-4 py-2">状态</th>
                <th class="px-4 py-2">操作</th>
            </tr>
            </thead>
            <tbody>
            @forelse($policies as $po)
                <tr class="border-b">
                    <td class="px-4 py-2">{{ $po->placement->placement_key ?? '—' }}</td>
                    <td class="px-4 py-2">{{ $po->country_code ?? '全部' }}</td>
                    <td class="px-4 py-2">{{ $po->user_segment }}</td>
                    <td class="px-4 py-2 text-xs">{{ $po->app_version_min ?? '*' }} ~ {{ $po->app_version_max ?? '*' }}</td>
                    <td class="px-4 py-2">
                        @if($po->enabled)<span class="text-green-600">启用</span>@else<span class="text-red-600">停用</span>@endif
                    </td>
                    <td class="px-4 py-2 space-x-2">
                        <a href="{{ route('admin.policies.edit', $po) }}" class="text-blue-600">编辑</a>
                        <form method="POST" action="{{ route('admin.policies.destroy', $po) }}" class="inline"
                              onsubmit="return confirm('确认删除该策略？');">
                            @csrf @method('DELETE')
                            <button class="text-red-600">删除</button>
                        </form>
                    </td>
                </tr>
            @empty
                <tr><td colspan="6" class="px-4 py-3 text-slate-500">暂无策略。</td></tr>
            @endforelse
            </tbody>
        </table>
    </div>

    <div class="mt-3">{{ $policies->links() }}</div>
@endsection
