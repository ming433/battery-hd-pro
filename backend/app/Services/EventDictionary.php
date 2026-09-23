<?php

namespace App\Services;

/**
 * 事件字典（PRD 附录 A）。
 *
 * 客户端与服务端共用同一份契约：未知事件名与超白名单属性一律拒绝，
 * 写入 analytics_events_rejected 并计数告警，避免 SDK bug 或恶意上报污染仓库。
 */
class EventDictionary
{
    public function isKnown(string $name): bool
    {
        return array_key_exists($name, config('analytics.events', []));
    }

    public function tier(string $name): ?string
    {
        return config("analytics.events.{$name}.tier");
    }

    public function isCritical(string $name): bool
    {
        return (bool) config("analytics.events.{$name}.critical", false);
    }

    /**
     * 是否免采样（T1 全部免采样；部分 T2 因作为指标分母也免采样）。
     */
    public function noSampling(string $name): bool
    {
        return (bool) config("analytics.events.{$name}.no_sampling", false);
    }

    /**
     * 过滤属性：仅保留白名单内字段，返回 [保留后的属性, 被剥离的键名列表]。
     */
    public function filterProperties(string $name, array $properties): array
    {
        $allowed = array_merge(
            config("analytics.events.{$name}.props", []),
            config('analytics.common_props', [])
        );

        $kept = [];
        $stripped = [];

        foreach ($properties as $key => $value) {
            if (in_array($key, $allowed, true)) {
                $kept[$key] = $value;
            } else {
                $stripped[] = $key;
            }
        }

        // 属性数量上限（PRD 10.1 原则 4）
        $max = config('analytics.limits.max_properties', 20);
        if (count($kept) > $max) {
            $overflow = array_slice(array_keys($kept), $max);
            foreach ($overflow as $key) {
                unset($kept[$key]);
                $stripped[] = $key;
            }
        }

        return [$kept, $stripped];
    }

    /**
     * 枚举与类型校验（PRD 10.2.5）。
     *
     * 返回 [修正后的属性, 违规项列表]，违规项格式：
     *   ['prop' => 'screen_name', 'reason' => 'not_in_enum', 'value' => 'xxx']
     *   ['prop' => 'is_pro',      'reason' => 'type_mismatch', 'expected' => 'boolean']
     *
     * 策略：违规属性**从事件中剔除**（而非整批拒绝），保证同批次其他事件不受影响；
     * 同时返回违规明细供写入 rejected 表与告警计数。
     */
    public function validateProperties(string $eventName, array $properties): array
    {
        $enums   = config('enums', []);
        $clean   = [];
        $invalid = [];

        foreach ($properties as $key => $value) {
            $rule = $this->enumRule($eventName, $key, $enums);

            if ($rule === null) {
                $clean[$key] = $value;
                continue;
            }

            $error = $this->checkRule($key, $value, $rule);

            if ($error === null) {
                $clean[$key] = $value;
            } else {
                $invalid[] = $error;
            }
        }

        return [$clean, $invalid];
    }

    /**
     * 取某事件某属性的枚举规则；无规则返回 null。
     */
    protected function enumRule(string $eventName, string $prop, array $enums): ?array
    {
        // 事件级覆盖优先（如 error_code 在广告/购买事件中取值域不同）
        $override = config("enums.event_overrides.{$eventName}.{$prop}");
        if ($override !== null && isset($enums[$override])) {
            return $enums[$override];
        }

        if (isset($enums[$prop]) && is_array($enums[$prop])) {
            return $enums[$prop];
        }

        // bool_props / number_props 是"属性名清单"型规则，需判断是否命中
        foreach (['bool_props', 'number_props'] as $group) {
            if (isset($enums[$group]['values']) && in_array($prop, $enums[$group]['values'], true)) {
                return $enums[$group];
            }
        }

        // 动态取值域（广告位等后台可配项）
        if (isset($enums['dynamic_props'][$prop])) {
            $values = $this->dynamicValues($prop);

            return $values === null ? null : ['type' => 'string', 'values' => $values, 'dynamic' => true];
        }

        // 格式约束（非枚举，防注入）
        if (isset($enums['format_props'][$prop])) {
            return ['type' => 'format', 'pattern' => $enums['format_props'][$prop]];
        }

        return null;
    }

    /**
     * 动态取值域（PRD：广告位由管理后台维护，不可静态枚举）。
     *
     * 返回 null 表示"暂无法获取取值域"，此时跳过校验（故障时放行优于误拒）。
     */
    protected function dynamicValues(string $prop): ?array
    {
        if ($prop !== 'placement_id') {
            return null;
        }

        try {
            return \Illuminate\Support\Facades\Cache::remember('bhd:placement_keys', 300, function () {
                return \App\Models\AdPlacement::query()
                    ->where('enabled', true)
                    ->pluck('placement_key')
                    ->all();
            });
        } catch (\Throwable $e) {
            // 数据库不可用时放行，避免广告埋点大面积被拒
            report($e);

            return null;
        }
    }

    /**
     * 按规则校验单个值，通过返回 null，否则返回违规描述。
     */
    protected function checkRule(string $prop, mixed $value, array $rule): ?array
    {
        // null 表示"该属性无值"（如首屏 previous_screen），跳过校验
        if ($value === null) {
            return null;
        }

        $type = $rule['type'] ?? 'string';

        if ($type === 'format') {
            if (! is_string($value) || ! preg_match($rule['pattern'], $value)) {
                return [
                    'prop'     => $prop,
                    'reason'   => 'format_mismatch',
                    'value'    => is_scalar($value) ? $value : gettype($value),
                    'expected' => $rule['pattern'],
                ];
            }

            return null;
        }

        if ($type === 'bool') {
            if (! is_bool($value)) {
                return [
                    'prop'     => $prop,
                    'reason'   => 'type_mismatch',
                    'expected' => 'boolean',
                    'value'    => is_scalar($value) ? $value : gettype($value),
                ];
            }

            return null;
        }

        if ($type === 'number') {
            if (! is_int($value) && ! is_float($value)) {
                return [
                    'prop'     => $prop,
                    'reason'   => 'type_mismatch',
                    'expected' => 'number',
                    'value'    => is_scalar($value) ? $value : gettype($value),
                ];
            }

            return null;
        }

        // string / int 枚举白名单（int 用宽松比较以兼容 JSON 中的数值类型）
        $values = $rule['values'] ?? [];
        $hit    = $type === 'int'
            ? in_array((int) $value, array_map('intval', $values), true)
            : in_array($value, $values, true);

        if (! $hit) {
            return [
                'prop'     => $prop,
                'reason'   => $type === 'int' ? 'not_in_enum' : 'not_in_enum',
                'value'    => is_scalar($value) ? $value : gettype($value),
                'expected' => $values,
            ];
        }

        return null;
    }

    /**
     * 高频维度抽列（PRD 10.4.3）。
     */
    public function extractColumns(string $name, array $properties): array
    {
        $map = [
            'screen_name'  => 'screen_name',
            'placement_id' => 'placement_id',
            'plan_id'      => 'plan_id',
            'ad_unit_id'   => 'ad_unit_id',
        ];

        $columns = [];
        foreach ($map as $prop => $column) {
            if (isset($properties[$prop]) && is_scalar($properties[$prop])) {
                $columns[$column] = (string) $properties[$prop];
            }
        }

        return $columns;
    }
}
