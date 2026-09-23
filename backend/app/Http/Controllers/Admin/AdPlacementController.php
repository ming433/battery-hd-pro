<?php

namespace App\Http\Controllers\Admin;

use App\Http\Controllers\Controller;
use App\Models\AdConfigAuditLog;
use App\Models\AdPlacement;
use App\Services\AdConfigResolver;
use Illuminate\Http\RedirectResponse;
use Illuminate\Http\Request;
use Illuminate\View\View;

/**
 * 广告位管理（PRD 10.5.4 / 10.5.7）。
 *
 * placement_key 为稳定标识（埋点聚合维度），创建后不可修改。
 */
class AdPlacementController extends Controller
{
    public function index(): View
    {
        $placements = AdPlacement::withCount(['units', 'policies'])
            ->orderBy('sort_order')
            ->orderBy('id')
            ->paginate(20);

        return view('admin.ad_placements.index', compact('placements'));
    }

    public function create(): View
    {
        return view('admin.ad_placements.form', ['placement' => null]);
    }

    public function store(Request $request): RedirectResponse
    {
        $data = $request->validate([
            'placement_key' => ['required', 'string', 'max:64', 'regex:/^[a-z0-9_]+$/', 'unique:ad_placements,placement_key'],
            'name'          => ['required', 'string', 'max:64'],
            'ad_format'     => ['required', 'in:banner,interstitial,rewarded,native'],
            'description'   => ['nullable', 'string', 'max:255'],
            'enabled'       => ['boolean'],
            'sort_order'    => ['integer', 'min:0'],
        ]);

        $placement = AdPlacement::create($data);

        AdConfigAuditLog::record('create', 'placement', $placement->id, null, $placement->toArray());
        app(AdConfigResolver::class)->forgetCache();

        return redirect()->route('admin.placements.index')
            ->with('success', '广告位已创建。');
    }

    public function edit(AdPlacement $placement): View
    {
        return view('admin.ad_placements.form', compact('placement'));
    }

    public function update(Request $request, AdPlacement $placement): RedirectResponse
    {
        // placement_key 不允许修改（埋点聚合维度，改了看板曲线会断）
        $data = $request->validate([
            'name'        => ['required', 'string', 'max:64'],
            'ad_format'   => ['required', 'in:banner,interstitial,rewarded,native'],
            'description' => ['nullable', 'string', 'max:255'],
            'enabled'     => ['boolean'],
            'sort_order'  => ['integer', 'min:0'],
        ]);

        $before = $placement->toArray();
        $placement->update($data);

        AdConfigAuditLog::record('update', 'placement', $placement->id, $before, $placement->toArray());
        app(AdConfigResolver::class)->forgetCache();

        return redirect()->route('admin.placements.index')
            ->with('success', '广告位已更新。');
    }

    /**
     * 启停（immediate，非高危字段）。
     */
    public function toggle(Request $request, AdPlacement $placement): RedirectResponse
    {
        $request->validate(['enabled' => ['required', 'boolean']]);

        $before = $placement->toArray();
        $placement->update(['enabled' => $request->boolean('enabled')]);

        AdConfigAuditLog::record('update', 'placement', $placement->id, $before, $placement->toArray());
        app(AdConfigResolver::class)->forgetCache();

        return redirect()->route('admin.placements.index')
            ->with('success', $placement->enabled ? '已启用。' : '已停用。');
    }
}
