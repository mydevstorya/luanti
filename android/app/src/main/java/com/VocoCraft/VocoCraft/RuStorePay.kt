/*
 * VocoCraft - RuStore Pay SDK Integration (version 10.1.0)
 * 
 * Kotlin implementation for subscription management.
 * Called from native C++ code via JNI.
 * 
 * Based on official documentation:
 * https://www.rustore.ru/help/en/sdk/pay/kotlin-java/10-1-0
 */

package com.VocoCraft.VocoCraft

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import androidx.annotation.Keep
import ru.rustore.sdk.pay.RuStorePayClient
import ru.rustore.sdk.pay.model.ProductId
import ru.rustore.sdk.pay.model.ProductPurchaseParams
import ru.rustore.sdk.pay.model.ProductType
import ru.rustore.sdk.pay.model.PurchaseAvailabilityResult
import ru.rustore.sdk.pay.model.SdkTheme
import ru.rustore.sdk.pay.model.PreferredPurchaseType
import ru.rustore.sdk.pay.model.ProductPurchaseResult
import ru.rustore.sdk.pay.model.SubscriptionPurchase
import ru.rustore.sdk.pay.model.Product
import ru.rustore.sdk.pay.model.Purchase
import ru.rustore.sdk.pay.model.TrialPeriod
import ru.rustore.sdk.pay.model.PromoPeriod
import ru.rustore.sdk.pay.model.MainPeriod
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * RuStore Pay SDK wrapper for VocoCraft subscription management.
 * All public methods use @JvmStatic for JNI compatibility.
 * 
 * Uses SDK API 10.1.0 with interactors accessed via get*Interactor() methods:
 * - getPurchaseInteractor() for purchases
 * - getProductInteractor() for products
 * - getIntentInteractor() for deeplinks
 */
@Keep
@Suppress("unused")
class RuStorePay private constructor(private val context: Context) {

    companion object {
        private const val TAG = "RuStorePay"

        // Subscription product ID from RuStore Console
        const val SUBSCRIPTION_PRODUCT_ID = "vococraft_premium_monthly"

        // Cache preferences
        private const val PREFS_NAME = "rustore_subscription_cache"
        private const val KEY_IS_SUBSCRIBED = "is_subscribed"
        private const val KEY_EXPIRATION_DATE = "expiration_date"
        private const val KEY_PURCHASE_ID = "purchase_id"
        private const val KEY_LAST_CHECK_TIME = "last_check_time"

        // Cache validity: 24 hours
        private const val CACHE_VALIDITY_MS = 24 * 60 * 60 * 1000L

        // Operation results
        const val RESULT_NONE = 0
        const val RESULT_SUCCESS = 1
        const val RESULT_ERROR = 2
        const val RESULT_CANCELLED = 3
        const val RESULT_NO_INTERNET = 4
        const val RESULT_NOT_AVAILABLE = 5

        // Singleton instance
        @Volatile
        private var instance: RuStorePay? = null

        /**
         * Initialize RuStore Pay SDK with Activity context.
         * Must be called from main thread before any other methods.
         */
        @JvmStatic
        fun init(activity: Activity) {
            Log.i(TAG, "init() called")
            if (instance == null) {
                synchronized(this) {
                    if (instance == null) {
                        instance = RuStorePay(activity.applicationContext)
                    }
                }
            }
            instance?.activity = activity
            instance?.initSdk()
        }

        /**
         * Handle deeplink intent for payment return.
         * Call this from Activity.onNewIntent()
         */
        @JvmStatic
        fun onNewIntent(intent: Intent?) {
            Log.d(TAG, "onNewIntent() called")
            try {
                intent?.let {
                    // Use getIntentInteractor().proceedIntent() according to docs
                    RuStorePayClient.instance.getIntentInteractor().proceedIntent(it, SdkTheme.DARK)
                }
            } catch (e: Exception) {
                Log.e(TAG, "onNewIntent error: ${e.message}")
            }
        }

        // ==================== JNI API Methods ====================

        @JvmStatic
        fun hasSubscription(): Boolean {
            return instance?.isSubscribed ?: false
        }

        @JvmStatic
        fun checkSubscriptionAsync() {
            instance?.doCheckSubscription()
        }

        @JvmStatic
        fun purchaseSubscription() {
            instance?.doPurchaseSubscription()
        }

        @JvmStatic
        fun getExpirationDate(): Long {
            return instance?.expirationDateMs ?: 0L
        }

        @JvmStatic
        fun getMonthlyPrice(): String {
            return instance?.monthlyPriceFormatted ?: ""
        }

        @JvmStatic
        fun getTrialPrice(): String {
            return instance?.trialPriceFormatted ?: ""
        }

        @JvmStatic
        fun getTrialDays(): Int {
            return instance?.trialDurationDays ?: 0
        }

        @JvmStatic
        fun getPromoPrice(): String {
            return instance?.promoPriceFormatted ?: ""
        }

        @JvmStatic
        fun getPromoDays(): Int {
            return instance?.promoDurationDays ?: 0
        }

        @JvmStatic
        fun isProductInfoFetched(): Boolean {
            return instance?.productInfoFetched ?: false
        }

        @JvmStatic
        fun isOperationInProgress(): Boolean {
            return instance?.operationInProgress?.get() ?: false
        }

        @JvmStatic
        fun getLastOperationResult(): Int {
            return instance?.lastOperationResult?.get() ?: RESULT_NONE
        }

        @JvmStatic
        fun getLastError(): String {
            return instance?.lastError?.get() ?: ""
        }

        @JvmStatic
        fun clearOperationResult() {
            instance?.lastOperationResult?.set(RESULT_NONE)
            instance?.lastError?.set("")
        }

        @JvmStatic
        fun restorePurchases() {
            instance?.doRestorePurchases()
        }

        @JvmStatic
        fun fetchProductInfo() {
            instance?.doFetchProductInfo()
        }

        @JvmStatic
        fun clearCache() {
            instance?.clearCacheInternal()
        }

        // Native callback (called from Kotlin to notify C++)
        @JvmStatic
        external fun nativeOnSubscriptionResult(success: Boolean, json: String)
    }

