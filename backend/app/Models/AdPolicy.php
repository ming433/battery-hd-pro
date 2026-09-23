<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;

class AdPolicy extends Model
{
    protected $fillable = [
        'placement_id', 'country_code', 'app_version_min', 'app_version_max',
        'user_segment', 'config_json', 'enabled', 'priority',
    ];

    protected $casts = [
        'config_json' => 'array',
        'enabled'     => 'boolean',
    ];

    public function placement(): BelongsTo
    {
        return $this->belongsTo(AdPlacement::class, 'placement_id');
    }
}
