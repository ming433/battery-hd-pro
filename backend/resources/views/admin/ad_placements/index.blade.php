@extends('admin.layouts.app')

@section('content')
    <div class="flex items-center justify-between mb-4">
        <h1 class="text-2xl font-semibold">广告位</h1>
        <a href="{{ route('admin.placements.create') }}" class="bg-slate-900 text-white px-4 py-2 rounded text-sm">新建广告位</a>
    </div>

    <div class="bg-white rounded shadow overflow-x-auto">
        <table class="w-full text-sm">
            <thead class="text-left text-slate-500 border-b">
            <tr>
                <th class="px-4 py-2">placement_key</th>
                <th class="px-4 py-2">名称</th>
                <th class="px-4 py-2">格式</th>
                <th class="px-4 py-2">状态</th>
                <th class="px-4 py-2">单元/策略</th>
                <th class="px-4 py-2">操作</th>
            </tr>
            </thead>
            <tbody>
            @forelse($placements as $p)
                <tr class="border-b">
                    <td class="px-4 py-2 font-mono">{{ $p->placement_key }}</td>
                    <td class="px-4 py-2">{{ $p->name }}</td>
                    <td class="px-4 py-2">{{ $p->ad_format }}</td>
                    <td class="px-4 py-2">
                        @if($p->enabled)
                            <span class="text-green-600">启用</span>
                        @else
                            <span class="text-red-600">停用</span>
                        @endif
                    </td>
                    <td class="px-4 py-2 text-slate-500">{{ $p->units_count }} / {{ $p->policies_count }}</td>
                    <td class="px-4 py-2 space-x-2">
                        <a href="{{ route('admin.placements.edit', $p) }}" class="text-blue-600">编辑</a>
                        <form method="POST" action="{{ route('admin.placements.toggle', $p) }}" class="inline">
                            @csrf
                            <input type="hidden" name="enabled" value="{{ $p->enabled ? 0 : 1 }}">
                            <button class="text-slate-600">{{ $p->enabled ? '停用' : '启用' }}</button>
                        </form>
                    </td>
                </tr>
            @empty
                <tr><td colspan="6" class="px-4 py-3 text-slate-500">暂无广告位。</td></tr>
            @endforelse
            </tbody>
        </table>
    </div>

    <div class="mt-3">
        {{ $placements->links() }}
    </div>
@endsection
