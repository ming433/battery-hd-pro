<?php

use Illuminate\Foundation\Inspiring;
use Illuminate\Support\Facades\Artisan;
use Illuminate\Support\Facades\Schedule;

Artisan::command('inspire', function () {
    $this->comment(Inspiring::quote());
})->purpose('Display an inspiring quote');

/*
|--------------------------------------------------------------------------
| 定时任务（PRD 10.4 / 10.7）
|--------------------------------------------------------------------------
| 由 deploy 中的 scheduler 容器执行 `php artisan schedule:run`。
| 任务都带 withoutOverlapping，避免上一轮没跑完时叠加堆积。
*/

// 分区预建：每月 1 号
Schedule::command('analytics:ensure-partitions')->dailyAt('01:00');

// 过期清理 + 会话兜底 + 数据删除请求：每天凌晨
Schedule::command('analytics:prune')->dailyAt('02:30')->withoutOverlapping();

// 异常设备识别：每天 03:00 跑前一天窗口，赶在业务看板产出前完成
Schedule::command('analytics:flag-suspect-devices --days=1')->dailyAt('03:00')->withoutOverlapping();

// 健康巡检：每 5 分钟。队列积压与 429 这类问题必须及时发现，
// 隔夜才发现意味着一整天的数据可能已经丢了。
Schedule::command('analytics:health-check --window=60')->everyFiveMinutes()->withoutOverlapping();

// RTDN 与客户端埋点核对：每天 04:00 核对昨日（PRD 10.4.7：差异 > 1% 告警）
Schedule::command('analytics:reconcile-subscriptions')->dailyAt('04:00')->withoutOverlapping();
