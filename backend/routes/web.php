<?php

use App\Http\Controllers\Admin\AdConfigAuditLogController;
use App\Http\Controllers\Admin\AdConfigReleaseController;
use App\Http\Controllers\Admin\AdGlobalSettingController;
use App\Http\Controllers\Admin\AdPendingReviewController;
use App\Http\Controllers\Admin\AdPlacementController;
use App\Http\Controllers\Admin\AdPolicyController;
use App\Http\Controllers\Admin\AdUnitController;
use App\Http\Controllers\Admin\AnalyticsDashboardController;
use App\Http\Controllers\Admin\AuthController;
use App\Http\Controllers\Admin\DashboardController;
use Illuminate\Support\Facades\Route;

/*
|--------------------------------------------------------------------------
| Web 路由
|--------------------------------------------------------------------------
| 管理后台（M4，PRD 10.5.7）挂载于 /admin。
| 全部走控制器，无闭包，以满足生产环境 route:cache。
*/

// 登录页：不挂 guest 中间件（本项目无 home 路由，避免已登录访问 /admin/login 时 500）；
// 未登录/已登录的跳转在 AuthController 内处理。
Route::get('/admin/login', [AuthController::class, 'showLogin'])->name('login');
Route::post('/admin/login', [AuthController::class, 'login']);

Route::post('/admin/logout', [AuthController::class, 'logout'])->name('admin.logout');

Route::middleware('auth:admin')->prefix('admin')->name('admin.')->group(function () {
    Route::get('/', [DashboardController::class, 'index'])->name('dashboard');

    Route::resource('placements', AdPlacementController::class)->except(['show', 'destroy']);
    Route::post('placements/{placement}/toggle', [AdPlacementController::class, 'toggle'])
        ->name('placements.toggle');

    Route::resource('units', AdUnitController::class)->except(['show']);
    Route::post('units/{unit}/toggle', [AdUnitController::class, 'toggle'])->name('units.toggle');

    Route::resource('policies', AdPolicyController::class)->except(['show']);

    Route::get('global-settings', [AdGlobalSettingController::class, 'edit'])->name('global-settings.edit');
    Route::put('global-settings', [AdGlobalSettingController::class, 'update'])->name('global-settings.update');

    // publish 必须显式注册：resource 的 except 把 store 之外的写操作都排除了，
    // 而 index 视图里用的是 admin.releases.publish（漏注册会 500）。
    Route::resource('releases', AdConfigReleaseController::class)->except(['show', 'edit', 'update', 'destroy']);
    Route::post('releases/publish', [AdConfigReleaseController::class, 'publish'])
        ->name('releases.publish');
    Route::post('releases/{release}/rollback', [AdConfigReleaseController::class, 'rollback'])
        ->name('releases.rollback');

    Route::get('reviews', [AdPendingReviewController::class, 'index'])->name('reviews.index');
    Route::post('reviews/{review}/approve', [AdPendingReviewController::class, 'approve'])->name('reviews.approve');
    Route::post('reviews/{review}/reject', [AdPendingReviewController::class, 'reject'])->name('reviews.reject');

    Route::get('audit-logs', [AdConfigAuditLogController::class, 'index'])->name('audit-logs.index');

    // 数据分析看板（PRD 10.6）
    Route::get('analytics', [AnalyticsDashboardController::class, 'index'])->name('analytics.index');
});
