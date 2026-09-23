<?php

use App\Http\Controllers\Api\V1\AdConfigController;
use App\Http\Controllers\Api\V1\AnalyticsConfigController;
use App\Http\Controllers\Api\V1\EventController;
use App\Http\Controllers\Api\V1\ConsentController;
use App\Http\Controllers\Api\V1\HealthController;
use App\Http\Controllers\Api\V1\MeController;
use App\Http\Controllers\Api\V1\RegisterController;
use App\Http\Controllers\Api\V1\RtdnController;
use App\Http\Middleware\DecompressRequest;
use App\Http\Middleware\EventVolumeLimit;
use Illuminate\Support\Facades\Route;

/*
|--------------------------------------------------------------------------
| Battery HD Pro API v1（PRD F-010）
|--------------------------------------------------------------------------
| 埋点配置与广告配置分属两个端点：关闭埋点后广告仍需正常拉取与展示。
*/

Route::prefix('v1')->group(function () {
    Route::get('/health', [HealthController::class, 'index']);

    // 设备注册：无 Token，依赖 Play Integrity + IP 限流
    Route::post('/analytics/register', [RegisterController::class, 'store'])
        ->middleware('throttle:analytics:register');

    // Google Play RTDN：收入与订阅状态的真相源（PRD 10.4.7）
    // 无 Sanctum 认证，改用 Pub/Sub 订阅名 + OIDC token 校验来源
    Route::post('/play/rtdn', [RtdnController::class, 'store'])
        ->middleware('throttle:rtdn');

    Route::middleware('auth:sanctum')->group(function () {
        // 批量事件上报：按条数限流（600/分钟）+ 请求数限流（10/分钟）
        // DecompressRequest 必须排在最前：客户端 >1KB 的批体会 gzip，
        // 先解压 EventVolumeLimit 才能按真实条数计费、validate() 才能看到字段
        Route::post('/analytics/events', [EventController::class, 'store'])
            ->middleware([DecompressRequest::class, EventVolumeLimit::class.':600', 'throttle:analytics:events']);

        // 删除我的数据（合规：数据删除权）
        Route::delete('/analytics/me', [MeController::class, 'destroy']);

        // 同意凭证（合规：必须能举证用户同意过）。独立于埋点开关：
        // 撤回同意这条记录本身也必须能上报，否则服务端看不到撤回动作。
        Route::post('/analytics/consent', [ConsentController::class, 'store']);

        // 远程配置（埋点与广告解耦）
        Route::get('/config/analytics', [AnalyticsConfigController::class, 'show'])
            ->middleware('throttle:config');
        Route::get('/config/ads', [AdConfigController::class, 'show'])
            ->middleware('throttle:config');
    });
});
