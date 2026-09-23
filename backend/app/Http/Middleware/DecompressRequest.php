<?php

namespace App\Http\Middleware;

use Closure;
use Illuminate\Http\Request;
use Symfony\Component\HttpFoundation\Response;

/**
 * 解压 `Content-Encoding: gzip` 的请求体。
 *
 * 客户端（埋点 SDK 的 ApiClient）在请求体超过 1KB 时会 gzip 压缩并带上该头，
 * 目的是让东南亚弱网下的批量上报体积降到约 1/5。
 *
 * 没有这个中间件会发生什么（真实踩过）：
 * Laravel 拿到的是压缩后的二进制，`batch_id` 与 `events` 都被判定为「缺失」，
 * 整批返回 422 `The batch id field is required.`。
 * 后果是——**上报量越大越必然失败**，只有不足 1KB 的极小批次才可能通过，
 * 表现为「偶尔能上报、正常使用就全失败」这种最难排查的形态。
 */
class DecompressRequest
{
    public function handle(Request $request, Closure $next): Response
    {
        $encoding = strtolower((string) $request->header('Content-Encoding', ''));

        if (! str_contains($encoding, 'gzip')) {
            return $next($request);
        }

        $raw = $request->getContent();
        $decoded = $raw === '' ? false : @gzdecode($raw);

        if ($decoded === false) {
            // 不静默吞掉：客户端把 4xx 视为不可重试，明确报错才能区分
            // 「客户端编码坏了」和「传输被截断」
            return response()->json(['message' => 'Malformed gzip request body'], 400);
        }

        $data = json_decode($decoded, true);

        if (! is_array($data)) {
            return response()->json(['message' => 'Request body must be a JSON object'], 400);
        }

        // 原始 body 是二进制，json bag 解析为空；merge 会写入当前 input source，
        // 这样后续 validate() 与 EventVolumeLimit 才能看到解压后的字段。
        $request->merge($data);

        return $next($request);
    }
}
