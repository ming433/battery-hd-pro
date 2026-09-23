<?php

namespace App\Http\Controllers\Admin;

use App\Http\Controllers\Controller;
use App\Models\AdConfigAuditLog;
use App\Models\AdGlobalSetting;
use App\Models\AdPendingReview;
use App\Services\AdConfigResolver;
use Illuminate\Http\RedirectResponse;
use Illuminate\Http\Request;
use Illuminate\View\View;

/**
 * 全局设置（PRD 10.5.3 / 10.5.7）。
 *
 * 高危字段 kill_switch / tag_for_child_directed / max_ad_content_rating 变更走双人复核，
 * 其余字段（ads_enabled / 免广告时长 / npa / ttl）立即生效。
 * tag_for_child_directed 与 max_ad_content_rating 设错会被 AdMob 直接停号，界面需醒目提示。
 */
class AdGlobalSettingController extends Controller
{
    // 高危字段（PRD 10.5.7 双人复核）
    private const SENSITIVE = ['kill_switch', 'tag_for_child_directed', 'max_ad_content_rating'];

    public function edit(): View
    {
        $global = AdGlobalSetting::current();

        $pendingReviews = AdPendingReview::with('requestedBy')
            ->where('target_type', 'global_settings')
            ->where('status', 'pending')
            ->orderByDesc('created_at')
            ->get();

        // 待复核字段 → 拟生效值，便于在表单上提示
        $pendingValues = [];
        foreach ($pendingReviews as $r) {
            if ($r->field && array_key_exists($r->field, $r->new_value ?? [])) {
                $pendingValues[$r->field] = $r->new_value[$r->field];
            }
        }

        return view('admin.ad_global_settings.edit', compact('global', 'pendingReviews', 'pendingValues'));
    }

    public function update(Request $request): RedirectResponse
    {
        $global = AdGlobalSetting::current();

        $data = $request->validate([
            'ads_enabled'                => ['boolean'],
            'kill_switch'                => ['boolean'],
            'first_launch_grace_minutes' => ['integer', 'min:0'],
            'tag_for_child_directed'     => ['boolean'],
            'max_ad_content_rating'      => ['in:G,PG,T,MA'],
            'npa_default'                => ['boolean'],
            'config_ttl_seconds'         => ['integer', 'min:30'],
        ]);

        $immediate = [];
        $pendingMessages = [];

        foreach ($data as $field => $value) {
            if (in_array($field, self::SENSITIVE, true)) {
                // 仅当值确实变化才提交复核
                if ($this->castValue($field, $value) != $global->$field) {
                    $this->supersedeOpenReview('global_settings', 1, $field);
                    AdPendingReview::create([
                        'target_type' => 'global_settings',
                        'target_id'   => 1,
                        'field'       => $field,
                        'action'      => 'update',
                        'old_value'   => [$field => $global->$field],
                        'new_value'   => [$field => $this->castValue($field, $value)],
                        'status'      => 'pending',
                        'requested_by'=> auth('admin')->id(),
                        'note'        => '修改高危全局字段，待复核',
                    ]);
                    $pendingMessages[] = $field;
                }
            } else {
                $immediate[$field] = $this->castValue($field, $value);
            }
        }

        if (! empty($immediate)) {
            $before = $global->toArray();
            $immediate['updated_by'] = auth('admin')->id();
            $global->update($immediate);
            AdConfigAuditLog::record('update', 'global_settings', 1, $before, $global->toArray());
            app(AdConfigResolver::class)->forgetCache();
        }

        if ($pendingMessages !== []) {
            return redirect()->route('admin.global-settings.edit')
                ->with('success', '高危字段（'.implode(', ', $pendingMessages).'）已提交，待另一名管理员复核后生效。');
        }

        return redirect()->route('admin.global-settings.edit')
            ->with('success', '全局设置已更新。');
    }

    /**
     * 复选框布尔值经 validate 后为字符串 '0'/'1'，转回布尔以正确比较与写入。
     */
    private function castValue(string $field, $value)
    {
        if (in_array($field, ['kill_switch', 'tag_for_child_directed', 'ads_enabled', 'npa_default'], true)) {
            return filter_var($value, FILTER_VALIDATE_BOOLEAN);
        }

        return $value;
    }

    private function supersedeOpenReview(string $type, int $id, string $field): void
    {
        AdPendingReview::where('target_type', $type)
            ->where('target_id', $id)
            ->where('field', $field)
            ->where('status', 'pending')
            ->update(['status' => 'rejected', 'note' => '被新的复核请求取代']);
    }
}
