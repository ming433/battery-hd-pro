@extends('admin.layouts.app')

@section('content')
    <h1 class="text-2xl font-semibold mb-4">双人复核工作台</h1>

    <div class="bg-white rounded shadow overflow-x-auto">
        <table class="w-full text-sm">
            <thead class="text-left text-slate-500 border-b">
            <tr>
                <th class="px-4 py-2">状态</th>
                <th class="px-4 py-2">目标</th>
                <th class="px-4 py-2">动作</th>
                <th class="px-4 py-2">变更内容</th>
                <th class="px-4 py-2">提交人</th>
                <th class="px-4 py-2">时间</th>
                <th class="px-4 py-2">操作</th>
            </tr>
            </thead>
            <tbody>
            @forelse($reviews as $r)
                <tr class="border-b">
                    <td class="px-4 py-2">
                        @if($r->status == 'pending')<span class="text-amber-700 font-semibold">待复核</span>
                        @elseif($r->status == 'approved')<span class="text-green-600">已通过</span>
                        @else<span class="text-slate-500">已拒绝</span>@endif
                    </td>
                    <td class="px-4 py-2">{{ $r->target_type }}@if($r->field)/{{ $r->field }}@endif
                        @if($r->target_id)#{{ $r->target_id }}@endif
                    </td>
                    <td class="px-4 py-2">{{ $r->action }}</td>
                    <td class="px-4 py-2 text-xs font-mono">
                        @if($r->old_value)<span class="text-red-600">{{ json_encode($r->old_value, JSON_UNESCAPED_UNICODE) }}</span> → @endif
                        <span class="text-green-700">{{ json_encode($r->new_value, JSON_UNESCAPED_UNICODE) }}</span>
                    </td>
                    <td class="px-4 py-2">{{ $r->requestedBy->name ?? '—' }}</td>
                    <td class="px-4 py-2 text-xs">{{ $r->created_at->format('Y-m-d H:i') }}</td>
                    <td class="px-4 py-2 space-x-2">
                        @if($r->status == 'pending')
                            @if($r->requested_by === auth('admin')->id())
                                <span class="text-slate-400 text-xs">不能复核自己的提交</span>
                            @else
                                <form method="POST" action="{{ route('admin.reviews.approve', $r) }}" class="inline">
                                    @csrf
                                    <button class="text-green-600">通过</button>
                                </form>
                                <form method="POST" action="{{ route('admin.reviews.reject', $r) }}" class="inline"
                                      onsubmit="return confirm('拒绝该变更？');">
                                    @csrf
                                    <button class="text-red-600">拒绝</button>
                                </form>
                            @endif
                        @else
                            <span class="text-slate-400">{{ $r->reviewedBy->name ?? '' }}</span>
                        @endif
                    </td>
                </tr>
            @empty
                <tr><td colspan="7" class="px-4 py-3 text-slate-500">暂无复核记录。</td></tr>
            @endforelse
            </tbody>
        </table>
    </div>

    <div class="mt-3">{{ $reviews->links() }}</div>
@endsection
