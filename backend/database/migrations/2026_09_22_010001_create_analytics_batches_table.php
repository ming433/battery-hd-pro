<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    /**
     * 批次去重表（非分区表，PRD 10.4.3）。
     *
     * MySQL 分区表的每个唯一键都必须包含分区键，因此幂等去重索引无法建在按月分区的
     * analytics_events 上。本表以 batch_id 为主键承担去重职责：上报时先 INSERT IGNORE，
     * affected_rows = 0 即判定为重复批次。
     */
    public function up(): void
    {
        Schema::create('analytics_batches', function (Blueprint $table) {
            $table->string('batch_id', 36)->primary();
            $table->unsignedBigInteger('device_id')->nullable();
            $table->unsignedSmallInteger('event_count')->default(0);
            $table->string('app_version', 20)->nullable();
            $table->timestamp('received_at')->useCurrent();

            $table->index(['device_id', 'received_at']);
            $table->index('received_at');
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('analytics_batches');
    }
};
