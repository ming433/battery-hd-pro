<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Model;

/**
 * 配置变更审计日志（PRD 10.5.7）。
 */
class AdConfigAuditLog extends Model
{
    public $timestamps = false;

    protected $fillable = [
        'operator_id', 'operator_name', 'action', 'target_type',
        'target_id', 'before_json', 'after_json', 'ip', 'created_at',
    ];

    protected $casts = [
        'before_json' => 'array',
        'after_json'  => 'array',
        'created_at'  => 'datetime',
    ];

    public static function record(string $action, string $targetType, ?int $targetId, ?array $before, ?array $after, ?string $operatorName = null): self
    {
        // operator_name 必须回落到当前登录管理员：审计日志的核心价值就是"谁改的"，
        // 留空会让日志无法追溯责任人。
        $operator = auth('admin')->user();

        return static::create([
            'operator_id'   => $operator?->id,
            'operator_name' => $operatorName ?? $operator?->name ?: $operator?->email,
            'action'        => $action,
            'target_type'   => $targetType,
            'target_id'     => $targetId,
            'before_json'   => $before,
            'after_json'    => $after,
            'ip'            => bhd_client_ip(),
            'created_at'    => now(),
        ]);
    }
}
