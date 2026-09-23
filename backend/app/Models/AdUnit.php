<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;

class AdUnit extends Model
{
    protected $fillable = [
        'placement_id', 'network', 'platform', 'country_code',
        'ad_unit_id', 'enabled', 'ecpm_floor', 'priority',
    ];

    protected $casts = [
        'enabled'    => 'boolean',
        'ecpm_floor' => 'decimal:4',
    ];

    public function placement(): BelongsTo
    {
        return $this->belongsTo(AdPlacement::class, 'placement_id');
    }

    /**
     * ad_unit_id 格式校验（ca-app-pub-...），非法时不下发，避免空/错误 ID 狂刷。
     *
     * 正则统一取自 enums 字典（PRD 10.2.5），避免与埋点侧校验出现两套标准。
     * 注意：AdMob 单元 ID 位数会变（发布商 ID 15~20 位、单元 ID 9~12 位），
     * 此处刻意宽松，宁可放过也不误拒——误拒会导致该广告位直接无收入。
     */
    public function isValid(): bool
    {
        $pattern = config('enums.format_props.ad_unit_id', '/^ca-app-pub-\d{15,20}\/\d{9,12}$/');

        return (bool) preg_match($pattern, (string) $this->ad_unit_id);
    }
}
