<?php

namespace App\Http\Controllers\Admin;

use App\Http\Controllers\Controller;
use App\Models\AdConfigAuditLog;
use App\Models\AdPendingReview;
use App\Models\AdPlacement;
use App\Models\AdUnit;
use App\Services\AdConfigResolver;
use Illuminate\Http\RedirectResponse;
use Illuminate\Http\Request;
use Illuminate\View\View;

/**
 * 广告单元管理（PRD 10.5.4 / 10.5.7）。
 *
 * ⚠️ 高危字段 ad_unit_id 变更走双人复核：保存时不立即生效，写入 ad_pending_reviews
 * 标记为 pending，待另一名管理员确认后才写入 ad_units 并清缓存。
 * 其余字段（ecpm_floor / priority / enabled / 国家 / 平台 等）立即生效。
 */
class AdUnitController extends Controller
{
    // 与 AdUnit::isValid() 保持一致，避免校验通过却因格式不符被 Resolver 静默丢弃
    private const AD_UNIT_ID_REGEX = '/^ca-app-pub-\d{16}\/\d{10}$/';

    public function index(): View
    {
        $units = AdUnit::with('placement')
            ->orderBy('placement_id')
            ->orderBy('platform')
            ->orderBy('country_code')
            ->paginate(25);

        // 标记哪些单元有未决的 ad_unit_id 复核
        $pendingUnitIds = AdPendingReview::where('target_type', 'unit')
            ->whereNotNull('target_id')
            ->where('field', 'ad_unit_id')
            ->where('status', 'pending')
            ->pluck('target_id')
            ->all();

        // 待创建（create 型）的单元复核，也在列表顶部提示
        $pendingCreates = AdPendingReview::with('requestedBy')
            ->where('target_type', 'unit')
            ->whereNull('target_id')
            ->where('status', 'pending')
            ->orderByDesc('created_at')
            ->get();

        $placements = AdPlacement::orderBy('sort_order')->get();

        return view('admin.ad_units.index', compact(
            'units', 'pendingUnitIds', 'pendingCreates', 'placements'
        ));
    }

    public function create(): View
    {
        $placements = AdPlacement::orderBy('sort_order')->get();

        return view('admin.ad_units.form', ['unit' => null, 'placements' => $placements]);
    }

    public function store(Request $request): RedirectResponse
    {
        $request->merge(['country_code' => $request->input('country_code') ?: null]);
        $data = $this->validateUnit($request);

        // ad_unit_id 为高危字段：不立即建记录，提交双人复核
        $snapshot = $data;
        AdPendingReview::create([
            'target_type' => 'unit',
            'target_id'   => null,
            'field'       => null,
            'action'      => 'create',
            'old_value'   => null,
            'new_value'   => $snapshot,
            'status'      => 'pending',
            'requested_by'=> auth('admin')->id(),
            'note'        => '新建广告单元（含高危 ad_unit_id），待复核',
        ]);

        AdConfigAuditLog::record('create', 'unit', null, null,
            $snapshot + ['_status' => 'pending_review']);

        return redirect()->route('admin.units.index')
            ->with('success', '已提交新建单元，待另一名管理员复核后生效。');
    }

    public function edit(AdUnit $unit): View
    {
        $placements = AdPlacement::orderBy('sort_order')->get();

        return view('admin.ad_units.form', compact('unit', 'placements'));
    }

    public function update(Request $request, AdUnit $unit): RedirectResponse
    {
        $request->merge(['country_code' => $request->input('country_code') ?: null]);
        $data = $this->validateUnit($request, $unit);

        $immediate = $data;
        $pendingMessage = null;

        // ad_unit_id 变更 → 双人复核（取代同一单元已有的未决复核）
        if (array_key_exists('ad_unit_id', $data)
            && $data['ad_unit_id'] !== $unit->ad_unit_id) {
            $this->supersedeOpenReview('unit', $unit->id, 'ad_unit_id');

            AdPendingReview::create([
                'target_type' => 'unit',
                'target_id'   => $unit->id,
                'field'       => 'ad_unit_id',
                'action'      => 'update',
                'old_value'   => ['ad_unit_id' => $unit->ad_unit_id],
                'new_value'   => ['ad_unit_id' => $data['ad_unit_id']],
                'status'      => 'pending',
                'requested_by'=> auth('admin')->id(),
                'note'        => '修改 ad_unit_id，待复核',
            ]);

            unset($immediate['ad_unit_id']);
            $pendingMessage = 'ad_unit_id 变更已提交，待另一名管理员复核后生效。';
        }

        if (! empty($immediate)) {
            $before = $unit->toArray();
            $unit->update($immediate);
            AdConfigAuditLog::record('update', 'unit', $unit->id, $before, $unit->toArray());
            app(AdConfigResolver::class)->forgetCache();
        }

        return redirect()->route('admin.units.index')
            ->with('success', $pendingMessage ?? '广告单元已更新。');
    }

    /**
     * 启停（enabled 非高危，立即生效）。
     */
    public function toggle(Request $request, AdUnit $unit): RedirectResponse
    {
        $request->validate(['enabled' => ['required', 'boolean']]);

        $before = $unit->toArray();
        $unit->update(['enabled' => $request->boolean('enabled')]);

        AdConfigAuditLog::record('update', 'unit', $unit->id, $before, $unit->toArray());
        app(AdConfigResolver::class)->forgetCache();

        return redirect()->route('admin.units.index')
            ->with('success', $unit->enabled ? '已启用。' : '已停用。');
    }

    public function destroy(AdUnit $unit): RedirectResponse
    {
        $before = $unit->toArray();
        $unit->delete();

        AdConfigAuditLog::record('delete', 'unit', $unit->id, $before, null);
        app(AdConfigResolver::class)->forgetCache();

        return redirect()->route('admin.units.index')
            ->with('success', '广告单元已删除。');
    }

    private function validateUnit(Request $request, ?AdUnit $unit = null): array
    {
        return $request->validate([
            'placement_id' => ['required', 'exists:ad_placements,id'],
            'network'      => ['required', 'string', 'max:32'],
            'platform'     => ['required', 'in:android,ios'],
            'country_code' => ['nullable', 'string', 'size:2'],
            'ad_unit_id'   => ['required', 'string', 'regex:'.self::AD_UNIT_ID_REGEX],
            'enabled'      => ['boolean'],
            'ecpm_floor'   => ['nullable', 'numeric', 'min:0'],
            'priority'     => ['integer', 'min:0'],
        ]);
    }

    /**
     * 新建复核前，取消同一目标同一字段的未决复核（避免堆积）。
     */
    private function supersedeOpenReview(string $type, int $id, string $field): void
    {
        AdPendingReview::where('target_type', $type)
            ->where('target_id', $id)
            ->where('field', $field)
            ->where('status', 'pending')
            ->update(['status' => 'rejected', 'note' => '被新的复核请求取代']);
    }
}
