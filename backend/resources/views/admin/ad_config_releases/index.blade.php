@extends('admin.layouts.app')

@section('content')
    <h1 class="text-2xl font-semibold mb-4">灰度发布与回滚</h1>

    <div class="bg-white p-6 rounded shadow max-w-xl mb-6">
        <h2 class="font-semibold mb-3">发布当前配置快照</h2>
        <form method="POST" action="{{ route('admin.releases.publish') }}">
            @csrf
            <div class="mb-3 flex items-center gap-4">
                <label class="text-sm">灰度比例(%)</label>
                <input type="number" name="rollout_percent" value="100" min="1" max="100"
                       class="border rounded px-2 py-1 w-20">
                <span class="text-xs text-slate-500">5 → 20 → 100 逐步放量</span>
            </div>
            <div class="mb-3">
                <label class="block text-sm mb-1">备注</label>
                <input type="text" name="note" class="w-full border rounded px-3 py-2" placeholder="如：替换 ID 插屏单元">
            </div>
            <div class="mb-3">
                <label class="block text-sm mb-1">目标条件 target_json（可选，JSON）</label>
                <textarea name="target_json" rows="2" class="w-full border rounded px-3 py-2 font-mono text-xs"
                          placeholder='{"country":["ID"],"app_version_min":"1.2.0"}'></textarea>
            </div>
            <button class="bg-slate-900 text-white px-4 py-2 rounded">发布新版本</button>
        </form>
    </div>

    <div class="bg-white rounded shadow overflow-x-auto">
        <table class="w-full text-sm">
            <thead class="text-left text-slate-500 border-b">
            <tr>
                <th class="px-4 py-2">版本</th>
                <th class="px-4 py-2">状态</th>
                <th class="px-4 py-2">灰度</th>
                <th class="px-4 py-2">发布人</th>
                <th class="px-4 py-2">发布时间</th>
                <th class="px-4 py-2">备注</th>
                <th class="px-4 py-2">操作</th>
            </tr>
            </thead>
            <tbody>
            @forelse($releases as $rel)
                <tr class="border-b">
                    <td class="px-4 py-2 font-bold">v{{ $rel->version }}
                        @if($rel->version == $currentVersion)<span class="text-green-600 text-xs">（生效中）</span>@endif
                    </td>
                    <td class="px-4 py-2">
                        @if($rel->status == 'published')<span class="text-green-600">已发布</span>
                        @elseif($rel->status == 'rolled_back')<span class="text-red-600">已回滚</span>
                        @else<span class="text-slate-500">草稿</span>@endif
                    </td>
                    <td class="px-4 py-2">{{ $rel->rollout_percent }}%</td>
                    <td class="px-4 py-2">{{ $rel->published_by ?? '—' }}</td>
                    <td class="px-4 py-2 text-xs">{{ $rel->published_at?->format('Y-m-d H:i') ?? '—' }}</td>
                    <td class="px-4 py-2 text-slate-500">{{ $rel->note }}</td>
                    <td class="px-4 py-2">
                        @if($rel->status != 'published')
                            <form method="POST" action="{{ route('admin.releases.rollback', $rel) }}" class="inline"
                                  onsubmit="return confirm('回滚到 v{{ $rel->version }}？当前生效版本将被取代。');">
                                @csrf
                                <button class="text-blue-600">回滚到此版本</button>
                            </form>
                        @else
                            <span class="text-slate-400">—</span>
                        @endif
                    </td>
                </tr>
            @empty
                <tr><td colspan="7" class="px-4 py-3 text-slate-500">暂无发布记录。</td></tr>
            @endforelse
            </tbody>
        </table>
    </div>

    <div class="mt-3">{{ $releases->links() }}</div>
@endsection
