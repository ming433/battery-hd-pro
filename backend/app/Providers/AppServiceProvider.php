<?php

namespace App\Providers;

use Illuminate\Cache\RateLimiting\Limit;
use Illuminate\Support\Facades\RateLimiter;
use Illuminate\Support\Facades\View;
use Illuminate\Support\ServiceProvider;

class AppServiceProvider extends ServiceProvider
{
    public function register(): void
    {
        //
    }

    public function boot(): void
    {
        $this->configureRateLimiting();

        // 侧边栏待复核数量徽标（仅后台布局共享）
        View::composer('admin.layouts.app', function ($view) {
            $view->with('pendingCount', \App\Models\AdPendingReview::where('status', 'pending')->count());
        });
    }

    /**
     * 限流策略（PRD 10.4.2 / 10.4.6）。
     */
    protected function configureRateLimiting(): void
    {
        // 设备注册：IP 维度 20 次/小时，防止批量伪造注册
        RateLimiter::for('analytics:register', function ($request) {
            return Limit::perHour(20)->by($request->ip());
        });

        // 事件上报：请求数为辅（10/分钟）
        RateLimiter::for('analytics:events', function ($request) {
            return Limit::perMinute(10)->by(optional($request->user())->id ?: $request->ip());
        });

        // 说明：事件数限流（600/分钟）按"条数"计费，Laravel 的 Limit 只按请求数计数，
        // 因此在 EventController 中用 Redis INCRBY 自行实现（见 EventVolumeLimit 中间件）。

        // 配置拉取：较宽松
        RateLimiter::for('config', function ($request) {
            return Limit::perMinute(30)->by(optional($request->user())->id ?: $request->ip());
        });

        // RTDN：Google 侧推送，量不大但可能突发重投，给足余量。
        // 真正的来源是 OIDC 校验，不靠限流兜底。
        RateLimiter::for('rtdn', function ($request) {
            return Limit::perMinute(300)->by($request->ip());
        });
    }
}
