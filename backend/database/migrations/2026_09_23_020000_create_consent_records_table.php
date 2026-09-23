<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

/**
 * 同意凭证（PRD 10.7.3）。
 *
 * 「用户同意过」这件事必须**可举证**——PDP Law / PDPA 下，拿不出同意记录
 * 就等于没获得同意。因此每次同意/撤回都追加一行（append-only），
 * 而不是只保存最新状态：历史状态是举证材料，不能被覆盖。
 */
return new class extends Migration
{
    public function up(): void
    {
        Schema::create('consent_records', function (Blueprint $table) {
            $table->id();
            $table->unsignedBigInteger('device_id')->nullable();
            $table->string('device_uuid', 36)->nullable();

            // granted（同意）| withdrawn（撤回）| updated（变更）
            $table->string('action', 16);
            $table->boolean('analytics_consent');
            $table->boolean('personalized_ads');

            // 隐私政策版本号：不同版本下的同意不能混为一谈
            $table->string('policy_version', 32);
            $table->timestamp('decided_at')->nullable();
            $table->string('ip', 45)->nullable();
            $table->timestamp('created_at')->useCurrent();

            $table->index(['device_id', 'created_at']);
            $table->index('policy_version');
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('consent_records');
    }
};
