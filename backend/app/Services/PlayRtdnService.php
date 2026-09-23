<?php

namespace App\Services;

use Illuminate\Support\Facades\Cache;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Facades\Http;
use Illuminate\Support\Facades\Log;

/**
 * Google Play RTDN 处理（PRD 10.4.7）。
 *
 * 收入与订阅状态的真相源。客户端 purchase_completed 只能表达"客户端认为成功了"，
 * 覆盖不到退款、自动续订、宽限期恢复、离线购买，还可能被伪造；
 * 因此所有收入口径都必须以本服务落库的通知为准。
 *
 * 通知类型见 Google 文档：
 * https://developer.android.com/google/play/billing/rtdn-reference
 */
class PlayRtdnService
{
    /** notificationType → 可读名；数字本身在库表里可读性极差 */
    public const TYPES = [
        1  => 'SUBSCRIPTION_RECOVERED',
        2  => 'SUBSCRIPTION_RENEWED',
        3  => 'SUBSCRIPTION_CANCELED',
        4  => 'SUBSCRIPTION_PURCHASED',
        5  => 'SUBSCRIPTION_ON_HOLD',
        6  => 'SUBSCRIPTION_IN_GRACE_PERIOD',
        7  => 'SUBSCRIPTION_RESTARTED',
        8  => 'SUBSCRIPTION_PRICE_CHANGE_CONFIRMED',
        9  => 'SUBSCRIPTION_DEFERRED',
        10 => 'SUBSCRIPTION_PAUSED',
        11 => 'SUBSCRIPTION_PAUSE_SCHEDULE_CHANGED',
        12 => 'SUBSCRIPTION_REVOKED',
        13 => 'SUBSCRIPTION_EXPIRED',
    ];

    /** 权益类：收到即应授予/续期 Pro */
    public const ENTITLING = [1, 2, 4, 7, 8, 9];

    /** 失效类：应收回 Pro */
    public const REVOKING = [3, 12, 13, 5];

    /**
     * 处理一条 Pub/Sub push 消息。
     *
     * @param array $message {"message":{"data":"<base64>"...}, "subscription":"..."}
     * @return array{status: string, type?: int, duplicated?: bool}
     */
    public function handle(array $message): array
    {
        $data = $message['message']['data'] ?? null;

        if (! is_string($data) || $data === '') {
            return ['status' => 'invalid', 'reason' => 'missing data'];
        }

        $decoded = base64_decode($data, true);
        if ($decoded === false) {
            return ['status' => 'invalid', 'reason' => 'bad base64'];
        }

        $payload = json_decode($decoded, true);
        if (! is_array($payload)) {
            return ['status' => 'invalid', 'reason' => 'bad json'];
        }

        // 测试通知：Play Console 建订阅时会发一条，必须回 200，否则订阅会被标记为失败
        if (isset($payload['testNotification'])) {
            Log::info('[rtdn] test notification', ['version' => $payload['testNotification']['version'] ?? null]);

            return ['status' => 'test'];
        }

        $eventMillis = isset($payload['eventTimeMillis']) ? (int) $payload['eventTimeMillis'] : null;

        if (isset($payload['subscriptionNotification'])) {
            return $this->storeSubscription($payload['subscriptionNotification'], $payload, $eventMillis);
        }

        if (isset($payload['voidedPurchaseNotification']) && config('play.accept_voided')) {
            return $this->storeVoided($payload['voidedPurchaseNotification'], $payload, $eventMillis);
        }

        // 未知类型也必须回 200：否则 Pub/Sub 会无限重投同一条消息
        return ['status' => 'ignored', 'reason' => 'unsupported notification'];
    }

    private function storeSubscription(array $n, array $payload, ?int $eventMillis): array
    {
        $type = (int) ($n['notificationType'] ?? 0);
        $token = (string) ($n['purchaseToken'] ?? '');

        if ($token === '') {
            return ['status' => 'invalid', 'reason' => 'missing purchaseToken'];
        }

        $eventAt = $eventMillis ? now()->createFromTimestampMs($eventMillis) : null;

        $inserted = DB::table('subscription_events')->insertOrIgnore([
            'notification_type'     => $type,
            'notification_name'     => self::TYPES[$type] ?? null,
            'package_name'          => $payload['packageName'] ?? config('play.package_name'),
            'subscription_id'       => $n['subscriptionId'] ?? null,
            'purchase_token'        => $token,
            'event_time_millis'     => $eventMillis,
            'event_at'              => $eventAt,
            'raw_payload'           => json_encode($payload, JSON_UNESCAPED_UNICODE),
            'received_at'           => now(),
        ]);

        if ($inserted === 0) {
            // Pub/Sub 是 at-least-once，重复投递是常态，不算错误
            return ['status' => 'duplicate', 'type' => $type];
        }

        Log::info('[rtdn] '.(self::TYPES[$type] ?? 'UNKNOWN'), [
            'type'  => $type,
            'name'  => self::TYPES[$type] ?? null,
            'token' => substr($token, 0, 12).'…',
        ]);

        return ['status' => 'stored', 'type' => $type];
    }

