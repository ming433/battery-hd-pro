<?php

namespace App\Http\Controllers\Api\V1;

use App\Http\Controllers\Controller;
use App\Services\AdConfigResolver;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;

/**
 * 广告配置下发（PRD 10.5.5）。
 *
 * 与埋点配置解耦：用户关闭埋点后，广告配置仍可拉取、广告正常展示。
 * 支持 ETag / If-None-Match；TTL 默认 300s，配合前台轮询 + 前后台切换 + FCM 唤醒
 * 满足"15 分钟内 90% 生效""kill switch 5 分钟内 95% 生效"的验收要求。
 */
class AdConfigController extends Controller
{
    public function __construct(private readonly AdConfigResolver $resolver) {}

    public function show(Request $request): JsonResponse
    {
        /** @var \App\Models\AnalyticsDevice $device */
        $device = $request->user();

        $country = strtoupper((string) ($request->query('country')
            ?: $request->header('CF-IPCountry')
            ?: $device?->country
            ?: ''));

        $appVersion = (string) ($request->query('app_version')
            ?: $request->header('X-App-Version')
            ?: $device?->app_version
            ?: '1.0.0');

        $isPro = filter_var($request->query('is_pro', false), FILTER_VALIDATE_BOOL);

        $config = $this->resolver->resolve(
            deviceUuid: (string) $device?->device_uuid,
            country: substr($country, 0, 2),
            appVersion: $appVersion,
            isPro: $isPro,
        );

        $etag = '"v'.$config['config_version'].'"';

        if ($request->header('If-None-Match') === $etag) {
            return response()->json(null, 304);
        }

        return response()->json($config)
            ->header('ETag', $etag)
            ->header('Cache-Control', 'private, max-age=60');
    }
}
