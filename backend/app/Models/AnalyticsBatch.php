<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Model;

/**
 * 批次去重记录（PRD 10.4.3）。
 * 主键即 batch_id，用于 INSERT IGNORE 幂等判定。
 */
class AnalyticsBatch extends Model
{
    public $incrementing = false;
    protected $keyType = 'string';
    protected $primaryKey = 'batch_id';

    // 表只有 received_at，没有 created_at / updated_at，必须关闭时间戳
    public $timestamps = false;

    protected $fillable = ['batch_id', 'device_id', 'event_count', 'app_version', 'received_at'];

    protected $casts = ['received_at' => 'datetime'];
}
