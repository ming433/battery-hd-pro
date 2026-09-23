<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    /**
     * 广告位定义（稳定标识，埋点按 placement_key 聚合，PRD 10.5.4）。
     */
    public function up(): void
    {
        Schema::create('ad_placements', function (Blueprint $table) {
            $table->id();
            $table->string('placement_key', 64)->unique();   // banner_home / interstitial_after_calibration
            $table->string('name', 64);
            $table->enum('ad_format', ['banner', 'interstitial', 'rewarded', 'native']);
            $table->string('description', 255)->nullable();
            $table->boolean('enabled')->default(true);
            $table->unsignedInteger('sort_order')->default(0);
            $table->timestamps();
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('ad_placements');
    }
};
