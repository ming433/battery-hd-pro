<?php

namespace Database\Seeders;

use App\Models\Admin;
use Illuminate\Database\Seeder;
use Illuminate\Support\Facades\Hash;

/**
 * 初始管理后台账号（M4，PRD 10.5.7）。
 *
 * ⚠️ 初始密码统一为 ChangeMe123!，上线前必须改密（登录后或 php artisan admin:create 重建）。
 * 一个运营（editor，可提交变更/待复核）、一个主管（supervisor，可复核/发布/回滚/熔断）。
 * 双人复核要求：复核人必须是「另一名」管理员，故至少需两个账号。
 */
class AdminSeeder extends Seeder
{
    public function run(): void
    {
        $admins = [
            [
                'name'     => '运营管理员',
                'email'    => 'editor@example.com',
                'password' => 'ChangeMe123!',
                'role'     => 'editor',
            ],
            [
                'name'     => '主管管理员',
                'email'    => 'supervisor@example.com',
                'password' => 'ChangeMe123!',
                'role'     => 'supervisor',
            ],
        ];

        foreach ($admins as $data) {
            Admin::firstOrCreate(
                ['email' => $data['email']],
                [
                    'name'     => $data['name'],
                    'email'    => $data['email'],
                    'password' => Hash::make($data['password']),
                    'role'     => $data['role'],
                ]
            );
        }
    }
}
