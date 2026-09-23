@extends('admin.layouts.app')

@section('content')
    <h1 class="text-2xl font-semibold mb-4">审计日志</h1>

    <form method="GET" class="mb-4 flex gap-3 items-end">
        <div>
            <label class="block text-xs mb-1">目标类型</label>
            <select name="target_type" class="border rounded px-2 py-1">
                <option value="">全部</option>
                @foreach($targetTypes as $t)
                    <option value="{{ $t }}" @selected(request('target_type') == $t)>{{ $t }}</option>
                @endforeach
            </select>
        </div>
        <div>
            <label class="block text-xs mb-1">动作</label>
            <select name="action" class="border rounded px-2 py-1">
                <option value="">全部</option>
                @foreach($actions as $a)
                    <option value="{{ $a }}" @selected(request('action') == $a)>{{ $a }}</option>
                @endforeach
            </select>
        </div>
        <button class="bg-slate-900 text-white px-3 py-1.5 rounded text-sm">筛选</button>
    </form>

    <div class="bg-white rounded shadow overflow-x-auto">
        <table class="w-full text-sm">
            <thead class="text-left text-slate-500 border-b">
            <tr>
                <th class="px-4 py-2">时间</th>
                <th class="px-4 py-2">操作人</th>
                <th class="px-4 py-2">动作</th>
                <th class="px-4 py-2">目标</th>
                <th class="px-4 py-2">旧值</th>
                <th class="px-4 py-2">新值</th>
                <th class="px-4 py-2">IP</th>
            </tr>
            </thead>
            <tbody>
            @forelse($logs as $log)
                <tr class="border-b align-top">
                    <td class="px-4 py-2 text-xs whitespace-nowrap">{{ $log->created_at->format('Y-m-d H:i:s') }}</td>
                    <td class="px-4 py-2">{{ $log->operator_name ?? '—' }}</td>
                    <td class="px-4 py-2">{{ $log->action }}</td>
                    <td class="px-4 py-2">{{ $log->target_type }}#{{ $log->target_id }}</td>
                    <td class="px-4 py-2 text-xs font-mono max-w-xs break-all">
                        @if($log->before_json)<pre class="text-red-600 whitespace-pre-wrap">{{ json_encode($log->before_json, JSON_UNESCAPED_UNICODE|JSON_PRETTY_PRINT) }}</pre>@else — @endif
                    </td>
                    <td class="px-4 py-2 text-xs font-mono max-w-xs break-all">
                        @if($log->after_json)<pre class="text-green-700 whitespace-pre-wrap">{{ json_encode($log->after_json, JSON_UNESCAPED_UNICODE|JSON_PRETTY_PRINT) }}</pre>@else — @endif
                    </td>
                    <td class="px-4 py-2 text-xs">{{ $log->ip }}</td>
                </tr>
            @empty
                <tr><td colspan="7" class="px-4 py-3 text-slate-500">暂无日志。</td></tr>
            @endforelse
            </tbody>
        </table>
    </div>

    <div class="mt-3">{{ $logs->links() }}</div>
@endsection
