<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Model;

/**
 * 配置发布版本（灰度 / 回滚，PRD 10.5.7）。
 */
class AdConfigRelease extends Model
{
    protected $fillable = [
        'version', 'snapshot_json', 'status', 'rollout_percent',
        'target_json', 'published_by', 'published_at', 'note',
    ];

    protected $casts = [
        'snapshot_json'  => 'array',
        'target_json'    => 'array',
        'published_at'   => 'datetime',
        'rollout_percent'=> 'integer',
    ];
}
