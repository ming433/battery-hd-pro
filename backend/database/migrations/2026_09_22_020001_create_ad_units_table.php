<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    /**
     * 广告单元（按国家 + 平台配置，PRD 10.5.4）。
     * country_code 为 NULL 表示该平台下的默认单元。
     */
    public function up(): void
    {
        Schema::create('ad_units', function (Blueprint $table) {
            $table->id();
            $table->foreignId('placement_id')->constrained('ad_placements')->cascadeOnDelete();
            $table->string('network', 32)->default('admob');
            $table->enum('platform', ['android', 'ios'])->default('android');
            $table->string('country_code', 2)->nullable();     // ID / VN / TH / PH；NULL = 默认
            $table->string('ad_unit_id', 128);
            $table->boolean('enabled')->default(true);
            $table->decimal('ecpm_floor', 10, 4)->nullable();  // 预留：接入中介后生效
            $table->unsignedInteger('priority')->default(0);
            $table->timestamps();

            $table->unique(['placement_id', 'network', 'platform', 'country_code'], 'ad_units_unique');
            $table->index(['placement_id', 'enabled']);
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('ad_units');
    }
};
