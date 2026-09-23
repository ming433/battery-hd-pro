<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>@yield('title', '广告配置管理后台') · Battery HD Pro</title>
    <script src="https://cdn.tailwindcss.com"></script>
</head>
<body class="bg-slate-100 text-slate-800">
<div class="min-h-screen flex">
    <!-- 侧边栏 -->
    <aside class="w-60 bg-slate-900 text-slate-100 flex flex-col">
        <div class="px-4 py-4 text-lg font-semibold border-b border-slate-700">
            广告配置后台
        </div>
        <nav class="flex-1 px-2 py-3 space-y-1 text-sm">
            <a href="{{ route('admin.dashboard') }}" class="block px-3 py-2 rounded hover:bg-slate-700">概览</a>
            <a href="{{ route('admin.placements.index') }}" class="block px-3 py-2 rounded hover:bg-slate-700">广告位</a>
            <a href="{{ route('admin.units.index') }}" class="block px-3 py-2 rounded hover:bg-slate-700">广告单元</a>
            <a href="{{ route('admin.policies.index') }}" class="block px-3 py-2 rounded hover:bg-slate-700">策略</a>
            <a href="{{ route('admin.global-settings.edit') }}" class="block px-3 py-2 rounded hover:bg-slate-700">全局设置</a>
            <a href="{{ route('admin.releases.index') }}" class="block px-3 py-2 rounded hover:bg-slate-700">发布 / 回滚</a>
            <a href="{{ route('admin.reviews.index') }}" class="block px-3 py-2 rounded hover:bg-slate-700">
                双人复核
                @if(($pendingCount ?? 0) > 0)
                    <span class="ml-1 px-1.5 py-0.5 text-xs bg-red-500 rounded">{{ $pendingCount }}</span>
                @endif
            </a>
            <a href="{{ route('admin.audit-logs.index') }}" class="block px-3 py-2 rounded hover:bg-slate-700">审计日志</a>
            <a href="{{ route('admin.analytics.index') }}" class="block px-3 py-2 rounded hover:bg-slate-700">数据分析看板</a>
        </nav>
        <div class="px-3 py-3 border-t border-slate-700 text-xs text-slate-400">
            {{ auth('admin')->user()->name ?? '' }}
            <span class="block text-slate-500">{{ auth('admin')->user()->role ?? '' }}</span>
            <form method="POST" action="{{ route('admin.logout') }}" class="mt-2">
                @csrf
                <button class="text-slate-300 hover:text-white">退出登录</button>
            </form>
        </div>
    </aside>

    <!-- 主内容 -->
    <main class="flex-1 p-6">
        @if(session('success'))
            <div class="mb-4 px-4 py-2 bg-green-100 border border-green-300 text-green-800 rounded">
                {{ session('success') }}
            </div>
        @endif
        @if($errors->any())
            <div class="mb-4 px-4 py-2 bg-red-100 border border-red-300 text-red-800 rounded">
                <ul class="list-disc pl-5">
                    @foreach($errors->all() as $error)
                        <li>{{ $error }}</li>
                    @endforeach
                </ul>
            </div>
        @endif

        @yield('content')
    </main>
</div>
</body>
</html>
