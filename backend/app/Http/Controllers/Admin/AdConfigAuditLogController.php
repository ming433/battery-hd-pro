<?php

namespace App\Http\Controllers\Admin;

use App\Http\Controllers\Controller;
use App\Models\AdConfigAuditLog;
use Illuminate\Http\Request;
use Illuminate\View\View;

/**
 * 审计日志查看（PRD 10.5.7）。
 *
 * 记录谁、何时、改了什么、旧值→新值。支持按 target_type 过滤。
 */
class AdConfigAuditLogController extends Controller
{
    public function index(Request $request): View
    {
        $query = AdConfigAuditLog::query()->orderByDesc('created_at');

        if ($type = $request->input('target_type')) {
            $query->where('target_type', $type);
        }

        if ($action = $request->input('action')) {
            $query->where('action', $action);
        }

        $logs = $query->paginate(30)->withQueryString();

        $targetTypes = ['placement', 'unit', 'policy', 'global_settings', 'release'];
        $actions = ['create', 'update', 'delete', 'publish', 'rollback', 'kill_switch'];

        return view('admin.ad_config_audit_logs.index', compact('logs', 'targetTypes', 'actions'));
    }
}
