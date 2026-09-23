@extends('admin.layouts.app')

@section('content')
    <div class="flex items-center justify-between mb-4">
        <h1 class="text-2xl font-semibold">广告单元</h1>
        <a href="{{ route('admin.units.create') }}" class="bg-slate-900 text-white px-4 py-2 rounded text-sm">新建单元</a>
    </div>

    @if($pendingCreates->isNotEmpty())
        <div class="mb-4 bg-amber-50 border border-amber-300 text-amber-800 p-3 rounded text-sm">
            <strong>待复核的新建单元（{{ $pendingCreates->count() }}）：</strong>
            以下新建请求含高危 ad_unit_id，需另一名管理员复核后才生效，期间不会下发。
            <ul class="mt-1 list-disc pl-5">
                @foreach($pendingCreates as $pc)
                    <li>{{ $pc->new_value['placement_id'] ?? '' }} / {{ $pc->new_value['platform'] ?? '' }} / {{ $pc->new_value['country_code'] ?? '全部' }}
                        — 提交人：{{ $pc->requestedBy->name ?? '—' }}</li>
                @endforeach
            </ul>
            <a href="{{ route('admin.reviews.index') }}" class="underline">前往复核 →</a>
        </div>
    @endif

    <div class="bg-white rounded shadow overflow-x-auto">
        <table class="w-full text-sm">
            <thead class="text-left text-slate-500 border-b">
            <tr>
                <th class="px-4 py-2">广告位</th>
                <th class="px-4 py-2">平台</th>
                <th class="px-4 py-2">国家</th>
                <th class="px-4 py-2">ad_unit_id</th>
                <th class="px-4 py-2">状态</th>
                <th class="px-4 py-2">操作</th>
            </tr>
            </thead>
            <tbody>
            @forelse($units as $u)
                <tr class="border-b">
                    <td class="px-4 py-2">{{ $u->placement->placement_key ?? '—' }}</td>
                    <td class="px-4 py-2">{{ $u->platform }}</td>
                    <td class="px-4 py-2">{{ $u->country_code ?? '全部' }}</td>
                    <td class="px-4 py-2 font-mono text-xs">
                        {{ $u->ad_unit_id }}
                        @if(in_array($u->id, $pendingUnitIds))
                            <span class="ml-1 px-1.5 py-0.5 text-xs bg-amber-200 text-amber-800 rounded">待复核</span>
                        @endif
                    </td>
                    <td class="px-4 py-2">
                        @if($u->enabled)<span class="text-green-600">启用</span>@else<span class="text-red-600">停用</span>@endif
                    </td>
                    <td class="px-4 py-2 space-x-2">
                        <a href="{{ route('admin.units.edit', $u) }}" class="text-blue-600">编辑</a>
                        <form method="POST" action="{{ route('admin.units.toggle', $u) }}" class="inline">
                            @csrf
                            <input type="hidden" name="enabled" value="{{ $u->enabled ? 0 : 1 }}">
                            <button class="text-slate-600">{{ $u->enabled ? '停用' : '启用' }}</button>
                        </form>
                        <form method="POST" action="{{ route('admin.units.destroy', $u) }}" class="inline"
                              onsubmit="return confirm('确认删除该广告单元？');">
                            @csrf @method('DELETE')
                            <button class="text-red-600">删除</button>
                        </form>
                    </td>
                </tr>
            @empty
                <tr><td colspan="6" class="px-4 py-3 text-slate-500">暂无广告单元。</td></tr>
            @endforelse
            </tbody>
        </table>
    </div>

    <div class="mt-3">{{ $units->links() }}</div>
@endsection
