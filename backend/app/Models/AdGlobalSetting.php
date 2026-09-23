<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Model;

/**
 * 广告全局设置（单表单行，id = 1）。
 * kill_switch 与 tag_for_child_directed 为高危字段，变更需双人复核。
 */
class AdGlobalSetting extends Model
{
    protected $fillable = [
        'ads_enabled', 'kill_switch', 'first_launch_grace_minutes',
        'tag_for_child_directed', 'max_ad_content_rating', 'npa_default',
        'config_ttl_seconds', 'updated_by',
    ];

    protected $casts = [
        'ads_enabled'            => 'boolean',
        'kill_switch'            => 'boolean',
        'tag_for_child_directed' => 'boolean',
        'npa_default'            => 'boolean',
    ];

    public static function current(): self
    {
        return static::firstOrCreate(['id' => 1]);
    }
}
