<?php

namespace App\Http\Controllers\Admin;

use App\Http\Controllers\Controller;
use App\Models\AdConfigAuditLog;
use App\Models\AdConfigRelease;
use App\Models\AdGlobalSetting;
use App\Models\AdPendingReview;
use App\Models\AdPlacement;
use App\Models\AdPolicy;
use App\Models\AdUnit;
use App\Services\AdConfigResolver;
use Illuminate\View\View;

/**
 * 管理后台首页：概览 + 待复核提醒 + 最近审计。
 */
class DashboardController extends Controller
{
    public function index(): View
    {
        $stats = [
            'placements' => AdPlacement::count(),
            'units'      => AdUnit::count(),
            'policies'   => AdPolicy::count(),
            'pending'    => AdPendingReview::where('status', 'pending')->count(),
        ];

        $global = AdGlobalSetting::current();
        $resolver = app(AdConfigResolver::class);
        $currentVersion = $resolver->currentVersion();
        $latestRelease = AdConfigRelease::where('status', 'published')
            ->orderByDesc('version')->first();

        $pendingReviews = AdPendingReview::with(['requestedBy'])
            ->where('status', 'pending')
            ->orderByDesc('created_at')
            ->limit(10)
            ->get();

        $recentLogs = AdConfigAuditLog::orderByDesc('created_at')
            ->limit(15)
            ->get();

        return view('admin.dashboard.index', compact(
            'stats', 'global', 'currentVersion', 'latestRelease',
            'pendingReviews', 'recentLogs'
        ));
    }
}
