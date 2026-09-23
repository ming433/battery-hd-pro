<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Model;

/**
 * 被拒事件（时钟偏移超限 / Schema 校验失败），仅用于监控告警，保留 30 天。
 */
class AnalyticsEventRejected extends Model
{
    /**
     * 必须显式指定：Laravel 会把 "Rejected" 复数化为 "analytics_event_rejecteds"，
     * 与迁移创建的表名 analytics_events_rejected 不一致。
     */
    protected $table = 'analytics_events_rejected';

    public $timestamps = false;

    protected $fillable = [
        'batch_id', 'device_id', 'event_name', 'reason', 'payload', 'created_at',
    ];

    protected $casts = [
        'payload'    => 'array',
        'created_at' => 'datetime',
    ];
}
