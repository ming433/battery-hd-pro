<?php

namespace App\Console\Commands;

use App\Models\Admin;
use Illuminate\Console\Command;
use Illuminate\Support\Facades\Hash;

/**
 * 创建 / 重置管理后台账号（双人复核至少需要两个账号）。
 *
 * 用法：
 *   php artisan admin:create --email=foo@bar.com --name=Foo --role=supervisor --password=Secret123!
 * 若邮箱已存在则更新其 name/role/password。
 */
class AdminCreateCommand extends Command
{
    protected $signature = 'admin:create
        {--email= : 管理员邮箱（必填）}
        {--name= : 显示名称}
        {--role=editor : editor | supervisor}
        {--password= : 明文密码}';

    protected $description = '创建或更新一个管理后台账号（admins 表）';

    public function handle(): int
    {
        $email = $this->option('email') ?: $this->ask('管理员邮箱');
        if (! $email || ! filter_var($email, FILTER_VALIDATE_EMAIL)) {
            $this->error('邮箱无效。');

            return self::FAILURE;
        }

        $name = $this->option('name') ?: $this->ask('显示名称', '管理员');
        $role = $this->option('role') ?: $this->choice('角色', ['editor', 'supervisor'], 0);
        $password = $this->option('password') ?: $this->secret('明文密码（输入不回显）');

        if (! $password) {
            $this->error('密码不能为空。');

            return self::FAILURE;
        }

        $admin = Admin::updateOrCreate(
            ['email' => $email],
            [
                'name'     => $name,
                'email'    => $email,
                'role'     => $role,
                'password' => Hash::make($password),
            ]
        );

        $this->info(sprintf('账号 %s（%s）已%s。', $admin->email, $admin->role, $admin->wasRecentlyCreated ? '创建' : '更新'));

        return self::SUCCESS;
    }
}
