package com.noscroll.app

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClient.ProductType
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams

/**
 * Google Play billing.
 *
 * Products to create in Play Console (Monetize → Products):
 *  - Subscription  "noscroll_pro"  with base plans "monthly" and "yearly"
 *  - In-app product "noscroll_lifetime" (one-time purchase)
 */
object Store {
    const val ALWAYS_PRO = false
    const val IS_STORE_BUILD = true

    const val SUBSCRIPTION_ID = "noscroll_pro"
    const val LIFETIME_ID = "noscroll_lifetime"
    const val PLAN_MONTHLY = "monthly"
    const val PLAN_YEARLY = "yearly"

    private val main = Handler(Looper.getMainLooper())
    private var client: BillingClient? = null
    private var appContext: Context? = null
    private var onPurchaseDone: (() -> Unit)? = null

    private val purchasesListener = PurchasesUpdatedListener { result, purchases ->
        if (result.responseCode == BillingResponseCode.OK && purchases != null) {
            appContext?.let { ctx -> refresh(ctx) { onPurchaseDone?.invoke() } }
        }
    }

    private fun connect(context: Context, onReady: (BillingClient) -> Unit) {
        appContext = context.applicationContext
        val c = client ?: BillingClient.newBuilder(context.applicationContext)
            .setListener(purchasesListener)
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .build()
            .also { client = it }
        if (c.isReady) {
            onReady(c)
            return
        }
        c.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingResponseCode.OK) main.post { onReady(c) }
            }

            override fun onBillingServiceDisconnected() {
                // Reconnects on the next call.
            }
        })
    }

    /** Re-checks what the user owns and saves it, so Pro also works offline. */
    fun refresh(context: Context, onDone: () -> Unit = {}) {
        val prefs = Prefs(context)
        connect(context) { c ->
            querySubs(c) { subs ->
                queryInApp(c) { inApp ->
                    val owned = (subs + inApp).filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
                    owned.filter { !it.isAcknowledged }.forEach { acknowledge(c, it) }
                    prefs.proKind = when {
                        owned.any { LIFETIME_ID in it.products } -> "lifetime"
                        owned.any { SUBSCRIPTION_ID in it.products } -> "subscription"
                        else -> ""
                    }
                    main.post(onDone)
                }
            }
        }
    }

    private fun querySubs(c: BillingClient, cb: (List<Purchase>) -> Unit) {
        c.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(ProductType.SUBS).build()
        ) { result, list -> cb(if (result.responseCode == BillingResponseCode.OK) list else emptyList()) }
    }

    private fun queryInApp(c: BillingClient, cb: (List<Purchase>) -> Unit) {
        c.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(ProductType.INAPP).build()
        ) { result, list -> cb(if (result.responseCode == BillingResponseCode.OK) list else emptyList()) }
    }

    private fun acknowledge(c: BillingClient, purchase: Purchase) {
        c.acknowledgePurchase(
            AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()
        ) { }
    }

    /** Loads the three plans with prices in the user's local currency. */
    fun loadPlans(context: Context, onLoaded: (List<Plan>) -> Unit) {
        connect(context) { c ->
            queryDetails(c, SUBSCRIPTION_ID, ProductType.SUBS) { sub ->
                queryDetails(c, LIFETIME_ID, ProductType.INAPP) { life ->
                    val plans = mutableListOf<Plan>()
                    sub?.subscriptionOfferDetails
                        ?.filter { it.offerId == null }                       // base plans
                        ?.forEach { offer ->
                            val price = offer.pricingPhases.pricingPhaseList.lastOrNull()?.formattedPrice ?: return@forEach
                            when (offer.basePlanId) {
                                PLAN_MONTHLY -> plans += Plan(PLAN_MONTHLY, "Monthly", "$price / month", sub, offer.offerToken)
                                PLAN_YEARLY -> plans += Plan(PLAN_YEARLY, "Yearly", "$price / year", sub, offer.offerToken)
                            }
                        }
                    life?.oneTimePurchaseOfferDetails?.let {
                        plans += Plan("lifetime", "Lifetime", "${it.formattedPrice} once", life, null)
                    }
                    val order = listOf(PLAN_YEARLY, PLAN_MONTHLY, "lifetime")
                    main.post { onLoaded(plans.sortedBy { order.indexOf(it.id) }) }
                }
            }
        }
    }

    private fun queryDetails(c: BillingClient, id: String, type: String, cb: (ProductDetails?) -> Unit) {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(QueryProductDetailsParams.Product.newBuilder().setProductId(id).setProductType(type).build())
            ).build()
        c.queryProductDetailsAsync(params) { result, list ->
            cb(if (result.responseCode == BillingResponseCode.OK) list.firstOrNull() else null)
        }
    }

    fun buy(activity: Activity, plan: Plan, onDone: () -> Unit = {}) {
        val details = plan.details as? ProductDetails ?: return
        onPurchaseDone = onDone
        connect(activity) { c ->
            val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(details)
                .apply { plan.offerToken?.let { setOfferToken(it) } }
                .build()
            val flow = BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(productParams)).build()
            c.launchBillingFlow(activity, flow)
        }
    }
}
