<?php

namespace App\Http\Controllers\Api\V1;

use App\Http\Controllers\Controller;
use App\Services\EventIngestor;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;

/**
 * 批量事件上报（PRD 10.4.4 / 10.4.5）。
 *
 * 限流口径（PRD 10.4.6）：事件数为主（600/分钟）+ 请求数为辅（10/分钟），
 * 避免远程调小 batch_size 后请求数暴涨误触发限流。429 必须携带 Retry-After。
 */
class EventController extends Controller
{
    public function __construct(private readonly EventIngestor $ingestor) {}

    public function store(Request $request): JsonResponse
    {
        $data = $request->validate([
            'batch_id'   => ['required', 'string', 'max:36'],
            'session_id' => ['nullable', 'string', 'max:36'],
            'events'     => ['required', 'array', 'min:1', 'max:'.config('analytics.limits.max_events_per_request', 50)],
            'events.*.event_name' => ['required', 'string', 'max:64'],
            'events.*.timestamp'  => ['nullable', 'integer'],
            'events.*.properties' => ['nullable', 'array'],
        ]);

        /** @var \App\Models\AnalyticsDevice $device */
        $device = $request->user();
        $batchId = $data['batch_id'];

        // 已提交删除请求的设备停止入库（PRD 10.7.3）
        if ($device?->pending_deletion_at) {
            return response()->json([
                'success'          => true,
                'received'         => 0,
                'rejected'         => 0,
                'duplicate'        => false,
                'server_timestamp' => now()->getTimestampMs(),
            ]);
        }

        // 幂等：重复批次直接返回，不重复入库（PRD 10.4.3）
        if (! $this->ingestor->claimBatch($batchId)) {
            return response()->json([
                'success'          => true,
                'received'         => 0,
                'rejected'         => 0,
                'duplicate'        => true,
                'server_timestamp' => now()->getTimestampMs(),
            ]);
        }

        [$accepted, $rejected] = $this->ingestor->validate($data['events'], $device?->id, $batchId);

        if ($accepted !== []) {
            $this->ingestor->dispatch([
                'batch_id'    => $batchId,
                'session_id'  => $data['session_id'] ?? null,
                'device_id'   => $device?->id,
                'app_version' => $request->header('X-App-Version') ?: $device?->app_version,
                'events'      => $accepted,
            ]);
        }

        return response()->json([
            'success'          => true,
            'received'         => count($accepted),
            'rejected'         => count($rejected),
            'duplicate'        => false,
            'server_timestamp' => now()->getTimestampMs(),
        ]);
    }
}
