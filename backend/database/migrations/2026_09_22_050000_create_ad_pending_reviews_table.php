<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    /**
     * 双人复核待办（PRD 10.5.7）。
     *
     * 四类高危变更（ad_unit_id / kill_switch / tag_for_child_directed /
     * max_ad_content_rating）保存时不立即生效，写入此表标记为 pending，
     * 需另一名管理员确认（approve）后才写入真实表并清缓存。
     *
     * 注意：本迁移为新增，不修改任何已有迁移文件。
     */
    public function up(): void
    {
        Schema::create('ad_pending_reviews', function (Blueprint $table) {
            $table->id();
            $table->string('target_type', 32);                 // unit / global_settings
            $table->unsignedBigInteger('target_id')->nullable(); // 关联记录 id（create 时为 null）
            $table->string('field', 64)->nullable();            // 具体字段（ad_unit_id / kill_switch ...）
            $table->enum('action', ['create', 'update'])->default('update');
            $table->json('old_value')->nullable();
            $table->json('new_value')->nullable();
            $table->enum('status', ['pending', 'approved', 'rejected'])->default('pending');
            $table->unsignedBigInteger('requested_by')->nullable();
            $table->unsignedBigInteger('reviewed_by')->nullable();
            $table->timestamp('reviewed_at')->nullable();
            $table->string('note', 255)->nullable();
            $table->timestamps();

            $table->index(['target_type', 'target_id', 'status']);
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('ad_pending_reviews');
    }
};
