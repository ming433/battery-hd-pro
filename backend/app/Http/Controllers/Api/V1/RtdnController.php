<?php

namespace App\Http\Controllers\Api\V1;

use App\Http\Controllers\Controller;
use App\Services\PlayRtdnService;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\Log;

/**
 * Google Play 实时开发者通知接收端点（PRD 10.4.7）。
 *
 * ── 为什么必须回 200 ──────────────────────────────────────────
 * Pub/Sub 是 at-least-once 投递：任何非 2xx 响应都会触发指数退避重投。
 * 因此**解析失败也要回 200**，否则一条坏消息会被无限重投，
 * 把错误放大成持续流量。真正的幂等由唯一键保证，而不是靠拒绝消息。
 *
 * ── 安全 ──────────────────────────────────────────────────────
 * 伪造一条 SUBSCRIPTION_PURCHASED 就等于白送 Pro，必须校验来源：
 * ① 订阅全名必须匹配配置；② 开启时必须校验 Pub/Sub 的 OIDC token。
 */
class RtdnController extends Controller
{
    public function __construct(private readonly PlayRtdnService $rtdn) {}

    public function store(Request $request): JsonResponse
    {
        $payload = $request->json()->all();

        // ① 订阅名校验：防止其它 Pub/Sub 主题的消息被当成订阅通知
        $expected = config('play.pubsub_subscription');
        $subscription = $payload['subscription'] ?? null;

        if ($expected && $subscription !== $expected) {
            Log::warning('[rtdn] subscription mismatch', [
                'got'      => $subscription,
                'expected' => $expected,
                'ip'       => $request->ip(),
            ]);

            return response()->json(['status' => 'rejected'], 200);
        }

        // ② OIDC 校验：生产环境必须开启
        $verification = $this->rtdn->verifyOidcToken($request->bearerToken());

        if (! $verification['ok']) {
            // 未配置订阅名 + 未开启校验时属于"裸奔"，仅告警不拒绝，
            // 否则本地联调与首次接入时会被自己的安全策略挡住。
            Log::warning('[rtdn] oidc verification failed', [
                'reason' => $verification['reason'] ?? null,
                'ip'     => $request->ip(),
            ]);

            if (! $expected) {
                Log::error('[rtdn] PLAY_PUBSUB_SUBSCRIPTION 未配置，端点未做来源校验，存在伪造风险');
            } else {
                return response()->json(['status' => 'unauthorized'], 200);
            }
        }

        $result = $this->rtdn->handle($payload);

        return response()->json(['status' => $result['status']], 200);
    }
}
