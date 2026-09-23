<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    /**
     * 匿名设备注册表（PRD 10.4.3）。
     * device_uuid 为客户端生成的匿名 UUID，不采集 IMEI / MAC / Android ID。
     */
    public function up(): void
    {
        Schema::create('analytics_devices', function (Blueprint $table) {
            $table->id();
            $table->string('device_uuid', 36)->unique();
            $table->string('app_version', 20)->nullable();
            $table->string('os_version', 10)->nullable();
            $table->string('locale', 10)->nullable();
            $table->string('country', 8)->nullable();
            $table->string('device_model', 64)->nullable();
            $table->string('manufacturer', 32)->nullable();
            $table->string('install_source', 32)->nullable();
            $table->boolean('is_suspect')->default(false);   // 异常设备，核心指标剔除
            $table->timestamp('pending_deletion_at')->nullable(); // 数据删除请求
            $table->timestamp('first_seen_at')->nullable();
            $table->timestamp('last_seen_at')->nullable();
            $table->timestamps();

            $table->index('last_seen_at');
            $table->index('country');
            $table->index('is_suspect');
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('analytics_devices');
    }
};