    // Instance fields
    private var activity: Activity? = null
    private var sdkAvailable = false

    // Subscription state
    private var isSubscribed = false
    private var expirationDateMs = 0L
    private var purchaseId: String? = null

    // Product info from RuStore
    private var monthlyPriceFormatted: String? = null
    private var trialPriceFormatted: String? = null
    private var trialDurationDays = 0
    private var promoPriceFormatted: String? = null
    private var promoDurationDays = 0
    private var productInfoFetched = false

    // Async operation state - separate flags for different operations
    private val operationInProgress = AtomicBoolean(false)  // For subscription check/purchase
    private val productFetchInProgress = AtomicBoolean(false)  // For product info fetch
    private val lastError = AtomicReference("")
    private val lastOperationResult = AtomicReference(RESULT_NONE)

    init {
        loadFromCache()
    }

    private fun getPrefs(): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun loadFromCache() {
        val prefs = getPrefs()
        isSubscribed = prefs.getBoolean(KEY_IS_SUBSCRIBED, false)
        expirationDateMs = prefs.getLong(KEY_EXPIRATION_DATE, 0)
        purchaseId = prefs.getString(KEY_PURCHASE_ID, null)

        // Check if subscription expired
        if (isSubscribed && expirationDateMs > 0 && System.currentTimeMillis() > expirationDateMs) {
            Log.i(TAG, "Cached subscription expired")
            isSubscribed = false
            saveToCache()
        }

        Log.i(TAG, "Loaded from cache: subscribed=$isSubscribed, expires=$expirationDateMs")
    }

    private fun saveToCache() {
        getPrefs().edit().apply {
            putBoolean(KEY_IS_SUBSCRIBED, isSubscribed)
            putLong(KEY_EXPIRATION_DATE, expirationDateMs)
            putString(KEY_PURCHASE_ID, purchaseId)
            putLong(KEY_LAST_CHECK_TIME, System.currentTimeMillis())
            apply()
        }
        Log.d(TAG, "Saved to cache: subscribed=$isSubscribed")
    }

