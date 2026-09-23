<?php

namespace App\Http\Controllers\Api\V1;

use App\Http\Controllers\Controller;
use App\Models\AnalyticsDevice;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;

/**
 * 设备注册（PRD 10.4.2）。
 *
 * 防护分层（初版接口完全开放，存在被批量伪造注册的风险）：
 *   ① IP 限流 20 次/小时（AppServiceProvider 中定义 analytics:register）
 *   ② Play Integrity attestation（token 由客户端提交，服务端解密校验）
 *   ③ 异常设备识别：注册后无任何核心事件或单日事件量超 P99.9 → is_suspect
 */
class RegisterController extends Controller
{
    public function store(Request $request): JsonResponse
    {
        $data = $request->validate([
            // 注意：Laravel 12 的 `Rule` 类没有 uuid() 静态方法，uuid 是字符串规则
            'device_uuid'      => ['required', 'string', 'max:36', 'uuid'],
            'app_version'      => ['nullable', 'string', 'max:20'],
            'os_version'       => ['nullable', 'string', 'max:10'],
            'locale'           => ['nullable', 'string', 'max:10'],
            'device_model'     => ['nullable', 'string', 'max:64'],
            'manufacturer'     => ['nullable', 'string', 'max:32'],
            'install_source'   => ['nullable', 'string', 'max:32'],
            'integrity_token'  => ['nullable', 'string'],   // Play Integrity
        ]);

        // TODO: 接入 Play Integrity —— 客户端获取 token，服务端调用 Google API 解密校验。
        // 注意默认配额约 1 万次/日，大规模注册需申请提额或对新设备抽样校验。

        $device = AnalyticsDevice::query()->firstOrNew(['device_uuid' => $data['device_uuid']]);

        $isNew = ! $device->exists;

        $device->fill([
            'app_version'    => $data['app_version'] ?? null,
            'os_version'     => $data['os_version'] ?? null,
            'locale'         => $data['locale'] ?? null,
            'device_model'   => $data['device_model'] ?? null,
            'manufacturer'   => $data['manufacturer'] ?? null,
            'install_source' => $data['install_source'] ?? null,
            'country'        => $this->country($request),
            'first_seen_at'  => $device->first_seen_at ?? now(),
            'last_seen_at'   => now(),
        ])->save();

        $expiresAt = now()->addDays(90);
        $token = $device->createToken('device', ['*'], $expiresAt);

        return response()->json([
            'token'            => $token->plainTextToken,
            'expires_at'       => $expiresAt->toIso8601String(),
            'device_id'        => $device->id,
            'is_new'           => $isNew,
            'country'          => $device->country,
            'server_timestamp' => now()->getTimestampMs(),
        ], $isNew ? 201 : 200);
    }

    /**
     * 国家判定：优先 Cloudflare 提供，无 CDN 时降级为空（后续可接 GeoIP）。
     * 埋点与广告配置必须使用同一取值（PRD P0-8）。
     */
    private function country(Request $request): ?string
    {
        $country = strtoupper((string) $request->header('CF-IPCountry', ''));

        return $country !== '' ? substr($country, 0, 2) : null;
    }
}
