<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;

/**
 * 双人复核待办（PRD 10.5.7）。
 *
 * 高危字段变更不立即生效，先落此表，待另一名管理员 approve 后才落实。
 */
class AdPendingReview extends Model
{
    protected $fillable = [
        'target_type', 'target_id', 'field', 'action',
        'old_value', 'new_value', 'status',
        'requested_by', 'reviewed_by', 'reviewed_at', 'note',
    ];

    protected $casts = [
        'old_value'   => 'array',
        'new_value'   => 'array',
        'reviewed_at' => 'datetime',
    ];

    public function requestedBy(): BelongsTo
    {
        return $this->belongsTo(Admin::class, 'requested_by');
    }

    public function reviewedBy(): BelongsTo
    {
        return $this->belongsTo(Admin::class, 'reviewed_by');
    }

    public function isPending(): bool
    {
        return $this->status === 'pending';
    }

    /**
     * 是否已存在同一目标同一字段的待复核项（用于去重/取代）。
     */
    public static function openExists(string $targetType, ?int $targetId, ?string $field): bool
    {
        return static::query()
            ->where('target_type', $targetType)
            ->where('target_id', $targetId)
            ->where('field', $field)
            ->where('status', 'pending')
            ->exists();
    }
}
