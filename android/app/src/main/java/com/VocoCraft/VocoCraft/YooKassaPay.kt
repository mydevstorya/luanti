/*
 * VocoCraft - YooKassa SDK Integration (version 8.3.0)
 * 
 * Kotlin implementation for one-time purchase management.
 * Called from native C++ code via JNI.
 * 
 * Based on official documentation:
 * https://git.yoomoney.ru/projects/SDK/repos/yookassa-android-sdk/browse
 * 
 * Flow:
 * 1. startTokenization() - Opens YooKassa UI for payment method selection
 * 2. Token received via callback
 * 3. Send token to backend to create payment
 * 4. If 3DS/SBP/SberPay confirmation needed - startConfirmation()
 * 5. Poll backend for payment status
 */

package com.VocoCraft.VocoCraft

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.annotation.Keep
import ru.yoomoney.sdk.kassa.payments.Checkout
import ru.yoomoney.sdk.kassa.payments.checkoutParameters.Amount
import ru.yoomoney.sdk.kassa.payments.checkoutParameters.PaymentMethodType
import ru.yoomoney.sdk.kassa.payments.checkoutParameters.PaymentParameters
import ru.yoomoney.sdk.kassa.payments.checkoutParameters.SavePaymentMethod
import ru.yoomoney.sdk.kassa.payments.TokenizationResult
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.math.BigDecimal
import java.security.MessageDigest
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * YooKassa SDK wrapper for VocoCraft one-time purchase management.
 * All public methods use @JvmStatic for JNI compatibility.
 */
@Keep
@Suppress("unused")
class YooKassaPay private constructor(private val context: Context) {

