<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    /**
     * 广告配置管理后台账号（M4 使用，PRD 10.5.7）。
     * 角色：editor（编辑草稿）/ supervisor（发布、回滚、熔断）。
     */
    public function up(): void
    {
        Schema::create('admins', function (Blueprint $table) {
            $table->id();
            $table->string('name', 64);
            $table->string('email', 128)->unique();
            $table->string('password');
            $table->enum('role', ['editor', 'supervisor'])->default('editor');
            $table->rememberToken();
            $table->timestamps();
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('admins');
    }
};