    private fun clearCacheInternal() {
        getPrefs().edit().clear().apply()
        isSubscribed = false
        expirationDateMs = 0
        purchaseId = null
        Log.i(TAG, "Cache cleared")
    }

    /**
     * Initialize RuStore Pay SDK.
     * SDK initialization is automatic via manifest meta-data.
     * Check if purchases are available.
     */
    private fun initSdk() {
        Log.i(TAG, "initSdk() called")
        try {
            // Check if SDK is available using getPurchaseInteractor()
            RuStorePayClient.instance.getPurchaseInteractor().getPurchaseAvailability()
                .addOnSuccessListener { result: PurchaseAvailabilityResult ->
                    when (result) {
                        is PurchaseAvailabilityResult.Available -> {
                            sdkAvailable = true
                            Log.i(TAG, "RuStore Pay SDK available")
                            // Immediately fetch product info after SDK is ready
                            doFetchProductInfo()
                        }
                        is PurchaseAvailabilityResult.Unavailable -> {
                            sdkAvailable = false
                            Log.w(TAG, "RuStore Pay SDK unavailable: ${result.cause?.message}")
                        }
                    }
                }
                .addOnFailureListener { error: Throwable ->
                    sdkAvailable = false
                    Log.e(TAG, "RuStore Pay SDK init failed: ${error.message}")
                }
        } catch (e: Exception) {
            sdkAvailable = false
            Log.e(TAG, "RuStore Pay SDK exception: ${e.message}")
        }
    }

    /**
     * Fetch product information from RuStore.
     * Retrieves price, trial info, promo periods.
     */
    private fun doFetchProductInfo() {
        Log.i(TAG, "fetchProductInfo() for $SUBSCRIPTION_PRODUCT_ID")

        // Use separate flag for product fetch - doesn't conflict with subscription operations
        if (productFetchInProgress.get()) {
            Log.w(TAG, "Product fetch already in progress")
            return
        }
        
        // Skip if already fetched
        if (productInfoFetched) {
            Log.d(TAG, "Product info already fetched")
            return
        }

        productFetchInProgress.set(true)

        try {
            val productIds = listOf(ProductId(SUBSCRIPTION_PRODUCT_ID))

            // Use getProductInteractor().getProducts() according to docs
            RuStorePayClient.instance.getProductInteractor().getProducts(productIds)
                .addOnSuccessListener { products: List<Product> ->
                    productFetchInProgress.set(false)

                    if (products.isEmpty()) {
                        Log.w(TAG, "No products returned from RuStore")
                        // Don't set operation result - this is separate from subscription operations
                        return@addOnSuccessListener
                    }

                    val product = products.first()
                    parseProductInfo(product)
                    productInfoFetched = true
                    // Don't set lastOperationResult - product fetch is independent operation

                    Log.i(TAG, "Product info fetched: price=${monthlyPriceFormatted}, " +
                            "trial=${trialDurationDays}d at ${trialPriceFormatted}, " +
                            "promo=${promoDurationDays}d at ${promoPriceFormatted}")
                }
                .addOnFailureListener { error: Throwable ->
                    productFetchInProgress.set(false)
                    lastError.set(error.message ?: "Unknown error")
                    // Don't set RESULT_ERROR - this is a separate operation
                    Log.e(TAG, "Failed to fetch product info: ${error.message}")
                }
        } catch (e: Exception) {
            productFetchInProgress.set(false)
            lastError.set(e.message ?: "Exception")
            Log.e(TAG, "Exception fetching product info: ${e.message}")
        }
    }

