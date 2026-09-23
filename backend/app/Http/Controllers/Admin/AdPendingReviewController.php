<?php

namespace App\Http\Controllers\Admin;

use App\Http\Controllers\Controller;
use App\Models\AdConfigAuditLog;
use App\Models\AdGlobalSetting;
use App\Models\AdPendingReview;
use App\Models\AdUnit;
use App\Services\AdConfigResolver;
use Illuminate\Http\RedirectResponse;
use Illuminate\Http\Request;
use Illuminate\View\View;
use Symfony\Component\HttpFoundation\Response;

/**
 * 双人复核工作台（PRD 10.5.7）。
 *
 * 复核人必须是「另一名」管理员（requested_by != reviewed_by），否则 403。
 * 通过 approve 才把高危变更落到真实表并清缓存；reject 仅关闭待办。
 */
class AdPendingReviewController extends Controller
{
    public function index(): View
    {
        $reviews = AdPendingReview::with(['requestedBy', 'reviewedBy'])
            ->orderByRaw("CASE WHEN status='pending' THEN 0 ELSE 1 END")
            ->orderByDesc('created_at')
            ->paginate(25);

        return view('admin.ad_pending_reviews.index', compact('reviews'));
    }

    public function approve(Request $request, AdPendingReview $review): RedirectResponse
    {
        if (! $review->isPending()) {
            return back()->withErrors(['review' => '该复核已处理。']);
        }

        if ($review->requested_by === auth('admin')->id()) {
            abort(Response::HTTP_FORBIDDEN, '不能复核自己提交的变更。');
        }

        $this->apply($review);

        $review->update([
            'status'      => 'approved',
            'reviewed_by' => auth('admin')->id(),
            'reviewed_at' => now(),
        ]);

        app(AdConfigResolver::class)->forgetCache();

        return redirect()->route('admin.reviews.index')
            ->with('success', '复核通过，变更已生效。');
    }

    public function reject(Request $request, AdPendingReview $review): RedirectResponse
    {
        if (! $review->isPending()) {
            return back()->withErrors(['review' => '该复核已处理。']);
        }

        if ($review->requested_by === auth('admin')->id()) {
            abort(Response::HTTP_FORBIDDEN, '不能复核自己提交的变更。');
        }

        $review->update([
            'status'      => 'rejected',
            'reviewed_by' => auth('admin')->id(),
            'reviewed_at' => now(),
            'note'        => trim(($review->note ?? '').' | 已拒绝'),
        ]);

        AdConfigAuditLog::record('update', $review->target_type, $review->target_id,
            $review->old_value, ['rejected' => true, 'field' => $review->field]);

        return redirect()->route('admin.reviews.index')
            ->with('success', '已拒绝该变更。');
    }

    /**
     * 将待复核的变更落到真实表。
     */
    private function apply(AdPendingReview $review): void
    {
        if ($review->target_type === 'unit') {
            if ($review->action === 'create') {
                $snapshot = $review->new_value;
                $unit = AdUnit::create([
                    'placement_id' => $snapshot['placement_id'],
                    'network'      => $snapshot['network'] ?? 'admob',
                    'platform'     => $snapshot['platform'] ?? 'android',
                    'country_code' => $snapshot['country_code'] ?? null,
                    'ad_unit_id'   => $snapshot['ad_unit_id'],
                    'enabled'      => (bool) ($snapshot['enabled'] ?? true),
                    'ecpm_floor'   => $snapshot['ecpm_floor'] ?? null,
                    'priority'     => (int) ($snapshot['priority'] ?? 0),
                ]);
                AdConfigAuditLog::record('create', 'unit', $unit->id, null, $unit->toArray());
            } else { // update ad_unit_id
                $unit = AdUnit::findOrFail($review->target_id);
                $before = $unit->toArray();
                $unit->update(['ad_unit_id' => $review->new_value['ad_unit_id']]);
                AdConfigAuditLog::record('update', 'unit', $unit->id, $before, $unit->toArray());
            }

            return;
        }

        if ($review->target_type === 'global_settings') {
            $global = AdGlobalSetting::current();
            $field = $review->field;
            $before = $global->toArray();
            $global->update([
                $field       => $review->new_value[$field],
                'updated_by' => auth('admin')->id(),
            ]);
            AdConfigAuditLog::record('update', 'global_settings', 1, $before, $global->toArray());

            return;
        }
    }
}
