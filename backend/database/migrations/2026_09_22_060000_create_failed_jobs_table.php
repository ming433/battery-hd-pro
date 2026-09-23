<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

/**
 * 失败任务表（Laravel 标准表）。
 *
 * 缺了它的后果：Job 抛异常时，worker 想把失败记录写进 failed_jobs 会二次抛错，
 * 真正的根因异常被这第二条错误盖住，排查时只看到 "Table failed_jobs doesn't exist"。
 */
return new class extends Migration
{
    public function up(): void
    {
        Schema::create('failed_jobs', function (Blueprint $table) {
            $table->id();
            $table->string('uuid')->unique();
            $table->text('connection');
            $table->text('queue');
            $table->longText('payload');
            $table->longText('exception');
            $table->timestamp('failed_at')->useCurrent();
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('failed_jobs');
    }
};