    /**
     * Parse product information including subscription periods.
     * According to docs: amountLabel is AmountLabel wrapper, price is Int in periods
     */
    private fun parseProductInfo(product: Product) {
        // Main price - amountLabel is AmountLabel wrapper, use .value or toString()
        monthlyPriceFormatted = product.amountLabel?.toString() ?: ""

        // Parse subscription periods (trial, promo, main)
        val subscriptionInfo = product.subscriptionInfo
        if (subscriptionInfo != null) {
            val periods = subscriptionInfo.periods

            for (period in periods) {
                when (period) {
                    is TrialPeriod -> {
                        // price is Int in minimum units, currency is String
                        // If price is 0, show "Бесплатно" instead of "0.00 ₽"
                        trialPriceFormatted = if (period.price == 0) {
                            "Бесплатно"
                        } else {
                            formatPrice(period.price, period.currency)
                        }
                        trialDurationDays = parseDurationDays(period.duration)
                        Log.d(TAG, "Trial period: ${trialDurationDays}d, price=$trialPriceFormatted")
                    }
                    is PromoPeriod -> {
                        promoPriceFormatted = formatPrice(period.price, period.currency)
                        promoDurationDays = parseDurationDays(period.duration)
                        Log.d(TAG, "Promo period: ${promoDurationDays}d, price=$promoPriceFormatted")
                    }
                    is MainPeriod -> {
                        // Main period price as fallback
                        if (monthlyPriceFormatted.isNullOrEmpty()) {
                            monthlyPriceFormatted = formatPrice(period.price, period.currency)
                        }
                        Log.d(TAG, "Main period: duration=${period.duration}, price=$monthlyPriceFormatted")
                    }
                    else -> {
                        // GracePeriod, HoldPeriod, etc. - ignore
                        Log.d(TAG, "Ignoring period type: ${period.javaClass.simpleName}")
                    }
                }
            }
        }
    }

    /**
     * Format price from minor units to display string.
     * price is in minimum currency units (kopecks for RUB) - Int type
     */
    private fun formatPrice(priceMinor: Int, currency: String): String {
        val priceDecimal = priceMinor / 100.0
        val currencySymbol = when (currency.uppercase()) {
            "RUB" -> "руб."
            "USD" -> "$"
            "EUR" -> "€"
            else -> currency
        }
        return String.format("%.2f %s", priceDecimal, currencySymbol)
    }

    /**
     * Parse ISO 8601 duration to days.
     * Examples: P7D -> 7, P1M -> 30, P1Y -> 365
     */
    private fun parseDurationDays(duration: String): Int {
        if (duration.isEmpty()) return 0

        try {
            val regex = Regex("P(?:(\\d+)Y)?(?:(\\d+)M)?(?:(\\d+)W)?(?:(\\d+)D)?")
            val match = regex.find(duration) ?: return 0

            val years = match.groupValues[1].toIntOrNull() ?: 0
            val months = match.groupValues[2].toIntOrNull() ?: 0
            val weeks = match.groupValues[3].toIntOrNull() ?: 0
            val days = match.groupValues[4].toIntOrNull() ?: 0

            return years * 365 + months * 30 + weeks * 7 + days
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse duration: $duration")
            return 0
        }
    }

    /**
     * Check subscription status asynchronously.
     */
    private fun doCheckSubscription() {
        Log.i(TAG, "checkSubscriptionAsync()")

        if (operationInProgress.get()) {
            Log.w(TAG, "Operation already in progress")
            return
        }

        operationInProgress.set(true)

        try {
            // Get purchases filtered by subscription type using getPurchaseInteractor()
            RuStorePayClient.instance.getPurchaseInteractor().getPurchases(
                productType = ProductType.SUBSCRIPTION
            )
                .addOnSuccessListener { purchases: List<Purchase> ->
                    operationInProgress.set(false)

                    // Find active subscription for our product
                    val subscriptionPurchase = purchases
                        .filterIsInstance<SubscriptionPurchase>()
                        .find { purchase -> 
                            purchase.productId.value == SUBSCRIPTION_PRODUCT_ID &&
                            isActiveSubscriptionStatus(purchase.status)
                        }

                    if (subscriptionPurchase != null) {
                        isSubscribed = true
                        purchaseId = subscriptionPurchase.purchaseId.value
                        expirationDateMs = subscriptionPurchase.expirationDate?.time ?: 0
                        saveToCache()
                        lastOperationResult.set(RESULT_SUCCESS)
                        Log.i(TAG, "Active subscription found, expires: $expirationDateMs")
                    } else {
                        isSubscribed = false
                        expirationDateMs = 0
                        purchaseId = null
                        saveToCache()
                        lastOperationResult.set(RESULT_SUCCESS)
                        Log.i(TAG, "No active subscription found")
                    }
                }
                .addOnFailureListener { error: Throwable ->
                    operationInProgress.set(false)
                    lastError.set(error.message ?: "Unknown error")
                    lastOperationResult.set(RESULT_ERROR)
                    Log.e(TAG, "Failed to check subscription: ${error.message}")
                }
        } catch (e: Exception) {
            operationInProgress.set(false)
            lastError.set(e.message ?: "Exception")
            lastOperationResult.set(RESULT_ERROR)
            Log.e(TAG, "Exception checking subscription: ${e.message}")
        }
    }