    private function storeVoided(array $n, array $payload, ?int $eventMillis): array
    {
        // 一次性商品退款，用 0 表示（Google 的 voidedPurchase 没有数字类型）
        $token = (string) ($n['purchaseToken'] ?? '');
        if ($token === '') {
            return ['status' => 'invalid', 'reason' => 'missing purchaseToken'];
        }

        DB::table('subscription_events')->insertOrIgnore([
            'notification_type' => 0,
            'notification_name' => 'VOIDED_PURCHASE',
            'package_name'      => $payload['packageName'] ?? config('play.package_name'),
            'subscription_id'   => $n['productId'] ?? null,
            'purchase_token'    => $token,
            'event_time_millis' => $eventMillis,
            'event_at'          => $eventMillis ? now()->createFromTimestampMs($eventMillis) : null,
            'order_id'          => $n['orderId'] ?? null,
            'raw_payload'       => json_encode($payload, JSON_UNESCAPED_UNICODE),
            'received_at'       => now(),
        ]);

        return ['status' => 'stored', 'type' => 0];
    }

    /**
     * 校验 Pub/Sub push 的 OIDC token（RS256）。
     *
     * 本项目未引入 firebase/php-jwt，故用 openssl 直接验签：
     * 从 Google 的 JWKS 取公钥（缓存 1 小时），校验签名、audience 与过期时间。
     *
     * @return array{ok: bool, reason?: string}
     */
    public function verifyOidcToken(?string $token): array
    {
        if (! config('play.verify_oidc')) {
            return ['ok' => true, 'reason' => 'oidc verification disabled by config'];
        }

        if (! $token) {
            return ['ok' => false, 'reason' => 'missing authorization header'];
        }

        $parts = explode('.', $token);
        if (count($parts) !== 3) {
            return ['ok' => false, 'reason' => 'malformed token'];
        }

        [$headerB64, $payloadB64, $signatureB64] = $parts;

        $header = json_decode($this->b64urlDecode($headerB64), true);
        $claims = json_decode($this->b64urlDecode($payloadB64), true);

        if (! is_array($header) || ! is_array($claims)) {
            return ['ok' => false, 'reason' => 'undecodable token'];
        }

        if (($header['alg'] ?? '') !== 'RS256') {
            return ['ok' => false, 'reason' => 'unexpected alg '.(string) ($header['alg'] ?? '')];
        }

        // audience：push 订阅配置的 audience；未显式配置时退化为校验 issuer 即可
        $audience = config('play.oidc_audience');
        if ($audience && ($claims['aud'] ?? null) !== $audience) {
            return ['ok' => false, 'reason' => 'audience mismatch'];
        }

        if (isset($claims['exp']) && (int) $claims['exp'] < time()) {
            return ['ok' => false, 'reason' => 'token expired'];
        }

        $kid = $header['kid'] ?? null;
        $pem = $kid ? $this->googleCert($kid) : null;

        if (! $pem) {
            return ['ok' => false, 'reason' => 'no matching google cert'];
        }

        $signed = $headerB64.'.'.$payloadB64;
        $signature = $this->b64urlDecode($signatureB64);

        $ok = openssl_verify($signed, $signature, $pem, OPENSSL_ALGO_SHA256) === 1;

        return $ok ? ['ok' => true] : ['ok' => false, 'reason' => 'signature verify failed'];
    }

    /** Google OIDC 公钥（JWKS），缓存 1 小时 */
    private function googleCert(string $kid): ?string
    {
        return Cache::remember('google_oauth_certs_'.$kid, 3600, function () use ($kid) {
            try {
                $jwks = Http::timeout(5)
                    ->get('https://www.googleapis.com/oauth2/v3/certs')
                    ->json();

                foreach (($jwks['keys'] ?? []) as $key) {
                    if (($key['kid'] ?? null) === $kid && ($key['n'] ?? null) && ($key['e'] ?? null)) {
                        return $this->jwkToPem($key['n'], $key['e']);
                    }
                }
            } catch (\Throwable $e) {
                report($e);
            }

            return null;
        });
    }

    /** JWK(RS256) → PEM 公钥 */
    private function jwkToPem(string $n, string $e): ?string
    {
        $modulus = $this->b64urlDecode($n);
        $exponent = $this->b64urlDecode($e);

        if ($modulus === '' || $exponent === '') {
            return null;
        }

        $der = $this->derSequence(
            $this->derBigint($modulus).$this->derBigint($exponent)
        );

        $pem = "-----BEGIN PUBLIC KEY-----\n"
            .chunk_split(base64_encode($der), 64, "\n")
            ."-----END PUBLIC KEY-----\n";

        return openssl_pkey_get_public($pem) ? $pem : null;
    }

    private function derBigint(string $bytes): string
    {
        // 最高位为 1 时补 0x00，避免被当作负数
        if (ord($bytes[0] ?? "\0") & 0x80) {
            $bytes = "\x00".$bytes;
        }

        return "\x02".chr(strlen($bytes)).$bytes;
    }

    private function derSequence(string $content): string
    {
        $len = strlen($content);
        if ($len < 128) {
            return "\x30".chr($len).$content;
        }

        $bytes = '';
        while ($len > 0) {
            $bytes = chr($len & 0xFF).$bytes;
            $len >>= 8;
        }

        return "\x30".chr(0x80 | strlen($bytes)).$bytes.$content;
    }

    private function b64urlDecode(string $input): string
    {
        $input = strtr($input, '-_', '+/');
        $padding = strlen($input) % 4;

        return (string) base64_decode($padding ? $input.str_repeat('=', 4 - $padding) : $input, true);
    }
}
