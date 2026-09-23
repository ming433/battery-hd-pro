<?php

namespace App\Http\Controllers\Admin;

use App\Http\Controllers\Controller;
use App\Models\AdConfigAuditLog;
use App\Models\AdConfigRelease;
use App\Models\AdGlobalSetting;
use App\Models\AdPlacement;
use App\Services\AdConfigResolver;
use Illuminate\Http\RedirectResponse;
use Illuminate\Http\Request;
use Illuminate\View\View;

/**
 * 灰度发布与回滚（PRD 10.5.4 / 10.5.7）。
 *
 * 发布：对当前生效配置做快照并写入 ad_config_releases（status=published），
 * 支持 rollout_percent 灰度；一键回滚到任一历史版本。
 * 注意：AdConfigResolver 当前按「最新 published 版本」提供 config_version，
 * 灰度分桶由客户端按 version 命中；发布/回滚后清缓存。
 */
class AdConfigReleaseController extends Controller
{
    public function index(): View
    {
        $releases = AdConfigRelease::orderByDesc('version')->paginate(20);
        $currentVersion = app(AdConfigResolver::class)->currentVersion();

        return view('admin.ad_config_releases.index', compact('releases', 'currentVersion'));
    }

    public function publish(Request $request): RedirectResponse
    {
        $request->merge(['target_json' => $request->input('target_json') ?: null]);
        $data = $request->validate([
            'rollout_percent' => ['required', 'integer', 'between:1,100'],
            'note'            => ['nullable', 'string', 'max:255'],
            'target_json'     => ['nullable', 'json'],
        ]);

        $version = (int) (AdConfigRelease::max('version') ?? 0) + 1;

        $release = AdConfigRelease::create([
            'version'         => $version,
            'snapshot_json'   => $this->buildSnapshot(),
            'status'          => 'published',
            'rollout_percent' => $data['rollout_percent'],
            'target_json'     => $data['target_json'] ? json_decode($data['target_json'], true) : null,
            'published_by'    => auth('admin')->id(),
            'published_at'    => now(),
            'note'            => $data['note'],
        ]);

        AdConfigAuditLog::record('publish', 'release', $release->id,
            null, $release->toArray());
        app(AdConfigResolver::class)->forgetCache();

        return redirect()->route('admin.releases.index')
            ->with('success', "已发布配置版本 v{$version}（灰度 {$data['rollout_percent']}%）。");
    }

    /**
     * 回滚到指定版本（将其设为 published，其余 published 置为 rolled_back）。
     */
    public function rollback(AdConfigRelease $release): RedirectResponse
    {
        AdConfigRelease::where('status', 'published')->update(['status' => 'rolled_back']);

        $release->update(['status' => 'published', 'published_at' => now()]);

        AdConfigAuditLog::record('rollback', 'release', $release->id,
            null, ['rolled_back_to_version' => $release->version]);
        app(AdConfigResolver::class)->forgetCache();

        return redirect()->route('admin.releases.index')
            ->with('success', "已回滚到版本 v{$release->version}。");
    }

    /**
     * 当前 DB 配置的结构化快照。
     */
    private function buildSnapshot(): array
    {
        $global = AdGlobalSetting::current();

        $placements = AdPlacement::with(['units', 'policies'])
            ->orderBy('sort_order')
            ->get()
            ->map(function (AdPlacement $p) {
                return [
                    'placement_key' => $p->placement_key,
                    'name'          => $p->name,
                    'ad_format'     => $p->ad_format,
                    'enabled'       => $p->enabled,
                    'units'         => $p->units->map(fn ($u) => $u->only([
                        'placement_id', 'network', 'platform', 'country_code',
                        'ad_unit_id', 'enabled', 'ecpm_floor', 'priority',
                    ]))->all(),
                    'policies'      => $p->policies->map(fn ($po) => $po->only([
                        'country_code', 'app_version_min', 'app_version_max',
                        'user_segment', 'config_json', 'enabled', 'priority',
                    ]))->all(),
                ];
            })
            ->all();

        return [
            'generated_at' => now()->toDateTimeString(),
            'global'       => $global->toArray(),
            'placements'   => $placements,
        ];
    }
}
