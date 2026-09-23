<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Model;

/**
 * 事件明细（按月分区表，PRD 10.4.3）。
 * 注意：分区表不支持外键，关联关系仅在应用层维护；写入统一由 Worker 批量 INSERT 完成。
 */
class AnalyticsEvent extends Model
{
    public $timestamps = false;

    protected $fillable = [
        'batch_id', 'event_index', 'session_id', 'device_id', 'event_name',
        'properties', 'screen_name', 'placement_id', 'plan_id', 'ad_unit_id',
        'client_timestamp', 'server_timestamp', 'app_version',
    ];

    protected $casts = [
        'properties'       => 'array',
        'server_timestamp' => 'datetime',
    ];
}
