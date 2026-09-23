<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    /**
     * 广告全局设置（单表单行，PRD 10.5.3）。
     * kill_switch 为一键停投；child_directed / content_rating 设错会导致 AdMob 停号，
     * 变更需双人复核（见 ad_config_audit_logs）。
     */
    public function up(): void
    {
        Schema::create('ad_global_settings', function (Blueprint $table) {
            $table->id();
            $table->boolean('ads_enabled')->default(true);
            $table->boolean('kill_switch')->default(false);
            $table->unsignedInteger('first_launch_grace_minutes')->default(10);
            $table->boolean('tag_for_child_directed')->default(false);
            $table->enum('max_ad_content_rating', ['G', 'PG', 'T', 'MA'])->default('T');
            $table->boolean('npa_default')->default(false);   // 非个性化广告降级
            $table->unsignedInteger('config_ttl_seconds')->default(300);
            $table->unsignedBigInteger('updated_by')->nullable();
            $table->timestamps();
        });

        // 写入初始行（单表单行配置）
        DB::table('ad_global_settings')->insert([
            'ads_enabled'                 => true,
            'kill_switch'                 => false,
            'first_launch_grace_minutes'  => 10,
            'tag_for_child_directed'      => false,
            'max_ad_content_rating'       => 'T',
            'npa_default'                 => false,
            'config_ttl_seconds'          => 300,
            'created_at'                  => now(),
            'updated_at'                  => now(),
        ]);
    }

    public function down(): void
    {
        Schema::dropIfExists('ad_global_settings');
    }
};
