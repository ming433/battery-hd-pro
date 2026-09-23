<?php

namespace App\Services;

use App\Models\AdConfigRelease;
use App\Models\AdGlobalSetting;
use App\Models\AdPlacement;
use Illuminate\Support\Facades\Cache;

/**
 * 广告配置解析器（PRD 10.5）。
 *
 * 优先级链：L0 内置默认（客户端）→ L1 全局 → L2 国家 → L3 广告位 → L4 用户分群 → L5 运营覆盖。
 * 服务端负责 L1~L5 的生成，L0 由客户端 assets 提供。
 *
 * ── 配置来源：实时表 or 发布快照 ──────────────────────────────
 * 后台每次"发布"会生成一条 ad_config_releases 快照。客户端请求时：
 *   1. 先按设备分桶决定命中哪个 release（灰度）
 *   2. 再按该 release 的快照解析出最终配置
 *   3. 无任何 published release 时，回退到"实时表读取"（未发布状态下后台改动即时可见）
 *
 * 这样"发布/灰度/回滚"才真正影响下发结果，否则后台做的发布功能形同虚设。
 *
 * ── 缓存分层（关键设计）────────────────────────────────────
 * 快照内容只与 (version, country, is_pro, app_version) 有关，与设备无关；
 * 设备只影响"命中哪个 version"。因此分成两层缓存：
 *   ① release 列表（不含设备维度）→ 命中判定在内存里做，成本低
 *   ② 快照解析结果 → 按 version 维度缓存
 * 若把 device_uuid 直接拼进缓存 key，会产生 100×国家×分群 个条目，不可接受。
 */
class AdConfigResolver
{
    /**
     * @param  string  $deviceUuid  用于灰度分桶
     * @param  string  $country     服务端按 IP 判定（与埋点 country 必须同源）
     */
    public function resolve(string $deviceUuid, string $country, string $appVersion, bool $isPro): array
    {
        $release = $this->matchRelease($deviceUuid, $country, $appVersion);
        $version = $release?->version ?? 0;   // 0 = 实时模式（尚未发布任何版本）

        $cacheKey = sprintf(
            'bhd:adcfg:%d:%d:%s:%s:%s',
            $this->epoch(),
            $version,
            $country,
            $isPro ? 'pro' : 'free',
            $this->versionBucket($appVersion)
        );

        return Cache::remember($cacheKey, 60, fn () => $this->build($deviceUuid, $country, $appVersion, $isPro, $release));
    }

    /**
     * 构建最终下发给客户端的配置。
     */
    public function build(string $deviceUuid, string $country, string $appVersion, bool $isPro, ?AdConfigRelease $release = null): array
    {
        // ① 取数据源：快照优先，否则实时读表
        $source = $release?->snapshot_json ?: $this->liveSnapshot();

        $global    = $source['global'] ?? [];
        $placementRows = $source['placements'] ?? [];

        $configVersion = $release?->version ?? $this->fallbackVersion();
        $ttl = (int) ($global['config_ttl_seconds'] ?? 300);

        // ② L5 最高优先级：全局熔断 / 总开关关闭
        if (! empty($global['kill_switch']) || empty($global['ads_enabled'])) {
            return [
                'config_version' => $configVersion,
                'ttl_seconds'    => $ttl,
                'global'         => array_merge($this->globalPayload($global), ['ads_enabled' => false]),
                'placements'     => [],
                'overrides'      => [],
            ];
        }

        // ③ L4 用户分群：Pro 用户去广告
        if ($isPro) {
            return [
                'config_version' => $configVersion,
                'ttl_seconds'    => $ttl,
                'global'         => array_merge($this->globalPayload($global), ['ads_enabled' => false]),
                'placements'     => [],
                'overrides'      => [['when' => ['is_pro' => true], 'apply' => ['global' => ['ads_enabled' => false]]]],
            ];
        }

        // ④ L2/L3 逐广告位解析
        $placements = [];

        foreach ($placementRows as $row) {
            if (empty($row['enabled'])) {
                continue;
            }

            $unit = $this->pickUnit($row['units'] ?? [], $country);

            // 单元缺失或非法：该广告位关闭，绝不下发空/错误 ID（PRD：防止无效流量）
            if ($unit === null || ! $this->validUnitId($unit['ad_unit_id'] ?? '')) {
                continue;
            }

            $policy = $this->matchPolicy($row['policies'] ?? [], $country, $appVersion, $isPro);

            $placements[$row['placement_key']] = array_merge([
                'placement_id' => $row['placement_key'],
                'enabled'      => true,
                'network'      => $unit['network'] ?? 'admob',
                'ad_unit_id'   => $unit['ad_unit_id'],
                'ad_format'    => $row['ad_format'] ?? 'banner',
            ], is_array($policy['config_json'] ?? null) ? $policy['config_json'] : []);
        }

        return [
            'config_version' => $configVersion,
            'ttl_seconds'    => $ttl,
            'global'         => $this->globalPayload($global),
            'placements'     => $placements,
            'overrides'      => [],
        ];
    }

