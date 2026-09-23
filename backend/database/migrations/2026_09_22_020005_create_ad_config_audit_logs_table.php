<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    /**
     * 配置变更审计日志（PRD 10.5.7）。
     * 广告配置误操作直接损失收入，必须可追溯操作人与前后值。
     */
    public function up(): void
    {
        Schema::create('ad_config_audit_logs', function (Blueprint $table) {
            $table->id();
            $table->unsignedBigInteger('operator_id')->nullable();
            $table->string('operator_name', 64)->nullable();
            $table->enum('action', ['create', 'update', 'delete', 'publish', 'rollback', 'kill_switch']);
            $table->string('target_type', 32);                // placement / unit / policy / global_settings / release
            $table->unsignedBigInteger('target_id')->nullable();
            $table->json('before_json')->nullable();
            $table->json('after_json')->nullable();
            $table->string('ip', 45)->nullable();
            $table->timestamp('created_at')->useCurrent();

            $table->index(['target_type', 'target_id', 'created_at']);
            $table->index('created_at');
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('ad_config_audit_logs');
    }
};
