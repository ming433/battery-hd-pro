<?php

namespace App\Http\Controllers\Api\V1;

use App\Http\Controllers\Controller;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\DB;

/**
 * 同意凭证上报（PRD 10.7.2 / 10.7.3）。
 *
 * 合规要求「拿得出同意记录」，所以这里是 **append-only**：
 * 每次同意/撤回都追加一行，而不是覆盖最新状态。
 * 只存最新状态的话，一旦用户改口说"我从来没同意过"，没有任何证据能反驳。
 *
 * 注意本端点**不受埋点开关影响**：用户撤回同意后，这条"撤回"本身仍然要记录，
 * 否则撤回动作会因为没有上报而在服务端消失，形成"同意过但从未撤回"的假象。
 */
class ConsentController extends Controller
{
    public function store(Request $request): JsonResponse
    {
        $data = $request->validate([
            'action'            => ['required', 'string', 'in:granted,withdrawn,updated'],
            'analytics'         => ['required', 'boolean'],
            'personalized_ads'  => ['required', 'boolean'],
            'policy_version'    => ['required', 'string', 'max:32'],
            'decided_at'        => ['nullable', 'integer'],
        ]);

        /** @var \App\Models\AnalyticsDevice|null $device */
        $device = $request->user();

        DB::table('consent_records')->insert([
            'device_id'         => $device?->id,
            'device_uuid'       => $device?->device_uuid,
            'action'            => $data['action'],
            'analytics_consent' => (bool) $data['analytics'],
            'personalized_ads'  => (bool) $data['personalized_ads'],
            'policy_version'    => $data['policy_version'],
            'decided_at'        => isset($data['decided_at'])
                ? now()->createFromTimestampMs((int) $data['decided_at'])
                : now(),
            'ip'                => bhd_client_ip(),
            'created_at'        => now(),
        ]);

        return response()->json(['success' => true]);
    }
}
