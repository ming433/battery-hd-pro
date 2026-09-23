<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    /**
     * 被拒事件表：时钟偏移超限、Schema 校验失败等（PRD 10.3.4 / 10.4.6）。
     * 仅用于监控告警，保留 30 天。
     */
    public function up(): void
    {
        Schema::create('analytics_events_rejected', function (Blueprint $table) {
            $table->id();
            $table->string('batch_id', 36)->nullable();
            $table->unsignedBigInteger('device_id')->nullable();
            $table->string('event_name', 64)->nullable();
            $table->string('reason', 32);                   // clock_skew | unknown_event | enum_violation | too_large
            $table->json('payload')->nullable();
            $table->timestamp('created_at')->useCurrent();

            $table->index(['reason', 'created_at']);
            $table->index('event_name');
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('analytics_events_rejected');
    }
};