    /**
     * 决定该设备命中哪个已发布版本（PRD 10.5.7 灰度与回滚）。
     *
     * 规则（按 version 倒序）：
     *   1. target（国家 / 最低版本）不匹配 → 跳过
     *   2. rollout_percent < 100 → 按 hash(device_uuid) 分桶，未命中则跳过
     *   3. 第一个命中的 release 即为生效版本
     *   4. 全部未命中 → null（走实时模式）
     *
     * 回滚的实现：后台将目标 release 标为 rolled_back，它便不再参与匹配，
     * 设备自动回落到上一个仍然 published 的版本。
     */
    public function matchRelease(string $deviceUuid, string $country, string $appVersion): ?AdConfigRelease
    {
        $releases = $this->publishedReleases();

        foreach ($releases as $release) {
            if (! $this->targetMatches($release->target_json, $country, $appVersion)) {
                continue;
            }

            if (! $this->inRollout($deviceUuid, (int) $release->rollout_percent)) {
                continue;
            }

            return $release;
        }

        return null;
    }

    /**
     * 已发布版本列表（倒序），缓存 60 秒。release 表极小，全量读取成本低。
     */
    protected function publishedReleases(): \Illuminate\Support\Collection
    {
        return Cache::remember('bhd:adreleases:'.$this->epoch(), 60, function () {
            return AdConfigRelease::query()
                ->where('status', 'published')
                ->orderByDesc('version')
                ->get();
        });
    }

    /**
     * 灰度定向：country 白名单 + 最低版本。两者 AND 关系（PRD 10.5.4）。
     */
    protected function targetMatches(mixed $target, string $country, string $appVersion): bool
    {
        if (empty($target) || ! is_array($target)) {
            return true;   // 无定向 = 面向全部
        }

        $countries = $target['country'] ?? null;
        if (is_array($countries) && $countries !== [] && ! in_array($country, $countries, true)) {
            return false;
        }

        $minVersion = $target['app_version_min'] ?? null;
        if ($minVersion && version_compare($appVersion, (string) $minVersion, '<')) {
            return false;
        }

        return true;
    }

    /**
     * 单元选择：同国家优先，回退 country_code = NULL 的默认单元。
     */
    protected function pickUnit(array $units, string $country): ?array
    {
        $enabled = array_values(array_filter($units, fn ($u) => ! empty($u['enabled'])
            && ($u['platform'] ?? 'android') === 'android'));

        foreach ($enabled as $unit) {
            if (($unit['country_code'] ?? null) === $country) {
                return $unit;
            }
        }

        foreach ($enabled as $unit) {
            if (($unit['country_code'] ?? null) === null) {
                return $unit;
            }
        }

        return null;
    }

    protected function validUnitId(string $id): bool
    {
        $pattern = config('enums.format_props.ad_unit_id', '/^ca-app-pub-\d{15,20}\/\d{9,12}$/');

        return $id !== '' && preg_match($pattern, $id) === 1;
    }

    /**
     * 策略命中：国家 + 版本区间 + 分群，priority 大者优先。
     * 版本比较使用语义化比较（避免 1.10.0 < 1.9.0 的字符串比较错误）。
     */
    protected function matchPolicy(array $policies, string $country, string $appVersion, bool $isPro): ?array
    {
        $segment = $isPro ? 'pro' : 'free';
        $hit     = null;

        foreach ($policies as $policy) {
            if (empty($policy['enabled'])) {
                continue;
            }

            $policyCountry = $policy['country_code'] ?? null;
            if ($policyCountry !== null && $policyCountry !== $country) {
                continue;
            }

            if (! empty($policy['app_version_min']) && version_compare($appVersion, (string) $policy['app_version_min'], '<')) {
                continue;
            }

            if (! empty($policy['app_version_max']) && version_compare($appVersion, (string) $policy['app_version_max'], '>')) {
                continue;
            }

            if (! in_array($policy['user_segment'] ?? 'all', ['all', $segment], true)) {
                continue;
            }

            if ($hit === null || ($policy['priority'] ?? 0) > ($hit['priority'] ?? 0)) {
                $hit = $policy;
            }
        }

        return $hit;
    }

