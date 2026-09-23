package com.batteryhd.app.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.batteryhd.analytics.Analytics
import com.batteryhd.analytics.Dictionary
import com.batteryhd.app.util.Prefs

/**
 * Play 订阅（PRD 10.4 / 10.6）。
 *
 * ── 定位：客户端埋点只做**行为漏斗** ──────────────────────────
 * 收入真相源是服务端的 Google Play RTDN 回调（退款、续订、离线购买、
 * 跨设备恢复购买客户端都看不到）。客户端埋点用于回答
 * 「用户在哪一步流失」，不用于回答「赚了多少钱」。
 * 两者靠 order_id 对齐，每日对账。
 *
 * 因此这里不实现"购买成功即认为已付费"的最终判断，
 * 只在本地缓存 isPro 用于 UI 展示，最终状态以服务端为准。
 */
class BillingManager(
    private val context: Context,
    private val prefs: Prefs,
    private val onProChanged: (Boolean) -> Unit
) {

    private var ready = false
    private var productDetails: ProductDetails? = null

    /**
     * 最近一次查到的价格与币种。
     *
     * Play 回调里的 `Purchase` 对象**不带价格**，而 `purchase_completed` 需要
     * `price_local` / `currency`（用于核算 ARPU 与汇率归一化），
     * 因此查询商品时缓存下来，完成事件回填。查不到时为默认值，**不阻塞上报**。
     */
    private var lastPriceLocal: Double = 0.0
    private var lastCurrency: String = ""

    private val purchasesUpdatedListener = PurchasesUpdatedListener { billingResult, purchases ->
        handlePurchaseResult(billingResult, purchases)
    }

    private val client: BillingClient = BillingClient.newBuilder(context)
        .setListener(purchasesUpdatedListener)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder()
                .enableOneTimeProducts()
                .build()
        )
        .build()

    fun start() {
        if (ready) return
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                ready = result.responseCode == BillingClient.BillingResponseCode.OK
                if (ready) {
                    queryProducts()
                    restore()
                }
            }

            override fun onBillingServiceDisconnected() {
                ready = false
            }
        })
    }

    /** 查询商品价格（展示用）。价格为本地化字符串，直接展示，不做换算。 */
    fun queryProducts(onPrice: ((String) -> Unit)? = null) {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(PLAN_ID)
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build()
                )
            )
            .build()

        client.queryProductDetailsAsync(params) { _, list ->
            val details = list.firstOrNull { it.productId == PLAN_ID }
            productDetails = details
            val phase = details?.subscriptionOfferDetails?.firstOrNull()
                ?.pricingPhases?.pricingPhaseList?.firstOrNull()
            lastPriceLocal = phase?.priceAmountMicros?.let { it / 1_000_000.0 } ?: 0.0
            lastCurrency = phase?.priceCurrencyCode ?: ""
            val price = phase?.formattedPrice
            if (price != null) onPrice?.invoke(price)
        }
    }

    /** 发起购买。返回 false 表示 Billing 不可用（调用方需给出提示）。 */
    fun launchPurchase(activity: Activity): Boolean {
        val details = productDetails
        if (!ready || details == null) {
            Analytics.track(
                Dictionary.Event.PURCHASE_FAILED,
                mapOf(
                    "plan_id" to PLAN_ID,
                    "error_code" to BillingClient.BillingResponseCode.BILLING_UNAVAILABLE,
                    "payment_method" to "google_play"
                )
            )
            return false
        }

        Analytics.track(
            Dictionary.Event.PURCHASE_INITIATED,
            mapOf(
                "plan_id" to PLAN_ID,
                "price_local" to lastPriceLocal,
                "currency" to lastCurrency
            )
        )

        val offerToken = details.subscriptionOfferDetails?.firstOrNull()?.offerToken
        val productDetailsParamsBuilder = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)
        if (offerToken != null) {
            productDetailsParamsBuilder.setOfferToken(offerToken)
        }

        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productDetailsParamsBuilder.build()))
            .build()

        val result = client.launchBillingFlow(activity, flowParams)
        return result.responseCode == BillingClient.BillingResponseCode.OK
    }

    /** 恢复购买（换机、重装场景） */
    fun restore() {
        if (!ready) return
        client.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
        ) { _, purchases ->
            val owned = purchases.any { purchase ->
                PLAN_ID in purchase.products && purchase.purchaseState == Purchase.PurchaseState.PURCHASED
            }
            if (owned) {
                prefs.isPro = true
                Analytics.setProStatus(true)
                onProChanged(true)
                Analytics.track(
                    Dictionary.Event.PURCHASE_RESTORED,
                    // 恢复发生在启动自检与 Pro 页「恢复购买」两处，统一记为 source=settings
                    mapOf("plan_id" to PLAN_ID, "source" to Dictionary.Source.SETTINGS)
                )
            }
        }
    }

    private fun handlePurchaseResult(result: BillingResult, purchases: List<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                val purchase = purchases?.firstOrNull()
                if (purchase != null) {
                    prefs.isPro = true
                    Analytics.setProStatus(true)
                    onProChanged(true)

                    Analytics.track(
                        Dictionary.Event.PURCHASE_COMPLETED,
                        mapOf(
                            "plan_id" to PLAN_ID,
                            "order_id" to purchase.orderId,
                            "price_local" to lastPriceLocal,
                            // 本地只判断"本地无购买记录"，换机/重装会误判；
                            // 真实首购口径以服务端 RTDN 为准
                            "is_first_purchase" to !prefs.hasPurchasedBefore
                        )
                    )
                    prefs.hasPurchasedBefore = true
                }
            }

            // 用户主动取消：单独事件。
            // 统计"购买失败率"时必须剔除 USER_CANCELED，否则把"用户改主意"
            // 算成系统故障，指标会误导决策（PRD 10.2.5）。
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                Analytics.track(
                    Dictionary.Event.PURCHASE_CANCELLED,
                    mapOf(
                        "plan_id" to PLAN_ID,
                        "cancel_step" to Dictionary.CancelStep.CONFIRM
                    )
                )
            }

            else -> {
                Analytics.track(
                    Dictionary.Event.PURCHASE_FAILED,
                    mapOf(
                        "plan_id" to PLAN_ID,
                        "error_code" to result.responseCode,
                        "payment_method" to "google_play"
                    )
                )
            }
        }
    }

    companion object {
        /** 必须与 Play Console 的 productId **大小写完全一致**，否则服务端无法归因 */
        const val PLAN_ID = "pro_monthly"
    }
}
