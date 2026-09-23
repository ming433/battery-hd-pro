<?php

namespace App\Http\Controllers\Api\V1;

use App\Http\Controllers\Controller;
use Illuminate\Http\JsonResponse;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Facades\Redis;

class HealthController extends Controller
{
    public function index(): JsonResponse
    {
        $redisOk = true;
        try {
            Redis::ping();
        } catch (\Throwable) {
            $redisOk = false;
        }

        $dbOk = true;
        try {
            DB::select('SELECT 1');
        } catch (\Throwable) {
            $dbOk = false;
        }

        return response()->json([
            'status'          => $dbOk && $redisOk ? 'ok' : 'degraded',
            'db'              => $dbOk ? 'ok' : 'down',
            'redis'           => $redisOk ? 'ok' : 'down',
            'server_timestamp'=> now()->getTimestampMs(),
        ], $dbOk && $redisOk ? 200 : 503);
    }
}
