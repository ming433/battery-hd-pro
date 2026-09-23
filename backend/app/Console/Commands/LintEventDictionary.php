<?php

namespace App\Console\Commands;

use App\Services\EventDictionary;
use Illuminate\Console\Command;

/**
 * 字典体检（PRD 附录 A）。
 *
 * 两个用途：
 *   1. lint  —— 检查事件字典与枚举字典的一致性，列出"未受约束的属性"供 PM 确认
 *   2. test  —— 跑内置冒烟用例，验证枚举/类型校验行为符合 PRD 10.2.5
 *
 * 建议接入 CI：字典变更后必须 lint 通过方可合入。
 */
class LintEventDictionary extends Command
{
    protected $signature = 'analytics:lint-dictionary
                            {--test : 跑枚举校验冒烟用例}
                            {--strict : 未受约束的属性视为错误（CI 用）}';

    protected $description = '校验事件字典与枚举字典的一致性（PRD 附录 A）';

    public function handle(EventDictionary $dictionary): int
    {
        return $this->option('test') ? $this->runSelfTest($dictionary) : $this->runLint($dictionary);
    }

    /**
     * 一致性检查。
     */
    protected function runLint(EventDictionary $dictionary): int
    {
        $events      = config('analytics.events', []);
        $enums       = config('enums', []);
        $commonProps = config('analytics.common_props', []);

        $unconstrained = [];  // 属性既无枚举约束、也不是公共属性
        $errors        = [];

        foreach ($events as $name => $def) {
            if (! isset($def['tier'])) {
                $errors[] = "事件 {$name} 缺少 tier";
            }

            foreach ($def['props'] ?? [] as $prop) {
                $rule = $this->lookupRule($name, $prop, $enums);

                if ($rule !== null) {
                    continue;
                }

                // 公共属性中的字符串型维度（如 device_model）是自由值，不强制枚举
                if (in_array($prop, $commonProps, true)) {
                    continue;
                }

                $unconstrained[$prop][] = $name;
            }
        }

        // 枚举字典结构自检
        foreach ($enums as $key => $rule) {
            // 这几个是"元配置"，不是取值域规则
            if (in_array($key, ['event_overrides', 'dynamic_props', 'format_props'], true)) {
                continue;
            }
            if (! is_array($rule) || ! isset($rule['type'], $rule['values'])) {
                $errors[] = "枚举 {$key} 结构非法，需含 type 与 values";
                continue;
            }
            if (! in_array($rule['type'], ['string', 'int', 'bool', 'number'], true)) {
                $errors[] = "枚举 {$key} 的 type 非法：{$rule['type']}";
            }
        }

        $this->info('事件总数：'.count($events).'　枚举规则：'.(count($enums) - 1));

        if ($errors !== []) {
            $this->error('结构错误：');
            foreach ($errors as $e) {
                $this->line('  ✗ '.$e);
            }

            return self::FAILURE;
        }

        $this->info('✓ 结构检查通过');

        if ($unconstrained !== []) {
            $this->newLine();
            $this->warn('以下属性未受枚举约束（如为自由文本属正常，否则应补枚举）：');
            foreach ($unconstrained as $prop => $usedIn) {
                $this->line(sprintf(
                    '  · %-24s 用于 %d 个事件：%s',
                    $prop,
                    count($usedIn),
                    implode(', ', array_slice($usedIn, 0, 4)).(count($usedIn) > 4 ? ' …' : '')
                ));
            }

            if ($this->option('strict')) {
                $this->newLine();
                $this->error('--strict 模式下未受约束属性视为失败');

                return self::FAILURE;
            }
        }

        return self::SUCCESS;
    }

