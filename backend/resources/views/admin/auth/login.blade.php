<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>登录 · 广告配置管理后台</title>
    <script src="https://cdn.tailwindcss.com"></script>
</head>
<body class="bg-slate-100 flex items-center justify-center min-h-screen">
<div class="w-full max-w-sm bg-white p-8 rounded-lg shadow">
    <h1 class="text-xl font-semibold mb-6 text-center">广告配置管理后台</h1>

    @if($errors->any())
        <div class="mb-4 px-4 py-2 bg-red-100 border border-red-300 text-red-800 rounded text-sm">
            {{ $errors->first() }}
        </div>
    @endif

    <form method="POST" action="{{ route('login') }}">
        @csrf
        <div class="mb-4">
            <label class="block text-sm mb-1">邮箱</label>
            <input type="email" name="email" value="{{ old('email') }}"
                   class="w-full border rounded px-3 py-2" required autofocus>
        </div>
        <div class="mb-4">
            <label class="block text-sm mb-1">密码</label>
            <input type="password" name="password"
                   class="w-full border rounded px-3 py-2" required>
        </div>
        <label class="flex items-center mb-4 text-sm">
            <input type="checkbox" name="remember" class="mr-2"> 记住我
        </label>
        <button class="w-full bg-slate-900 text-white py-2 rounded hover:bg-slate-700">登录</button>
    </form>
</div>
</body>
</html>
