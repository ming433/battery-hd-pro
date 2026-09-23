<?php

namespace App\Console\Commands;

use App\Services\PlayRtdnService;
use Illuminate\Console\Command;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Facades\Log;

/**
 * RTDN 与客户端埋点每日核对（PRD 10.4.7）。
 *
 * 核对的意义不在于「数字对齐」，而在于尽早发现**整条链路断了**：
 * 例如 Pub/Sub 订阅配错、端点 500、客户端 SDK 没上报 purchase_completed。
 * 这类故障若只看一侧是完全无感的——RTDN 没数据会以为没人买，
 * 客户端没数据会以为收入掉了。
 *
 * 差异率 > 1% 触发告警（阈值可配 PLAY_RECONCILE_ALERT_RATE）。
 *
 * ⚠️ 局限：RTDN 通知本身**不带 order_id**，只有 purchase_token。
 * 精确到订单的对齐必须调用 Play Developer API（purchases.subscriptionsv2.get）
 * 用 token 换取 orderId——需要服务账号凭据，接入后应把下面按数量核对
 * 升级为按 order_id 的关联查询。
 */
class ReconcileSubscriptions extends Command
{
    protected $signature = 'analytics:reconcile-subscriptions
                            {--date= : 核对日期（Y-m-d），默认昨天}
                            {--json : 输出 JSON}';

    protected $description = '核对 RTDN 与客户端 purchase_completed 的差异率';

    public function handle(PlayRtdnService $rtdn): int
    {
        $date = $this->option('date')
            ? \Carbon\Carbon::parse($this->option('date'))
            : now()->subDay();

        $from = $date->copy()->startOfDay();
        $to = $date->copy()->endOfDay();

        // RTDN 侧：新购 + 续订（这两类对应客户端会产生的购买成功事件）
        $rtdnPurchased = DB::table('subscription_events')
            ->whereIn('notification_type', [4, 2, 7])   // PURCHASED / RENEWED / RESTARTED
            ->whereBetween('event_at', [$from, $to])
            ->count();

        // 客户端侧：purchase_completed 去重订单数
        $clientCompleted = DB::table('analytics_events')
            ->where('event_name', 'purchase_completed')
            ->whereBetween('server_timestamp', [$from, $to])
            ->distinct()
            ->count(DB::raw("properties->>'\$.order_id'"));

        $max = max($rtdnPurchased, $clientCompleted);
        $diff = abs($rtdnPurchased - $clientCompleted);
        $rate = $max > 0 ? round($diff / $max * 100, 3) : 0.0;

        $threshold = (float) config('play.reconcile_alert_rate', 1.0);
        $alerting = $rate > $threshold;

        // 类型分布，便于快速定位是"漏收"还是"错收"
        $types = DB::table('subscription_events')
            ->selectRaw('notification_type, COUNT(*) AS cnt')
            ->whereBetween('event_at', [$from, $to])
            ->groupBy('notification_type')
            ->pluck('cnt', 'notification_type')
            ->mapWithKeys(fn ($cnt, $type) => [
                PlayRtdnService::TYPES[$type] ?? ('TYPE_'.$type) => (int) $cnt,
            ]);

        $result = [
            'date'            => $date->toDateString(),
            'rtdn_purchases'  => $rtdnPurchased,
            'client_completed'=> $clientCompleted,
            'diff'            => $diff,
            'diff_rate'       => $rate,
            'threshold'       => $threshold,
            'alerting'        => $alerting,
            'rtdn_by_type'    => $types,
        ];

        if ($this->option('json')) {
            $this->line(json_encode($result, JSON_UNESCAPED_UNICODE | JSON_PRETTY_PRINT));
        } else {
            $this->line(sprintf('核对日期 %s', $date->toDateString()));
            $this->line(sprintf('  RTDN 购买/续订：%d', $rtdnPurchased));
            $this->line(sprintf('  客户端完成购买：%d', $clientCompleted));
            $this->line(sprintf('  差异：%d（%.3f%%，阈值 %.1f%%）', $diff, $rate, $threshold));

            foreach ($types as $name => $cnt) {
                $this->line(sprintf('  · %s：%d', $name, $cnt));
            }

            $alerting
                ? $this->error('差异率超阈值，请检查 RTDN 链路与客户端上报')
                : $this->info('差异率在阈值内');
        }

        if ($alerting) {
            Log::warning('[rtdn-reconcile] 差异率超阈值', $result);
        }

        return self::SUCCESS;
    }
}
