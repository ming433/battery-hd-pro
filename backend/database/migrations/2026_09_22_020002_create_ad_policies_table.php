<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    /**
     * 广告策略（频次、场景、超时、熔断，PRD 10.5.4）。
     * 按 广告位 + 国家 + 版本区间 + 用户分群 命中，priority 大者优先。
     */
    public function up(): void
    {
        Schema::create('ad_policies', function (Blueprint $table) {
            $table->id();
            $table->foreignId('placement_id')->constrained('ad_placements')->cascadeOnDelete();
            $table->string('country_code', 2)->nullable();
            $table->string('app_version_min', 20)->nullable();  // 语义化版本比较，见 AdConfigResolver
            $table->string('app_version_max', 20)->nullable();
            $table->enum('user_segment', ['all', 'free', 'pro', 'new'])->default('all');
            $table->json('config_json');                        // frequency_cap / trigger_scenes / timeout / retry / circuit_breaker
            $table->boolean('enabled')->default(true);
            $table->unsignedInteger('priority')->default(0);
            $table->timestamps();

            $table->index(['placement_id', 'country_code', 'user_segment', 'enabled'], 'ad_policies_lookup');
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('ad_policies');
    }
};
