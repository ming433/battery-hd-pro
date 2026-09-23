<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

/**
 * Google Play RTDN 通知记录（PRD 10.4.7）。
 *
 * 客户端 purchase_completed 无法覆盖退款、续订、宽限期恢复、离线购买，
 * 且存在伪造风险，因此收入与订阅状态的真相源必须是服务端 RTDN。
 *
 * 幂等：Pub/Sub 是 at-least-once 投递，同一通知会重复推送，
 * 故对 (purchase_token, event_time_millis, notification_type) 建唯一键。
 */
return new class extends Migration
{
    public function up(): void
    {
        Schema::create('subscription_events', function (Blueprint $table) {
            $table->id();

            // 1..13，见 Google 文档；test_notification 单独用 0 表示
            $table->unsignedSmallInteger('notification_type');
            $table->string('notification_name', 48)->nullable();  // SUBSCRIPTION_RENEWED 等，便于人工阅读
            $table->string('package_name', 128)->nullable();
            $table->string('subscription_id', 128)->nullable();   // 商品 ID（plan_id 对齐用）
            $table->string('purchase_token', 512);
            $table->unsignedBigInteger('event_time_millis')->nullable();
            $table->timestamp('event_at')->nullable();             // 由 event_time_millis 换算，便于分区裁剪式查询

            // 与客户端埋点的关联：purchase_completed.order_id（PRD 10.4.7 要求按 order_id 对齐）
            $table->string('order_id', 128)->nullable();
            $table->unsignedBigInteger('device_id')->nullable();

            $table->json('raw_payload')->nullable();
            $table->timestamp('received_at')->useCurrent();

            $table->unique(['purchase_token', 'event_time_millis', 'notification_type'], 'uq_sub_event');
            $table->index('event_at');
            $table->index('order_id');
            $table->index(['notification_type', 'event_at']);
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('subscription_events');
    }
};