    /**
     * 冒烟用例（PRD 10.2.5 关键约束的行为验证）。
     */
    protected function runSelfTest(EventDictionary $dictionary): int
    {
        $cases = [
            ['screen_viewed', ['screen_name' => 'home_dashboard', 'session_depth' => 3], 0, '合法页面名'],
            ['screen_viewed', ['screen_name' => 'my_page'], 1, '非法页面名'],
            ['battery_health_viewed', ['health_score' => 88, 'is_pro' => 'true'], 1, '布尔传字符串'],
            ['battery_health_viewed', ['health_score' => 88, 'is_pro' => true], 0, '布尔传 true'],
            ['screen_exited', ['screen_name' => 'settings', 'duration_ms' => '1200'], 1, '数值传字符串'],
            ['ad_load_failed', ['error_code' => 1, 'network_type' => 'wifi'], 0, '广告错误码合法'],
            ['ad_load_failed', ['error_code' => 99], 1, '广告错误码越界'],
            ['purchase_failed', ['plan_id' => 'pro_yearly', 'error_code' => 7], 0, '购买错误码 7 合法'],
            ['purchase_failed', ['plan_id' => 'pro_yearly', 'error_code' => 1], 0, '购买错误码 1(用户取消) 合法'],
            ['purchase_initiated', ['plan_id' => 'Pro_Yearly', 'price_local' => 299000], 1, 'plan_id 大小写错'],
            ['interstitial_shown', ['trigger_scene' => 'manual_debug'], 0, '调试场景合法'],
            ['interstitial_shown', ['trigger_scene' => 'random'], 1, '未定义触发场景'],
            ['app_hibernation_set', ['app_category' => 'other', 'target_app_count' => 5], 0, '分类兜底值'],
            ['app_hibernation_set', ['app_category' => 'com.facebook.katana'], 1, '★合规：传包名被拦截'],
            ['notification_clicked', ['notification_type' => 'weekly_report', 'time_since_received_ms' => 5000], 0, '通知类型合法'],
            // 新增约束的验证
            ['screen_viewed', ['screen_name' => 'settings', 'previous_screen' => null], 0, '首屏 previous_screen=null'],
            ['screen_viewed', ['screen_name' => 'settings', 'previous_screen' => 'fake'], 1, '非法 previous_screen'],
            ['onboarding_step_completed', ['step_index' => 1, 'step_name' => 'welcome'], 0, '引导步骤合法'],
            ['onboarding_step_completed', ['step_name' => 'step1'], 1, '引导步骤越界'],
            ['weekly_report_viewed', ['week_number' => 38, 'report_type' => 'weekly'], 0, '报告类型合法'],
            ['pro_page_viewed', ['source' => 'feature_gate', 'current_plan' => 'free'], 0, 'current_plan 合法'],
            ['pro_page_viewed', ['source' => 'feature_gate', 'current_plan' => 'pro_user'], 1, 'current_plan 越界'],
            ['feature_gate_shown', ['feature_name' => 'ad_free', 'current_plan' => 'free'], 0, 'feature_name 合法'],
            ['ad_revenue_paid', ['revenue_micros' => 1200, 'currency' => 'IDR'], 0, '币种 IDR 合法'],
            ['ad_revenue_paid', ['revenue_micros' => 1200, 'currency' => 'idr'], 1, '币种小写被拒'],
            // 格式约束（防注入，非枚举）
            ['banner_shown', ['ad_unit_id' => 'ca-app-pub-3940256099942544/6300978111'], 0, 'AdMob ID 格式合法'],
            ['banner_shown', ['ad_unit_id' => 'fake-unit-id'], 1, 'AdMob ID 格式非法'],
            ['purchase_completed', ['plan_id' => 'pro_yearly', 'order_id' => 'GPA.1234-5678-9012-34567'], 0, '订单号合法'],
            ['purchase_completed', ['plan_id' => 'pro_yearly', 'order_id' => '<script>alert(1)</script>'], 1, '★订单号注入被拦'],
        ];

        $pass = 0;
        $fail = 0;

        foreach ($cases as [$event, $props, $expect, $label]) {
            [, $invalid] = $dictionary->validateProperties($event, $props);
            $ok = count($invalid) === $expect;
            $ok ? $pass++ : $fail++;

            $this->line(sprintf(
                '  %s %-28s 违规=%d(期望%d)%s',
                $ok ? '<info>✓</info>' : '<fg=red>✗</fg=red>',
                $label,
                count($invalid),
                $expect,
                $invalid !== [] ? ' → '.implode(',', array_map(fn ($i) => $i['prop'].':'.$i['reason'], $invalid)) : ''
            ));
        }

        $this->newLine();
        $fail === 0
            ? $this->info("冒烟测试全部通过（{$pass} 项）")
            : $this->error("冒烟测试失败（{$fail} 项 / 共 ".($pass + $fail).' 项）');

        return $fail === 0 ? self::SUCCESS : self::FAILURE;
    }

    /**
     * 与 EventDictionary::enumRule 保持一致的查询逻辑（供 lint 使用）。
     */
    protected function lookupRule(string $eventName, string $prop, array $enums): ?array
    {
        $override = $enums['event_overrides'][$eventName][$prop] ?? null;
        if ($override !== null && isset($enums[$override])) {
            return $enums[$override];
        }

        if (isset($enums[$prop]) && is_array($enums[$prop])) {
            return $enums[$prop];
        }

        foreach (['bool_props', 'number_props'] as $group) {
            if (isset($enums[$group]['values']) && in_array($prop, $enums[$group]['values'], true)) {
                return $enums[$group];
            }
        }

        if (isset($enums['dynamic_props'][$prop]) || isset($enums['format_props'][$prop])) {
            return ['type' => 'dynamic_or_format'];
        }

        return null;
    }
}
