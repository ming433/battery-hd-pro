<?php

namespace App\Http\Controllers\Api\V1;

use App\Http\Controllers\Controller;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;

/**
 * 埋点远程配置（PRD 10.3.6）。
 * 支持 ETag / If-None-Match 增量更新，减少流量开销。
 */
class AnalyticsConfigController extends Controller
{
    public function show(Request $request): JsonResponse
    {
        $config = [
            'analytics_enabled'      => (bool) config('analytics.remote_defaults.analytics_enabled', true),
            'batch_size'             => (int) config('analytics.remote_defaults.batch_size', 20),
            'upload_interval_seconds'=> (int) config('analytics.remote_defaults.upload_interval_seconds', 30),
            'max_retry_count'        => (int) config('analytics.remote_defaults.max_retry_count', 5),
            'max_offline_events'     => (int) config('analytics.remote_defaults.max_offline_events', 500),
            'event_ttl_days'         => (int) config('analytics.limits.event_ttl_days', 7),
            'sampling_rate'          => (float) config('analytics.remote_defaults.sampling_rate', 1.0),
            'disabled_events'        => config('analytics.remote_defaults.disabled_events', []),
            'config_ttl_seconds'     => 3600,
            'server_timestamp'       => now()->getTimestampMs(),
        ];

        $etag = '"v'.md5(json_encode($config)).'"';

        if ($request->header('If-None-Match') === $etag) {
            return response()->json(null, 304);
        }

        return response()->json($config)->header('ETag', $etag);
    }
}
