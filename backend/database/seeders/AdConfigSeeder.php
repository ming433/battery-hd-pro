<?php

namespace Database\Seeders;

use App\Models\AdPlacement;
use App\Models\AdPolicy;
use App\Models\AdUnit;
use Illuminate\Database\Seeder;

/**
 * 广告配置初始数据（幂等，PRD 10.5.4）。
 *
 * 注意：此处 ad_unit_id 使用 Google 官方测试单元，上线前必须在管理后台替换为真实单元。
 */
class AdConfigSeeder extends Seeder
{
    public function run(): void
    {
        $placements = [
            ['banner_home', '首页 Banner', 'banner', 10],
            ['interstitial_after_calibration', '校准完成后插屏', 'interstitial', 20],
        ];

        foreach ($placements as [$key, $name, $format, $sort]) {
            $placement = AdPlacement::query()->firstOrCreate(
                ['placement_key' => $key],
                ['name' => $name, 'ad_format' => $format, 'sort_order' => $sort, 'enabled' => true]
            );

            // 默认广告单元（country_code = NULL 表示全部国家）
            AdUnit::query()->firstOrCreate(
                [
                    'placement_id' => $placement->id,
                    'network'      => 'admob',
                    'platform'     => 'android',
                    'country_code' => null,
                ],
                [
                    'ad_unit_id' => $format === 'banner'
                        ? 'ca-app-pub-3940256099942544/6300978111'   // 官方测试 Banner
                        : 'ca-app-pub-3940256099942544/1033173712',  // 官方测试插屏
                    'enabled'  => true,
                    'priority' => 0,
                ]
            );

            $defaultConfig = $format === 'banner'
                ? ['refresh_interval_seconds' => 60, 'circuit_breaker' => ['fail_threshold' => 5, 'cooldown_seconds' => 600]]
                : [
                    'trigger_scenes' => ['calibration_completed', 'weekly_report_viewed'],
                    'frequency_cap'  => ['per_session' => 2, 'per_day' => 8, 'min_interval_seconds' => 180],
                    'load_timeout_ms'=> 5000,
                    'retry_count'    => 2,
                    'circuit_breaker'=> ['fail_threshold' => 3, 'cooldown_seconds' => 900],
                ];

            AdPolicy::query()->firstOrCreate(
                [
                    'placement_id' => $placement->id,
                    'country_code' => null,
                    'user_segment' => 'all',
                ],
                [
                    'config_json' => $defaultConfig,
                    'enabled'     => true,
                    'priority'    => 0,
                ]
            );
        }
    }
}
