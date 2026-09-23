<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Model;

/**
 * 会话聚合（由 Worker 从事件流推导，服务端为唯一权威，PRD 10.3.2）。
 */
class AnalyticsSession extends Model
{
    public $incrementing = false;
    protected $keyType = 'string';
    protected $primaryKey = 'session_id';

    protected $fillable = [
        'session_id', 'device_id', 'started_at', 'ended_at',
        'screen_count', 'event_count', 'app_version', 'country',
    ];

    protected $casts = [
        'started_at' => 'datetime',
        'ended_at'   => 'datetime',
    ];
}
