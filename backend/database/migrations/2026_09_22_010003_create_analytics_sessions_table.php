<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    /**
     * 会话聚合表（由 Worker 从事件流推导，PRD 10.3.2）。
     * 服务端为会话边界的唯一权威：客户端不再单独上报会话开始/结束。
     */
    public function up(): void
    {
        Schema::create('analytics_sessions', function (Blueprint $table) {
            $table->string('session_id', 36)->primary();
            $table->unsignedBigInteger('device_id')->nullable();
            $table->timestamp('started_at')->nullable();
            $table->timestamp('ended_at')->nullable();      // 可空：由 Worker 兜底补写
            $table->unsignedInteger('screen_count')->default(0);
            $table->unsignedInteger('event_count')->default(0);
            $table->string('app_version', 20)->nullable();
            $table->string('country', 8)->nullable();
            $table->timestamps();

            $table->index(['device_id', 'started_at']);
            $table->index('started_at');
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('analytics_sessions');
    }
};
