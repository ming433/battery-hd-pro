<?php

/**
 * Battery HD Pro — 全局辅助函数
 * 注意：composer.json 的 autoload.files 引用本文件，删除会导致自动加载失败。
 */

if (! function_exists('bhd_client_ip')) {
    /**
     * 取客户端 IP（适配 nginx / Cloudflare 反代）。
     */
    function bhd_client_ip(): string
    {
        $request = request();

        return (string) ($request?->header('CF-Connecting-IP')
            ?: $request?->header('X-Forwarded-For')
            ?: $request?->ip()
            ?: '');
    }
}

if (! function_exists('bhd_partition_name')) {
    /**
     * 事件表月分区名，如 p202610。
     */
    function bhd_partition_name(DateTimeInterface|string $date): string
    {
        $d = $date instanceof DateTimeInterface ? $date : new DateTimeImmutable($date);

        return 'p'.$d->format('Ym');
    }
}

if (! function_exists('bhd_partition_boundary')) {
    /**
     * 分区上界（次月 1 日），如 2026-11-01。
     */
    function bhd_partition_boundary(DateTimeInterface|string $date): string
    {
        $d = $date instanceof DateTimeInterface ? $date : new DateTimeImmutable($date);

        return $d->format('Y-m-01');
    }
}
