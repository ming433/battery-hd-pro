<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Factories\HasFactory;
use Illuminate\Database\Eloquent\Model;
use Laravel\Sanctum\HasApiTokens;

class AnalyticsDevice extends Model
{
    use HasApiTokens, HasFactory;

    protected $fillable = [
        'device_uuid', 'app_version', 'os_version', 'locale', 'country',
        'device_model', 'manufacturer', 'install_source',
        'is_suspect', 'pending_deletion_at', 'first_seen_at', 'last_seen_at',
    ];

    protected $casts = [
        'is_suspect'          => 'boolean',
        'first_seen_at'       => 'datetime',
        'last_seen_at'        => 'datetime',
        'pending_deletion_at' => 'datetime',
    ];

    /**
     * 默认 Token 有效期 90 天（PRD 10.4.2）。
     */
    public function tokenExpiresAt(): \DateTimeInterface
    {
        return now()->addDays(90);
    }
}