    /**
     * Check if subscription status indicates active subscription.
     * ACTIVE and PAUSED are considered active (user has access).
     */
    private fun isActiveSubscriptionStatus(status: ru.rustore.sdk.pay.model.PurchaseStatus?): Boolean {
        if (status == null) return false
        val statusName = status.toString().uppercase()
        return statusName == "ACTIVE" || statusName == "PAUSED"
    }

    /**
     * Initiate subscription purchase flow.
     */
    private fun doPurchaseSubscription() {
        Log.i(TAG, "purchaseSubscription()")

        val currentActivity = activity
        if (currentActivity == null) {
            Log.e(TAG, "No activity available for purchase")
            lastError.set("No activity")
            lastOperationResult.set(RESULT_ERROR)
            return
        }

        if (operationInProgress.get()) {
            Log.w(TAG, "Operation already in progress")
            return
        }

        operationInProgress.set(true)

        try {
            val params = ProductPurchaseParams(
                productId = ProductId(SUBSCRIPTION_PRODUCT_ID)
            )

            // Use getPurchaseInteractor().purchase() according to docs
            RuStorePayClient.instance.getPurchaseInteractor()
                .purchase(
                    params = params,
                    preferredPurchaseType = PreferredPurchaseType.ONE_STEP,
                    sdkTheme = SdkTheme.DARK
                )
                .addOnSuccessListener { result: ProductPurchaseResult ->
                    operationInProgress.set(false)
                    handlePurchaseResult(result)
                }
                .addOnFailureListener { error: Throwable ->
                    operationInProgress.set(false)
                    handlePurchaseError(error)
                }
        } catch (e: Exception) {
            operationInProgress.set(false)
            lastError.set(e.message ?: "Exception")
            lastOperationResult.set(RESULT_ERROR)
            Log.e(TAG, "Exception starting purchase: ${e.message}")
        }
    }

    /**
     * Handle successful purchase result.
     */
    private fun handlePurchaseResult(result: ProductPurchaseResult) {
        Log.i(TAG, "Purchase result: invoiceId=${result.invoiceId}, purchaseId=${result.purchaseId}")

        // Purchase successful - update subscription state
        isSubscribed = true
        purchaseId = result.purchaseId.value
        
        // For subscriptions, we need to fetch the actual expiration date
        // Set a temporary expiration (will be updated on next check)
        expirationDateMs = System.currentTimeMillis() + 30L * 24 * 60 * 60 * 1000 // 30 days
        
        saveToCache()
        lastOperationResult.set(RESULT_SUCCESS)

        Log.i(TAG, "Subscription purchased successfully")
        
        // Trigger async check to get real expiration date
        // Reset the flag first
        operationInProgress.set(false)
        doCheckSubscription()
    }

    /**
     * Handle purchase error or cancellation.
     */
    private fun handlePurchaseError(error: Throwable) {
        Log.e(TAG, "Purchase error: ${error.javaClass.simpleName}: ${error.message}")

        // Check error type by class name since exact types may vary
        val errorClassName = error.javaClass.simpleName
        
        when {
            errorClassName.contains("Cancel", ignoreCase = true) -> {
                lastOperationResult.set(RESULT_CANCELLED)
                lastError.set("Purchase cancelled by user")
                Log.i(TAG, "Purchase cancelled by user")
            }
            else -> {
                lastOperationResult.set(RESULT_ERROR)
                lastError.set(error.message ?: "Unknown error")
            }
        }
    }

    /**
     * Restore purchases from RuStore.
     */
    private fun doRestorePurchases() {
        Log.i(TAG, "restorePurchases()")
        
        // Same as checkSubscriptionAsync - fetches current subscription state
        doCheckSubscription()
    }
}