    /**
     * 实时快照（尚未发布任何版本时使用，后台改动即时可见）。
     */
    public function liveSnapshot(): array
    {
        $global = AdGlobalSetting::current();

        $placements = AdPlacement::query()
            ->where('enabled', true)
            ->with(['units', 'policies'])
            ->orderBy('sort_order')
            ->get()
            ->map(fn (AdPlacement $p) => [
                'placement_key' => $p->placement_key,
                'ad_format'     => $p->ad_format,
                'enabled'       => (bool) $p->enabled,
                'sort_order'    => (int) $p->sort_order,
                'units'         => $p->units->map(fn ($u) => [
                    'network'      => $u->network,
                    'platform'     => $u->platform,
                    'country_code' => $u->country_code,
                    'ad_unit_id'   => $u->ad_unit_id,
                    'enabled'      => (bool) $u->enabled,
                    'priority'     => (int) $u->priority,
                ])->all(),
                'policies'      => $p->policies->map(fn ($q) => [
                    'country_code'    => $q->country_code,
                    'user_segment'    => $q->user_segment,
                    'app_version_min' => $q->app_version_min,
                    'app_version_max' => $q->app_version_max,
                    'priority'        => (int) $q->priority,
                    'enabled'         => (bool) $q->enabled,
                    'config_json'     => $q->config_json,
                ])->all(),
            ])
            ->all();

        return [
            'global'     => array_merge($global->only([
                'ads_enabled', 'kill_switch', 'first_launch_grace_minutes',
                'tag_for_child_directed', 'max_ad_content_rating', 'npa_default',
            ]), ['config_ttl_seconds' => (int) $global->config_ttl_seconds]),
            'placements' => $placements,
        ];
    }

    private function globalPayload(array $global): array
    {
        return [
            'ads_enabled'                => (bool) ($global['ads_enabled'] ?? false),
            'kill_switch'                => (bool) ($global['kill_switch'] ?? false),
            'first_launch_grace_minutes' => (int) ($global['first_launch_grace_minutes'] ?? 10),
            'tag_for_child_directed'     => (bool) ($global['tag_for_child_directed'] ?? false),
            'max_ad_content_rating'      => $global['max_ad_content_rating'] ?? 'T',
            'npa'                        => (bool) ($global['npa_default'] ?? false),
        ];
    }

    /**
     * 最新已发布版本号（**仅供后台展示**）。
     *
     * 注意：灰度期间不同设备命中的版本不同（见 matchRelease），
     * 因此这个值不代表"所有客户端当前的版本"，后台展示时应配合 rollout_percent 一起看。
     */
    public function currentVersion(): int
    {
        return (int) (AdConfigRelease::query()
            ->where('status', 'published')
            ->orderByDesc('version')
            ->value('version') ?? 0);
    }

    /**
     * 实时模式下回给客户端的版本号：取最新发布版本号 +1 表示"草稿态"，
     * 便于客户端识别配置来源并在埋点中区分。
     */
    public function fallbackVersion(): int
    {
        $latest = AdConfigRelease::query()->orderByDesc('version')->value('version');

        return ((int) $latest) + 1;
    }

    /**
     * 灰度分桶：hash(device_uuid) % 100 < rollout_percent（PRD 10.5.6）。
     * 使用 crc32 保证跨进程、跨语言结果一致（客户端侧需同算法以便本地预判）。
     */
    public function inRollout(string $deviceUuid, int $percent): bool
    {
        if ($percent >= 100) {
            return true;
        }
        if ($percent <= 0) {
            return false;
        }

        return crc32($deviceUuid) % 100 < $percent;
    }

    /**
     * 缓存维度：按主版本分桶，避免每个补丁版本都生成一份缓存。
     */
    private function versionBucket(string $appVersion): string
    {
        $parts = explode('.', $appVersion);

        return ($parts[0] ?? '0').'.'.($parts[1] ?? '0');
    }

    /**
     * 缓存失效：递增 epoch 使所有配置缓存条目逻辑失效。
     *
     * 不用 Cache::flush()——那会清空整个缓存库（含埋点去重键、限流计数器），
     * 共享 Redis 环境下是生产事故。epoch 方案只影响广告配置这一组 key。
     */
    public function forgetCache(): void
    {
        if (! Cache::has('bhd:adcfg:epoch')) {
            Cache::forever('bhd:adcfg:epoch', 1);
        }

        Cache::increment('bhd:adcfg:epoch');
    }

    private function epoch(): int
    {
        return (int) (Cache::get('bhd:adcfg:epoch') ?? 1);
    }
}
