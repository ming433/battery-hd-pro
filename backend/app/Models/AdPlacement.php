<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\HasMany;

class AdPlacement extends Model
{
    protected $fillable = ['placement_key', 'name', 'ad_format', 'description', 'enabled', 'sort_order'];

    protected $casts = ['enabled' => 'boolean'];

    /**
     * 外键必须显式指定：Laravel 会按类名推断为 ad_placement_id，
     * 而迁移中的列名是 placement_id。
     */
    public function units(): HasMany
    {
        return $this->hasMany(AdUnit::class, 'placement_id');
    }

    public function policies(): HasMany
    {
        return $this->hasMany(AdPolicy::class, 'placement_id');
    }
}