    companion object {
        private const val TAG = "YooKassaPay"

        // Product ID for full version purchase (same ID used in backend apps_config.json)
        // Must be lowercase to match server config
        const val FULL_VERSION_PRODUCT_ID = "com.vococraft.vococraft.fullversion"

        // Special offer product (discounted, available first 24 hours after install)
        const val SPECIAL_OFFER_PRODUCT_ID = "com.vococraft.vococraft.fullversion.special_offer"

        // Special offer window: 24 hours in milliseconds
        private const val SPECIAL_OFFER_WINDOW_MS = 24L * 60 * 60 * 1000
        private const val PREFS_INSTALL_TIME = "first_install_time"
        private const val PREFS_LAUNCH_COUNT = "launch_count"

        // Backend API URL (from local.properties via BuildConfig)
        // Uses debug URL for debug builds, prod URL for release builds
        private val BACKEND_URL: String
            get() = if (BuildConfig.DEBUG) {
                BuildConfig.YOOKASSA_BACKEND_URL_DEBUG.ifEmpty { "https://payments-debug.moodray.ru" }
            } else {
                BuildConfig.YOOKASSA_BACKEND_URL_PROD.ifEmpty { "https://payments.moodray.ru" }
            }

        // YooKassa credentials (from local.properties via BuildConfig)
        // Configure in android/local.properties (not committed to git)
        private val SHOP_ID_DEBUG: String
            get() = BuildConfig.YOOKASSA_SHOP_ID_DEBUG
        private val SHOP_ID_PROD: String
            get() = BuildConfig.YOOKASSA_SHOP_ID_PROD
        private val CLIENT_APP_KEY_DEBUG: String
            get() = BuildConfig.YOOKASSA_CLIENT_KEY_DEBUG
        private val CLIENT_APP_KEY_PROD: String
            get() = BuildConfig.YOOKASSA_CLIENT_KEY_PROD

        // Cache preferences
        private const val PREFS_NAME = "yookassa_purchase_cache"
        private const val KEY_IS_PURCHASED = "is_purchased"
        private const val KEY_PURCHASE_DATE = "purchase_date"
        private const val KEY_DEVICE_UUID = "device_uuid"

        // Operation results
        const val RESULT_NONE = 0
        const val RESULT_SUCCESS = 1
        const val RESULT_ERROR = 2
        const val RESULT_CANCELLED = 3
        const val RESULT_PENDING_CONFIRMATION = 4
        const val RESULT_CONFIRMATION_NEEDED = 5

        // Request codes for activity results
        const val REQUEST_CODE_TOKENIZE = 2001
        const val REQUEST_CODE_CONFIRM = 2002

        // Payment status polling
        private const val MAX_POLLING_ATTEMPTS = 30
        private const val POLLING_INTERVAL_MS = 2000L

        // Singleton instance
        @Volatile
        private var instance: YooKassaPay? = null

        // OkHttp client for backend API calls
        private val httpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        /**
         * Initialize YooKassa Pay with Activity context.
         * Must be called from main thread before any other methods.
         */
        @JvmStatic
        fun init(activity: Activity) {
            Log.i(TAG, "init() called, DEBUG=${BuildConfig.DEBUG}, BACKEND_URL=$BACKEND_URL")
            if (instance == null) {
                synchronized(this) {
                    if (instance == null) {
                        instance = YooKassaPay(activity.applicationContext)
                    }
                }
            }
            instance?.activity = activity
            instance?.loadFromCache()

            // Force landscape on all YooKassa SDK activities.
            // The SDK calls setRequestedOrientation(PORTRAIT) in onCreate AFTER super.onCreate(),
            // so onActivityCreated (which fires during super.onCreate) gets overwritten.
            // We use onActivityResumed which fires AFTER onCreate completes.
            activity.application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                private val SDK_PACKAGES = listOf(
                    "ru.yoomoney.sdk.kassa.payments",
                    "ru.yoomoney.sdk.auth"
                )

                private fun isSdkActivity(act: Activity): Boolean {
                    return SDK_PACKAGES.any { act.javaClass.name.startsWith(it) }
                }

                override fun onActivityCreated(act: Activity, savedInstanceState: Bundle?) {}
                override fun onActivityStarted(act: Activity) {}
                override fun onActivityResumed(act: Activity) {
                    if (isSdkActivity(act)) {
                        act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        Log.d(TAG, "Forced sensorLandscape on ${act.javaClass.simpleName}")
                    }
                }
                override fun onActivityPaused(act: Activity) {}
                override fun onActivityStopped(act: Activity) {}
                override fun onActivitySaveInstanceState(act: Activity, outState: Bundle) {}
                override fun onActivityDestroyed(act: Activity) {}
            })
        }

        /**
         * Get Shop ID based on build type
         */
        private fun getShopId(): String {
            return if (BuildConfig.DEBUG) SHOP_ID_DEBUG else SHOP_ID_PROD
        }

        /**
         * Get Client Application Key based on build type
         */
        private fun getClientAppKey(): String {
            return if (BuildConfig.DEBUG) CLIENT_APP_KEY_DEBUG else CLIENT_APP_KEY_PROD
        }

        // ==================== JNI API Methods ====================

        @JvmStatic
        fun hasPurchase(): Boolean {
            return instance?.isPurchased ?: false
        }

        @JvmStatic
        fun startPurchase(amount: String, currency: String, title: String, description: String) {
            instance?.doStartPurchase(amount, currency, title, description)
        }

        @JvmStatic
        fun getDeviceUuid(): String {
            return instance?.getDeviceUuidInternal() ?: ""
        }

        @JvmStatic
        fun getProductPrice(): String {
            return instance?.productPriceFormatted ?: ""
        }

        @JvmStatic
        fun getProductAmount(): String {
            return instance?.productAmount ?: "249"
        }

        @JvmStatic
        fun getProductCurrency(): String {
            return instance?.productCurrency ?: "RUB"
        }

        @JvmStatic
        fun isProductInfoFetched(): Boolean {
            return instance?.productInfoFetched ?: false
        }

        // ==================== Special Offer ====================

        @JvmStatic
        fun getSpecialOfferPrice(): String {
            return instance?.specialOfferPriceFormatted ?: ""
        }

        @JvmStatic
        fun getSpecialOfferAmount(): String {
            return instance?.specialOfferAmount ?: ""
        }

        @JvmStatic
        fun getSpecialOfferCurrency(): String {
            return instance?.specialOfferCurrency ?: "RUB"
        }

        @JvmStatic
        fun isSpecialOfferAvailable(): Boolean {
            val inst = instance ?: return false
            // Show special offer UI whenever within 24h window,
            // regardless of whether special offer product has been fetched from backend
            return inst.isWithinSpecialOfferWindow()
        }

        /**
         * Check if the special offer product price has been fetched from backend.
         */
        @JvmStatic
        fun isSpecialOfferPriceFetched(): Boolean {
            return instance?.specialOfferFetched ?: false
        }

        /**
         * Get remaining seconds until special offer expires.
         * Returns 0 if expired or not available.
         */
        @JvmStatic
        fun getSpecialOfferRemainingSeconds(): Long {
            val inst = instance ?: return 0
            return inst.getSpecialOfferRemainingSecondsInternal()
        }

        /**
         * Get the number of times the app has been launched (GameActivity created).
         */
        @JvmStatic
        fun getLaunchCount(): Int {
            return instance?.launchCount ?: 0
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

        @JvmStatic
        fun getPendingConfirmationUrl(): String {
            return instance?.pendingConfirmationUrl ?: ""
        }

        @JvmStatic
        fun getPendingPaymentMethodType(): String {
            return instance?.pendingPaymentMethodType ?: ""
        }

        @JvmStatic
        fun startConfirmation(confirmationUrl: String, paymentMethodType: String) {
            instance?.doStartConfirmation(confirmationUrl, paymentMethodType)
        }

        /**
         * Handle activity result from tokenization or confirmation.
         * Call this from Activity.onActivityResult()
         */
        @JvmStatic
        fun handleActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
            instance?.onActivityResult(requestCode, resultCode, data)
        }

        // Native callback (called from Kotlin to notify C++)
        @JvmStatic
        external fun nativeOnPurchaseResult(success: Boolean, json: String)
    }

    // Instance fields
    private var activity: Activity? = null

    // Purchase state
    private var isPurchased = false
    private var purchaseDateMs = 0L
    private var cachedDeviceUuid: String? = null

    // Product info from backend
    private var productPriceFormatted: String? = null
    private var productAmount: String? = null      // Raw amount from backend (e.g. "249")
    private var productCurrency: String? = null    // Currency from backend (e.g. "RUB")
    @Volatile
    private var productInfoFetched = false

    // Special offer product info
    private var specialOfferPriceFormatted: String? = null
    private var specialOfferAmount: String? = null
    private var specialOfferCurrency: String? = null
    @Volatile
    private var specialOfferFetched = false
    private var firstInstallTime: Long = 0L
    private var launchCount: Int = 0

    // Current payment flow state
    private var pendingPaymentId: String? = null
    private var pendingProductId: String? = null
    private var pendingConfirmationUrl: String? = null
    private var pendingPaymentMethodType: String? = null
    private var pendingPaymentToken: String? = null

    // Async operation state
    private val operationInProgress = AtomicBoolean(false)
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
        isPurchased = prefs.getBoolean(KEY_IS_PURCHASED, false)
        purchaseDateMs = prefs.getLong(KEY_PURCHASE_DATE, 0)
        cachedDeviceUuid = prefs.getString(KEY_DEVICE_UUID, null)

        // Track first install time for special offer countdown
        firstInstallTime = prefs.getLong(PREFS_INSTALL_TIME, 0L)
        if (firstInstallTime == 0L) {
            firstInstallTime = System.currentTimeMillis()
            prefs.edit().putLong(PREFS_INSTALL_TIME, firstInstallTime).apply()
            Log.i(TAG, "First install time recorded: $firstInstallTime")
        }

        // Track launch count (incremented each time GameActivity creates and inits YooKassaPay)
        launchCount = prefs.getInt(PREFS_LAUNCH_COUNT, 0) + 1
        prefs.edit().putInt(PREFS_LAUNCH_COUNT, launchCount).apply()
        Log.i(TAG, "Launch count: $launchCount")
        
        Log.i(TAG, "Loaded from cache: purchased=$isPurchased, date=$purchaseDateMs, uuid=${cachedDeviceUuid?.take(8)}..., installTime=$firstInstallTime")
    }

    private fun saveToCache() {
        getPrefs().edit().apply {
            putBoolean(KEY_IS_PURCHASED, isPurchased)
            putLong(KEY_PURCHASE_DATE, purchaseDateMs)
            cachedDeviceUuid?.let { putString(KEY_DEVICE_UUID, it) }
            apply()
        }
        Log.d(TAG, "Saved to cache: purchased=$isPurchased")
    }

    private fun clearCacheInternal() {
        getPrefs().edit().clear().apply()
        isPurchased = false
        purchaseDateMs = 0
        Log.i(TAG, "Cache cleared")
    }

    /**
     * Check if current time is within the 24-hour special offer window.
     */
    fun isWithinSpecialOfferWindow(): Boolean {
        if (firstInstallTime == 0L) return false
        val elapsed = System.currentTimeMillis() - firstInstallTime
        return elapsed < SPECIAL_OFFER_WINDOW_MS
    }

    /**
     * Get remaining seconds until special offer expires.
     */
    fun getSpecialOfferRemainingSecondsInternal(): Long {
        if (firstInstallTime == 0L) return 0
        val remaining = SPECIAL_OFFER_WINDOW_MS - (System.currentTimeMillis() - firstInstallTime)
        return if (remaining > 0) remaining / 1000 else 0
    }

    /**
     * Generate unique device identifier similar to Unity's SystemInfo.deviceUniqueIdentifier.
     * Uses Android ID + hardware info to create a stable UUID.
     */
    private fun getDeviceUuidInternal(): String {
        // Return cached UUID if available
        cachedDeviceUuid?.let { return it }

        // Generate UUID similar to Unity's approach
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        val deviceInfo = "${android.os.Build.MANUFACTURER}|${android.os.Build.MODEL}|${android.os.Build.SERIAL}|$androidId"
        
        // Create MD5 hash and format as UUID
        val md5 = MessageDigest.getInstance("MD5")
        val hashBytes = md5.digest(deviceInfo.toByteArray())
        
        // Format as UUID string
        val uuid = StringBuilder()
        for (i in hashBytes.indices) {
            val hex = String.format("%02x", hashBytes[i])
            uuid.append(hex)
            if (i == 3 || i == 5 || i == 7 || i == 9) {
                uuid.append("-")
            }
        }
        
        cachedDeviceUuid = uuid.toString()
        saveToCache()
        
        Log.i(TAG, "Generated device UUID: ${cachedDeviceUuid?.take(8)}...")
        return cachedDeviceUuid!!
    }

    /**
     * Fetch product information from backend.
     */
    private fun doFetchProductInfo() {
        Log.i(TAG, "fetchProductInfo()")

        if (productInfoFetched) {
            Log.d(TAG, "Product info already fetched")
            return
        }

        val appId = context.packageName.lowercase()
        val url = "$BACKEND_URL/get-products?id=$appId"
        
        Log.d(TAG, "Fetching products from: $url")
        Log.d(TAG, "Looking for product ID: $FULL_VERSION_PRODUCT_ID")
        
        val request = Request.Builder()
            .url(url)
            .addHeader("ngrok-skip-browser-warning", "true")
            .get()
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Failed to fetch products: ${e.message}")
                // Don't set fallback - keep "Загрузка..." state
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        Log.e(TAG, "Products fetch failed: ${resp.code}")
                        // Don't set fallback - keep "Загрузка..." state
                        return
                    }

                    try {
                        val body = resp.body?.string() ?: run {
                            Log.e(TAG, "Empty response body")
                            // Don't set fallback - keep "Загрузка..." state
                            return
                        }
                        
                        Log.d(TAG, "Products response: $body")
                        
                        val json = JSONObject(body)
                        val products = json.optJSONArray("products")
                        
                        if (products == null || products.length() == 0) {
                            Log.w(TAG, "No products in response")
                            // Don't set fallback - keep "Загрузка..." state
                            return
                        }

                        var found = false
                        for (i in 0 until products.length()) {
                            val product = products.getJSONObject(i)
                            val productId = product.optString("id", "")
                            Log.d(TAG, "Product[$i]: id='$productId', full json=$product")
                            
                            // Match regular full version product
                            if (productId == FULL_VERSION_PRODUCT_ID) {
                                // Get amount - handle both string and number types
                                val amount = when {
                                    product.has("amount") -> {
                                        val amountVal = product.get("amount")
                                        when (amountVal) {
                                            is String -> amountVal
                                            is Number -> amountVal.toString()
                                            else -> "249"
                                        }
                                    }
                                    else -> "249"
                                }
                                val currency = product.optString("currency", "RUB")
                                
                                productAmount = amount
                                productCurrency = currency
                                productPriceFormatted = formatPrice(amount, currency)
                                productInfoFetched = true
                                found = true
                                Log.i(TAG, "Product info fetched: amount=$amount, currency=$currency, formatted=$productPriceFormatted")
                            }

                            // Match special offer product
                            if (productId == SPECIAL_OFFER_PRODUCT_ID) {
                                val amount = when {
                                    product.has("amount") -> {
                                        val amountVal = product.get("amount")
                                        when (amountVal) {
                                            is String -> amountVal
                                            is Number -> amountVal.toString()
                                            else -> null
                                        }
                                    }
                                    else -> null
                                }
                                if (amount != null) {
                                    val currency = product.optString("currency", "RUB")
                                    specialOfferAmount = amount
                                    specialOfferCurrency = currency
                                    specialOfferPriceFormatted = formatPrice(amount, currency)
                                    specialOfferFetched = true
                                    Log.i(TAG, "Special offer fetched: amount=$amount, currency=$currency, formatted=$specialOfferPriceFormatted")
                                }
                            }
                        }
                        
                        if (!found) {
                            Log.w(TAG, "Product not found in response")
                            // Don't set fallback - keep "Загрузка..." state
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse products: ${e.message}")
                        e.printStackTrace()
                        // Don't set fallback - keep "Загрузка..." state
                    }
                }
            }
        })
    }

    private fun formatPrice(amount: String, currency: String): String {
        // Remove trailing .00 for cleaner display (249.00 -> 249)
        val cleanAmount = if (amount.endsWith(".00")) {
            amount.dropLast(3)
        } else if (amount.endsWith(".0")) {
            amount.dropLast(2)
        } else {
            amount
        }
        
        return when (currency.uppercase()) {
            "RUB" -> "$cleanAmount рублей"
            "USD" -> "$cleanAmount USD"
            "EUR" -> "$cleanAmount EUR"
            else -> "$cleanAmount $currency"
        }
    }

    /**
     * Start purchase flow with YooKassa tokenization.
     */
    private fun doStartPurchase(amount: String, currency: String, title: String, description: String) {
        Log.i(TAG, "startPurchase: amount=$amount, currency=$currency")

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

        // Use special offer product ID if the offer is active and we have special offer pricing
        pendingProductId = if (isWithinSpecialOfferWindow() && specialOfferFetched) {
            SPECIAL_OFFER_PRODUCT_ID
        } else {
            FULL_VERSION_PRODUCT_ID
        }
        Log.i(TAG, "Using product ID: $pendingProductId, amount=$amount, currency=$currency")

        try {
            val paymentAmount = Amount(BigDecimal(amount), Currency.getInstance(currency))
            
            // Configure payment methods (SBP, SberPay, Bank Card)
            val paymentMethodTypes = setOf(
                PaymentMethodType.BANK_CARD,
                PaymentMethodType.SBERBANK,
                PaymentMethodType.SBP
            )

            val paymentParameters = PaymentParameters(
                amount = paymentAmount,
                title = title,
                subtitle = description,
                clientApplicationKey = getClientAppKey(),
                shopId = getShopId(),
                savePaymentMethod = SavePaymentMethod.OFF,
                paymentMethodTypes = paymentMethodTypes
            )

            val intent = Checkout.createTokenizeIntent(currentActivity, paymentParameters)
            currentActivity.startActivityForResult(intent, REQUEST_CODE_TOKENIZE)

        } catch (e: Exception) {
            operationInProgress.set(false)
            lastError.set(e.message ?: "Exception")
            lastOperationResult.set(RESULT_ERROR)
            Log.e(TAG, "Exception starting purchase: ${e.message}")
        }
    }

    /**
     * Handle activity result from YooKassa SDK.
     */
    private fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        Log.d(TAG, "onActivityResult: requestCode=$requestCode, resultCode=$resultCode")

        when (requestCode) {
            REQUEST_CODE_TOKENIZE -> handleTokenizationResult(resultCode, data)
            REQUEST_CODE_CONFIRM -> handleConfirmationResult(resultCode, data)
        }
    }

    private fun handleTokenizationResult(resultCode: Int, data: Intent?) {
        when (resultCode) {
            Activity.RESULT_OK -> {
                if (data != null) {
                    try {
                        val result: TokenizationResult = Checkout.createTokenizationResult(data)
                        val token = result.paymentToken
                        val paymentMethodType = result.paymentMethodType.name

                        Log.i(TAG, "Tokenization success: method=$paymentMethodType")
                        
                        pendingPaymentToken = token
                        pendingPaymentMethodType = paymentMethodType

                        // Send token to backend to create payment
                        createPaymentOnBackend(token, paymentMethodType)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse tokenization result: ${e.message}")
                        operationInProgress.set(false)
                        lastError.set(e.message ?: "Parse error")
                        lastOperationResult.set(RESULT_ERROR)
                        triggerNativeUiRefresh()
                    }
                } else {
                    operationInProgress.set(false)
                    lastError.set("No data from tokenization")
                    lastOperationResult.set(RESULT_ERROR)
                    triggerNativeUiRefresh()
                }
            }
            Activity.RESULT_CANCELED -> {
                Log.i(TAG, "Tokenization cancelled by user")
                operationInProgress.set(false)
                lastError.set("Покупка отменена")
                lastOperationResult.set(RESULT_CANCELLED)
                triggerNativeUiRefresh()
            }
            Checkout.RESULT_ERROR -> {
                val errorCode = data?.getIntExtra(Checkout.EXTRA_ERROR_CODE, -1)
                val errorDesc = data?.getStringExtra(Checkout.EXTRA_ERROR_DESCRIPTION)
                Log.e(TAG, "Tokenization error: code=$errorCode, desc=$errorDesc")
                operationInProgress.set(false)
                lastError.set(errorDesc ?: "Ошибка оплаты")
                lastOperationResult.set(RESULT_ERROR)
                triggerNativeUiRefresh()
            }
        }
    }

    private fun handleConfirmationResult(resultCode: Int, data: Intent?) {
        when (resultCode) {
            Activity.RESULT_OK -> {
                Log.i(TAG, "Confirmation completed")
                // Start polling for payment status
                pollPaymentStatus()
            }
            Activity.RESULT_CANCELED -> {
                Log.i(TAG, "Confirmation cancelled")
                operationInProgress.set(false)
                lastError.set("Подтверждение отменено")
                lastOperationResult.set(RESULT_CANCELLED)
                triggerNativeUiRefresh()
            }
            Checkout.RESULT_ERROR -> {
                val errorCode = data?.getIntExtra(Checkout.EXTRA_ERROR_CODE, -1)
                val errorDesc = data?.getStringExtra(Checkout.EXTRA_ERROR_DESCRIPTION)
                Log.e(TAG, "Confirmation error: code=$errorCode, desc=$errorDesc")
                operationInProgress.set(false)
                lastError.set(errorDesc ?: "Ошибка подтверждения")
                lastOperationResult.set(RESULT_ERROR)
                triggerNativeUiRefresh()
            }
        }
    }

    /**
     * Send payment token to backend to create payment.
     */
    private fun createPaymentOnBackend(token: String, paymentMethodType: String) {
        Log.i(TAG, "Creating payment on backend...")

        val uuid = getDeviceUuidInternal()
        val appId = context.packageName.lowercase()

        val jsonBody = JSONObject().apply {
            put("uuid", uuid)
            put("app_id", appId)
            put("product_id", pendingProductId ?: FULL_VERSION_PRODUCT_ID)
            put("payment_token", token)
        }

        Log.d(TAG, "create-payment request: $jsonBody")

        val request = Request.Builder()
            .url("$BACKEND_URL/create-payment")
            .addHeader("Content-Type", "application/json")
            .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Create payment failed: ${e.message}")
                operationInProgress.set(false)
                lastError.set(e.message ?: "Ошибка сети")
                lastOperationResult.set(RESULT_ERROR)
                activity?.runOnUiThread { triggerNativeUiRefresh() }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    try {
                        val body = resp.body?.string() ?: ""
                        
                        if (!resp.isSuccessful) {
                            Log.e(TAG, "Create payment error: ${resp.code} - $body")
                            operationInProgress.set(false)
                            lastError.set("Ошибка сервера: ${resp.code}")
                            lastOperationResult.set(RESULT_ERROR)
                            activity?.runOnUiThread { triggerNativeUiRefresh() }
                            return
                        }

                        val json = JSONObject(body)
                        pendingPaymentId = json.getString("payment_id")
                        val status = json.getString("status")
                        val confirmationUrl = json.optString("confirmation_url", "")

                        Log.i(TAG, "Payment created: id=$pendingPaymentId, status=$status")

                        when (status) {
                            "pending" -> {
                                if (confirmationUrl.isNotEmpty()) {
                                    // Need 3DS/SBP/SberPay confirmation
                                    pendingConfirmationUrl = confirmationUrl
                                    lastOperationResult.set(RESULT_CONFIRMATION_NEEDED)
                                    activity?.runOnUiThread {
                                        doStartConfirmation(confirmationUrl, paymentMethodType)
                                    }
                                } else {
                                    // Start polling
                                    pollPaymentStatus()
                                }
                            }
                            "succeeded" -> {
                                handlePaymentSuccess()
                            }
                            else -> {
                                // Start polling to wait for result
                                pollPaymentStatus()
                            }
                        }

                    } catch (e: Exception) {
                        Log.e(TAG, "Parse payment response error: ${e.message}")
                        operationInProgress.set(false)
                        lastError.set(e.message ?: "Ошибка обработки ответа")
                        lastOperationResult.set(RESULT_ERROR)
                        activity?.runOnUiThread { triggerNativeUiRefresh() }
                    }
                }
            }
        })
    }

    /**
     * Start confirmation process (3DS, SBP, SberPay).
     */
    private fun doStartConfirmation(confirmationUrl: String, paymentMethodType: String) {
        Log.i(TAG, "startConfirmation: url=$confirmationUrl, method=$paymentMethodType")

        val currentActivity = activity
        if (currentActivity == null) {
            Log.e(TAG, "No activity for confirmation")
            operationInProgress.set(false)
            lastError.set("No activity")
            lastOperationResult.set(RESULT_ERROR)
            triggerNativeUiRefresh()
            return
        }

        try {
            val pmType = when (paymentMethodType.uppercase()) {
                "BANK_CARD" -> PaymentMethodType.BANK_CARD
                "SBERBANK" -> PaymentMethodType.SBERBANK
                "SBP" -> PaymentMethodType.SBP
                else -> PaymentMethodType.BANK_CARD
            }

            val intent = Checkout.createConfirmationIntent(
                currentActivity,
                confirmationUrl,
                pmType,
                getClientAppKey(),
                getShopId()
            )
            currentActivity.startActivityForResult(intent, REQUEST_CODE_CONFIRM)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start confirmation: ${e.message}")
            operationInProgress.set(false)
            lastError.set(e.message ?: "Ошибка подтверждения")
            lastOperationResult.set(RESULT_ERROR)
            triggerNativeUiRefresh()
        }
    }

    /**
     * Poll backend for payment status.
     */
    private fun pollPaymentStatus(attempt: Int = 0) {
        if (attempt >= MAX_POLLING_ATTEMPTS) {
            Log.e(TAG, "Payment status polling timeout")
            operationInProgress.set(false)
            lastError.set("Превышено время ожидания")
            lastOperationResult.set(RESULT_ERROR)
            activity?.runOnUiThread { triggerNativeUiRefresh() }
            return
        }

        val paymentId = pendingPaymentId
        if (paymentId == null) {
            Log.e(TAG, "No pending payment ID")
            operationInProgress.set(false)
            lastError.set("No payment ID")
            lastOperationResult.set(RESULT_ERROR)
            activity?.runOnUiThread { triggerNativeUiRefresh() }
            return
        }

        val uuid = getDeviceUuidInternal()

        val jsonBody = JSONObject().apply {
            put("uuid", uuid)
            put("payment_id", paymentId)
        }

        val request = Request.Builder()
            .url("$BACKEND_URL/check-payment-status")
            .addHeader("Content-Type", "application/json")
            .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.w(TAG, "Status check failed, retrying: ${e.message}")
                scheduleNextPoll(attempt)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    try {
                        val body = resp.body?.string() ?: ""

                        if (!resp.isSuccessful) {
                            Log.w(TAG, "Status check error: ${resp.code}")
                            scheduleNextPoll(attempt)
                            return
                        }

                        val json = JSONObject(body)
                        val status = json.getString("status")
                        val isPurchased = json.optBoolean("is_purchased", false)

                        Log.d(TAG, "Payment status: $status, purchased=$isPurchased")

                        when {
                            status == "succeeded" || isPurchased -> {
                                handlePaymentSuccess()
                            }
                            status == "canceled" || status == "cancelled" -> {
                                operationInProgress.set(false)
                                lastError.set("Платёж отменён")
                                lastOperationResult.set(RESULT_CANCELLED)
                                activity?.runOnUiThread { triggerNativeUiRefresh() }
                            }
                            status == "pending" || status == "waiting_for_capture" -> {
                                scheduleNextPoll(attempt)
                            }
                            else -> {
                                scheduleNextPoll(attempt)
                            }
                        }

                    } catch (e: Exception) {
                        Log.e(TAG, "Parse status error: ${e.message}")
                        scheduleNextPoll(attempt)
                    }
                }
            }
        })
    }

    private fun scheduleNextPoll(currentAttempt: Int) {
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            pollPaymentStatus(currentAttempt + 1)
        }, POLLING_INTERVAL_MS)
    }

    private fun handlePaymentSuccess() {
        Log.i(TAG, "Payment successful!")
        isPurchased = true
        purchaseDateMs = System.currentTimeMillis()
        saveToCache()
        operationInProgress.set(false)
        lastOperationResult.set(RESULT_SUCCESS)
        
        // Clear pending state
        pendingPaymentId = null
        pendingProductId = null
        pendingConfirmationUrl = null
        pendingPaymentMethodType = null
        pendingPaymentToken = null
        
        // Analytics
        sendPurchaseAnalytics("purchase", "success", null)
        
        // Trigger UI refresh
        activity?.runOnUiThread { triggerNativeUiRefresh() }
    }

    /**
     * Restore purchases from backend.
     */
    private fun doRestorePurchases() {
        Log.i(TAG, "restorePurchases()")

        if (operationInProgress.get()) {
            Log.w(TAG, "Operation already in progress")
            return
        }

        operationInProgress.set(true)

        val uuid = getDeviceUuidInternal()
        val appId = context.packageName.lowercase()

        val jsonBody = JSONObject().apply {
            put("uuid", uuid)
        }

        val request = Request.Builder()
            .url("$BACKEND_URL/get-my-products?id=$appId")
            .addHeader("Content-Type", "application/json")
            .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Restore failed: ${e.message}")
                operationInProgress.set(false)
                lastError.set(e.message ?: "Ошибка сети")
                lastOperationResult.set(RESULT_ERROR)
                activity?.runOnUiThread { triggerNativeUiRefresh() }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    try {
                        val body = resp.body?.string() ?: ""
                        
                        if (!resp.isSuccessful) {
                            Log.e(TAG, "Restore error: ${resp.code}")
                            operationInProgress.set(false)
                            lastError.set("Ошибка сервера: ${resp.code}")
                            lastOperationResult.set(RESULT_ERROR)
                            activity?.runOnUiThread { triggerNativeUiRefresh() }
                            return
                        }

                        val json = JSONObject(body)
                        val products = json.optJSONArray("products") ?: JSONArray()

                        var foundPurchase = false
                        for (i in 0 until products.length()) {
                            val product = products.getJSONObject(i)
                            val productId = product.getString("product_id")
                            if (productId == FULL_VERSION_PRODUCT_ID || productId == SPECIAL_OFFER_PRODUCT_ID) {
                                val isConsumed = product.optBoolean("is_consumed", false)
                                if (!isConsumed) {
                                    foundPurchase = true
                                    isPurchased = true
                                    saveToCache()
                                    break
                                }
                            }
                        }

                        if (foundPurchase) {
                            Log.i(TAG, "Restore found purchase!")
                        } else {
                            Log.i(TAG, "Restore: no purchases found")
                        }

                        operationInProgress.set(false)
                        lastOperationResult.set(RESULT_SUCCESS)
                        activity?.runOnUiThread { triggerNativeUiRefresh() }

                    } catch (e: Exception) {
                        Log.e(TAG, "Parse restore error: ${e.message}")
                        operationInProgress.set(false)
                        lastError.set(e.message ?: "Ошибка обработки")
                        lastOperationResult.set(RESULT_ERROR)
                        activity?.runOnUiThread { triggerNativeUiRefresh() }
                    }
                }
            }
        })
    }

    /**
     * Trigger UI refresh in native code.
     */
    private fun triggerNativeUiRefresh() {
        val currentActivity = activity
        if (currentActivity == null) {
            Log.w(TAG, "Activity is null, cannot trigger UI refresh")
            return
        }

        try {
            Log.d(TAG, "Triggering native UI refresh... purchased=$isPurchased")
            // Only dismiss dialogs/blockers if user actually purchased
            if (isPurchased) {
                PurchasePromptDialog.dismiss()
                InternetCheckService.dismissBlocker()
                // Remove every forced ad and the in-game purchase CTA.
                (currentActivity as? GameActivity)?.onPurchaseActivated()
                (currentActivity as? GameActivity)?.nativeOnPurchaseComplete()
                    ?: Log.w(TAG, "Activity is not GameActivity")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to trigger native UI refresh: ${e.message}")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Native method not found: ${e.message}")
        }
    }

    /**
     * Send purchase analytics event.
     */
    private fun sendPurchaseAnalytics(action: String, result: String?, error: String?) {
        val params = StringBuilder("{\"action\":\"$action\"")
        if (result != null) {
            params.append(",\"result\":\"$result\"")
        }
        if (error != null) {
            val safeError = error.replace("\\", "\\\\").replace("\"", "\\\"")
            params.append(",\"error\":\"$safeError\"")
        }
        params.append("}")
        Log.d(TAG, "Sending analytics: purchase with params: $params")
        Analytics.sendEventWithParams("purchase", params.toString())
    }
}
