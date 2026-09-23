<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    /**
     * 配置发布版本（灰度与回滚，PRD 10.5.4 / 10.5.7）。
     * 每次发布生成快照；rollout_percent 与 target_json 为 AND 关系，
     * 灰度分桶使用 hash(device_uuid) % 100（见 AdConfigResolver）。
     */
    public function up(): void
    {
        Schema::create('ad_config_releases', function (Blueprint $table) {
            $table->id();
            $table->unsignedInteger('version')->unique();
            $table->json('snapshot_json');
            $table->enum('status', ['draft', 'published', 'rolled_back'])->default('draft');
            $table->unsignedTinyInteger('rollout_percent')->default(100);
            $table->json('target_json')->nullable();          // {"country":["ID"],"app_version_min":"1.2.0"}
            $table->unsignedBigInteger('published_by')->nullable();
            $table->timestamp('published_at')->nullable();
            $table->string('note', 255)->nullable();
            $table->timestamps();

            $table->index(['status', 'published_at']);
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('ad_config_releases');
    }
};
