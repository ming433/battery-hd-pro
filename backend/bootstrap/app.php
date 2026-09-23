<?php

use App\Console\Commands\EnsureAnalyticsPartitions;
use App\Console\Commands\PruneAnalytics;
use Illuminate\Foundation\Application;
use Illuminate\Foundation\Configuration\Exceptions;
use Illuminate\Foundation\Configuration\Middleware;
use Illuminate\Support\Facades\Schedule;

return Application::configure(basePath: dirname(__DIR__))
    ->withRouting(
        web: __DIR__.'/../routes/web.php',
        api: __DIR__.'/../routes/api.php',
        commands: __DIR__.'/../routes/console.php',
        health: '/up',
    )
    ->withMiddleware(function (Middleware $middleware) {
        $middleware->trustProxies(at: '*');
        $middleware->alias([
            'event.volume' => \App\Http\Middleware\EventVolumeLimit::class,
        ]);
    })
    ->withExceptions(function (Exceptions $exceptions) {
        // 纯 API 服务：全部返回 JSON
        $exceptions->shouldRenderJsonWhen(
            fn ($request, $e) => $request->is('api/*') || $request->expectsJson() || $request->is('up')
        );
    })
    ->withSchedule(function () {
        // 每月 1 日预建未来 3 个月的事件表分区（PRD 10.4.3）
        Schedule::command(EnsureAnalyticsPartitions::class, ['--months' => 3])
            ->monthlyOn(1, '02:10')
            ->name('analytics:partitions')
            ->withoutOverlapping();

        // 每日：清理过期数据 + 会话结束兜底 + 执行数据删除请求（PRD 10.7.3）
        Schedule::command(PruneAnalytics::class)
            ->dailyAt('03:10')
            ->name('analytics:prune')
            ->withoutOverlapping();
    })->create();
