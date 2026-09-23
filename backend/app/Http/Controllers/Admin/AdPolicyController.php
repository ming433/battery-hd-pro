<?php

namespace App\Http\Controllers\Admin;

use App\Http\Controllers\Controller;
use App\Models\AdConfigAuditLog;
use App\Models\AdPlacement;
use App\Models\AdPolicy;
use App\Services\AdConfigResolver;
use Illuminate\Http\RedirectResponse;
use Illuminate\Http\Request;
use Illuminate\View\View;

/**
 * 广告策略管理（PRD 10.5.4 / 10.5.7）。
 *
 * config_json 含频次上限、触发场景白名单、加载超时、重试次数、熔断阈值。
 * 用结构化表单采集并组装为 JSON（避免手填 JSON 出错），非高危字段，立即生效 + 审计。
 */
class AdPolicyController extends Controller
{
    // 触发场景白名单（PRD 10.2.5 trigger_scene 枚举）
    public const TRIGGER_SCENES = [
        'app_cold_start', 'screen_switch', 'charge_complete', 'calibration_complete',
        'report_generated', 'feature_gate_exit', 'app_exit', 'manual_debug',
    ];

    public function index(): View
    {
        $policies = AdPolicy::with('placement')
            ->orderBy('placement_id')
            ->paginate(25);

        return view('admin.ad_policies.index', compact('policies'));
    }

    public function create(): View
    {
        $placements = AdPlacement::orderBy('sort_order')->get();
        $triggerScenes = self::TRIGGER_SCENES;

        return view('admin.ad_policies.form', [
            'policy'        => null,
            'placements'    => $placements,
            'triggerScenes' => $triggerScenes,
        ]);
    }

    public function store(Request $request): RedirectResponse
    {
        $request->merge(['country_code' => $request->input('country_code') ?: null]);
        $data = $this->validatePolicy($request);
        $data['config_json'] = $this->buildConfigJson($request);

        $policy = AdPolicy::create($data);

        AdConfigAuditLog::record('create', 'policy', $policy->id, null, $policy->toArray());
        app(AdConfigResolver::class)->forgetCache();

        return redirect()->route('admin.policies.index')
            ->with('success', '策略已创建。');
    }

    public function edit(AdPolicy $policy): View
    {
        $placements = AdPlacement::orderBy('sort_order')->get();
        $triggerScenes = self::TRIGGER_SCENES;

        return view('admin.ad_policies.form', compact('policy', 'placements', 'triggerScenes'));
    }

    public function update(Request $request, AdPolicy $policy): RedirectResponse
    {
        $request->merge(['country_code' => $request->input('country_code') ?: null]);
        $data = $this->validatePolicy($request);
        $data['config_json'] = $this->buildConfigJson($request);

        $before = $policy->toArray();
        $policy->update($data);

        AdConfigAuditLog::record('update', 'policy', $policy->id, $before, $policy->toArray());
        app(AdConfigResolver::class)->forgetCache();

        return redirect()->route('admin.policies.index')
            ->with('success', '策略已更新。');
    }

    public function destroy(AdPolicy $policy): RedirectResponse
    {
        $before = $policy->toArray();
        $policy->delete();

        AdConfigAuditLog::record('delete', 'policy', $policy->id, $before, null);
        app(AdConfigResolver::class)->forgetCache();

        return redirect()->route('admin.policies.index')
            ->with('success', '策略已删除。');
    }

    /**
     * 由结构化表单组装 config_json（仅含已填写的字段）。
     */
    private function buildConfigJson(Request $request): array
    {
        $json = [];

        $fc = [
            'per_session'           => $request->input('fc_per_session'),
            'per_day'               => $request->input('fc_per_day'),
            'min_interval_seconds'  => $request->input('fc_min_interval_seconds'),
        ];
        $fc = array_filter($fc, fn ($v) => $v !== null && $v !== '');
        if ($fc !== []) {
            $json['frequency_cap'] = array_map('intval', $fc);
        }

        $scenes = $request->input('trigger_scenes', []);
        if (is_array($scenes) && $scenes !== []) {
            $json['trigger_scenes'] = array_values($scenes);
        }

        foreach (['load_timeout_ms', 'retry_count'] as $k) {
            $v = $request->input($k);
            if ($v !== null && $v !== '') {
                $json[$k] = (int) $v;
            }
        }

        $cb = [
            'fail_threshold'   => $request->input('cb_fail_threshold'),
            'cooldown_seconds' => $request->input('cb_cooldown_seconds'),
        ];
        $cb = array_filter($cb, fn ($v) => $v !== null && $v !== '');
        if ($cb !== []) {
            $json['circuit_breaker'] = array_map('intval', $cb);
        }

        return $json;
    }

    private function validatePolicy(Request $request): array
    {
        return $request->validate([
            'placement_id'     => ['required', 'exists:ad_placements,id'],
            'country_code'     => ['nullable', 'string', 'size:2'],
            'app_version_min'  => ['nullable', 'string', 'max:20'],
            'app_version_max'  => ['nullable', 'string', 'max:20'],
            'user_segment'     => ['required', 'in:all,free,pro,new'],
            'enabled'          => ['boolean'],
            'priority'         => ['integer', 'min:0'],
            'fc_per_session'           => ['nullable', 'integer', 'min:0'],
            'fc_per_day'               => ['nullable', 'integer', 'min:0'],
            'fc_min_interval_seconds'  => ['nullable', 'integer', 'min:0'],
            'load_timeout_ms'          => ['nullable', 'integer', 'min:0'],
            'retry_count'              => ['nullable', 'integer', 'min:0'],
            'cb_fail_threshold'        => ['nullable', 'integer', 'min:0'],
            'cb_cooldown_seconds'      => ['nullable', 'integer', 'min:0'],
            'trigger_scenes'           => ['nullable', 'array'],
            'trigger_scenes.*'         => ['in:'.implode(',', self::TRIGGER_SCENES)],
        ]);
    }
}
