<?php

namespace App\Http\Middleware;

use Closure;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\Redis;
use Symfony\Component\HttpFoundation\Response;

/**
 * 事件数限流（PRD 10.4.6）。
 *
 * Laravel 的 RateLimiter 只按"请求数"计数，而事件上报需要按"事件条数"计费，
 * 否则远程调小 batch_size 后请求数暴涨会误触发限流。这里用 Redis INCRBY 按分钟窗口计数。
 */
class EventVolumeLimit
{
    public function handle(Request $request, Closure $next, int $maxEventsPerMinute = 600): Response
    {
        $device = $request->user();
        $identifier = $device?->id ?: $request->ip();

        $count = is_array($request->input('events')) ? count($request->input('events')) : 1;

        $window = now()->format('YmdHi');
        $key = sprintf('bhd:evtvol:%s:%s', $identifier, $window);

        $used = Redis::incrby($key, $count);
        Redis::expire($key, 120);

        if ($used > $maxEventsPerMinute) {
            // 429 计数（PRD 10.4.8）：429 比例是限流阈值是否合理的核心指标，
            // 持续走高说明 batch_size / 采样率配置需要调整，而不是简单加机器。
            $this->recordThrottle();

            return response()->json([
                'message' => 'Event volume limit exceeded.',
                'limit'   => $maxEventsPerMinute,
            ], 429)->header('Retry-After', 60);
        }

        // 成功请求同样计数：429 比例 = 429 / (429 + 成功)，缺了分母算不出比例
        $this->recordRequest();

        return $next($request);
    }

    private function recordThrottle(): void
    {
        $this->increment('throttled_429');
    }

    private function recordRequest(): void
    {
        $this->increment('requests_ok');
    }

    private function increment(string $field): void
    {
        try {
            $key = 'bhd:metrics:'.now()->format('Ymd');
            Redis::hincrby($key, $field, 1);
            Redis::expireat($key, now()->endOfDay()->addDay()->timestamp);
        } catch (\Throwable) {
            // 监控计数失败绝不能影响上报主流程
        }
    }
}
