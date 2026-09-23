<?php

namespace App\Http\Controllers\Api\V1;

use App\Http\Controllers\Controller;
use App\Models\AdConfigAuditLog;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;

/**
 * 数据删除权（PRD 10.7.3）。
 *
 * 收到删除请求即置 pending_deletion_at 并停止入库（EventController 会拦截该设备），
 * 明细数据由 analytics:prune 定时任务在 30 天内异步清除。
 */
class MeController extends Controller
{
    public function destroy(Request $request): JsonResponse
    {
        /** @var \App\Models\AnalyticsDevice $device */
        $device = $request->user();

        $device->forceFill(['pending_deletion_at' => now()])->save();
        $device->tokens()->delete();

        AdConfigAuditLog::create([
            'operator_name' => 'device',
            'action'        => 'delete',
            'target_type'   => 'device',
            'target_id'     => $device->id,
            'before_json'   => ['device_uuid' => $device->device_uuid],
            'after_json'    => ['pending_deletion_at' => now()->toIso8601String()],
            'ip'            => bhd_client_ip(),
            'created_at'    => now(),
        ]);

        return response()->json([
            'success'   => true,
            'message'   => 'Deletion scheduled. Data will be removed within 30 days.',
            'deleted_at'=> now()->toIso8601String(),
        ]);
    }
}
