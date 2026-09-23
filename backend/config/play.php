<?php

/*
|--------------------------------------------------------------------------
| Google Play RTDN（PRD 10.4.7）
|--------------------------------------------------------------------------
| 客户端 purchase_completed 无法覆盖退款、续订、宽限期恢复、离线购买，
| 且存在伪造风险。RTDN 是收入与订阅状态的**唯一事实来源**，
| 客户端埋点仅用于行为漏斗，通过 order_id 与服务端对齐。
|
| 接入步骤：
|   1. Play Console → 变现设置 → 启用实时开发者通知，填写 Pub/Sub 主题
|   2. 创建对该主题的 **push** 订阅，终点设为 https://{域名}/api/v1/play/rtdn
|   3. 订阅勾选「启用身份验证」，并授予 Pub/Sub 服务账号相应权限
|   4. 把订阅全名填入 PLAY_PUBSUB_SUBSCRIPTION
*/

return [

    'package_name' => env('PLAY_PACKAGE_NAME', 'com.batteryhd.pro'),

    /**
     * Pub/Sub push 订阅全名：projects/{project}/subscriptions/{sub}
     * 服务端逐条校验该字段，防止其它主题的消息被误当作订阅通知处理。
     */
    'pubsub_subscription' => env('PLAY_PUBSUB_SUBSCRIPTION'),

    /**
     * 是否强制校验 OIDC token。**生产必须为 true**：
     * 关闭意味着任何人都可以伪造一条 SUBSCRIPTION_PURCHASED 给自己发 Pro。
     */
    'verify_oidc' => env('PLAY_VERIFY_OIDC', true),

    /** OIDC token 的 audience（push 订阅配置的 audience，通常与端点一致） */
    'oidc_audience' => env('PLAY_OIDC_AUDIENCE'),

    // 一次性商品的退款通知（voided purchase）也走同一端点
    'accept_voided' => env('PLAY_ACCEPT_VOIDED', true),

    /**
     * 每日核对告警阈值（%）：RTDN 与客户端 purchase_completed 的差异率上限。
     * PRD 10.4.7 要求 > 1% 触发告警。
     */
    'reconcile_alert_rate' => env('PLAY_RECONCILE_ALERT_RATE', 1.0),
];
