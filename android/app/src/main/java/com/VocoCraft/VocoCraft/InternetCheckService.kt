/*
 * VocoCraft - Internet Connectivity Check Service
 *
 * Periodically checks internet connectivity and blocks the game
 * with an overlay if the user has no internet and hasn't purchased
 * the full version. This encourages users to either maintain an
 * internet connection (for ads) or purchase the full version.
 *
 * Check interval: 60 seconds
 * Users with full version purchase are exempt from this check.
 */

package com.VocoCraft.VocoCraft

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.Keep
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Service that periodically checks internet connectivity and blocks
 * the game if the user is offline and hasn't purchased the full version.
 *
 * Usage:
 *   InternetCheckService.init(activity, rootLayout)
 *   InternetCheckService.start()   // call in onResume
 *   InternetCheckService.stop()    // call in onPause
 *   InternetCheckService.destroy() // call in onDestroy
 */
@Keep
@Suppress("unused")
object InternetCheckService {

    private const val TAG = "InternetCheckService"

    /** Interval between connectivity checks (in seconds) */
    private const val CHECK_INTERVAL_SECONDS = 60L

    /** Initial delay before the first check (in seconds) */
    private const val INITIAL_DELAY_SECONDS = 15L

    /** Timeout for HTTP connectivity probe (in milliseconds) */
    private const val CONNECTIVITY_TIMEOUT_MS = 5000

    /** URLs to probe for connectivity (tried in order) */
    private val PROBE_URLS = listOf(
        "https://www.google.com",
        "https://ya.ru"
    )

    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "InternetCheckThread").apply { isDaemon = true }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var scheduledTask: ScheduledFuture<*>? = null
    private var activityRef: WeakReference<Activity>? = null
    private var rootLayout: ViewGroup? = null
    private var blockingOverlay: View? = null
    private val isRunning = AtomicBoolean(false)
    private val isBlocked = AtomicBoolean(false)
    private var initialized = false

    /**
     * Initialize the service with the activity and root layout.
     * Must be called before start().
     *
     * @param activity The game activity
     * @param layout The root ViewGroup where the blocking overlay will be added
     */
    @JvmStatic
    fun init(activity: Activity, layout: ViewGroup) {
        activityRef = WeakReference(activity)
        rootLayout = layout
        initialized = true
        Log.i(TAG, "InternetCheckService initialized")
    }

    /**
     * Start periodic internet connectivity checks.
     * Should be called in Activity.onResume().
     */
    @JvmStatic
    fun start() {
        if (!initialized) {
            Log.w(TAG, "Not initialized, cannot start")
            return
        }

        if (isRunning.getAndSet(true)) {
            Log.d(TAG, "Already running")
            return
        }

        Log.i(TAG, "Starting internet check service (interval: ${CHECK_INTERVAL_SECONDS}s)")

        scheduledTask = scheduler.scheduleAtFixedRate({
            try {
                performCheck()
            } catch (e: Exception) {
                Log.e(TAG, "Error during connectivity check: ${e.message}")
            }
        }, INITIAL_DELAY_SECONDS, CHECK_INTERVAL_SECONDS, TimeUnit.SECONDS)
    }

    /**
     * Stop periodic checks.
     * Should be called in Activity.onPause().
     */
    @JvmStatic
    fun stop() {
        if (!isRunning.getAndSet(false)) {
            return
        }

        Log.i(TAG, "Stopping internet check service")
        scheduledTask?.cancel(false)
        scheduledTask = null
    }

    /**
     * Destroy the service and release all resources.
     * Should be called in Activity.onDestroy().
     */
    @JvmStatic
    fun destroy() {
        Log.i(TAG, "Destroying internet check service")
        stop()
        mainHandler.post {
            removeOverlay()
        }
        activityRef = null
        rootLayout = null
        initialized = false
    }

    /**
     * Force an immediate connectivity check.
     */
    @JvmStatic
    fun checkNow() {
        scheduler.execute {
            try {
                performCheck()
            } catch (e: Exception) {
                Log.e(TAG, "Error during immediate check: ${e.message}")
            }
        }
    }

    /**
     * Check if game is currently blocked due to no internet.
     */
    @JvmStatic
    fun isGameBlocked(): Boolean = isBlocked.get()

    // ==================== Internal Implementation ====================

    private fun performCheck() {
        val activity = activityRef?.get()
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            Log.d(TAG, "Activity not available, skipping check")
            return
        }

        // Users with full version purchase are exempt
        if (YooKassaPay.hasPurchase()) {
            Log.d(TAG, "User has purchase, skipping connectivity check")
            if (isBlocked.get()) {
                mainHandler.post { removeOverlay() }
            }
            return
        }

        val hasInternet = checkConnectivity(activity)
        Log.d(TAG, "Connectivity check result: $hasInternet")

        if (!hasInternet) {
            if (!isBlocked.get()) {
                Log.w(TAG, "No internet detected — blocking game")
                mainHandler.post { showBlockingOverlay(activity) }

                // Send analytics
                try {
                    Analytics.sendAdEvent(activity, "internet_check", "blocked", "no_internet")
                } catch (e: Exception) {
                    Log.e(TAG, "Error sending analytics: ${e.message}")
                }
            }
        } else {
            if (isBlocked.get()) {
                Log.i(TAG, "Internet restored — unblocking game")
                mainHandler.post { removeOverlay() }

                try {
                    Analytics.sendAdEvent(activity, "internet_check", "unblocked", "internet_restored")
                } catch (e: Exception) {
                    Log.e(TAG, "Error sending analytics: ${e.message}")
                }
            }
        }
    }

    /**
     * Check internet connectivity using both system API and HTTP probe.
     */
    private fun checkConnectivity(context: Context): Boolean {
        // First, quick check via ConnectivityManager
        if (!isNetworkAvailable(context)) {
            return false
        }

        // Then verify with an actual HTTP request
        return probeInternet()
    }

    /**
     * Check if network interface is available via ConnectivityManager.
     */
    private fun isNetworkAvailable(context: Context): Boolean {
        return try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = connectivityManager.activeNetwork ?: return false
                val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            } else {
                @Suppress("DEPRECATION")
                val networkInfo = connectivityManager.activeNetworkInfo
                @Suppress("DEPRECATION")
                networkInfo != null && networkInfo.isConnected
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking network availability: ${e.message}")
            false
        }
    }

    /**
     * Probe internet with HTTP request to verify actual connectivity.
     */
    private fun probeInternet(): Boolean {
        for (probeUrl in PROBE_URLS) {
            try {
                val url = URL(probeUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = CONNECTIVITY_TIMEOUT_MS
                connection.readTimeout = CONNECTIVITY_TIMEOUT_MS
                connection.requestMethod = "HEAD"
                connection.useCaches = false

                val responseCode = connection.responseCode
                connection.disconnect()

                if (responseCode in 200..399) {
                    return true
                }
            } catch (e: Exception) {
                Log.d(TAG, "Probe failed for $probeUrl: ${e.message}")
                continue
            }
        }
        return false
    }

    // ==================== UI: Blocking Overlay ====================

    private fun showBlockingOverlay(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return

        // Remove existing overlay first
        removeOverlay()

        val layout = rootLayout ?: return
        val density = activity.resources.displayMetrics.density

        // Semi-transparent black overlay covering the entire screen
        val overlay = FrameLayout(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#E6000000")) // ~90% opacity black
            isClickable = true  // Block all touches through
            isFocusable = true
            elevation = 100f    // Ensure it's on top
        }

        // Center content container
        val contentContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val containerParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER }
            layoutParams = containerParams
            setPadding(
                (32 * density).toInt(),
                (24 * density).toInt(),
                (32 * density).toInt(),
                (24 * density).toInt()
            )
        }

        // Wi-Fi icon / No connection icon
        val iconText = TextView(activity).apply {
            text = "📡"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 48f)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (16 * density).toInt()
            }
        }
        contentContainer.addView(iconText)

        // Title text
        val titleText = TextView(activity).apply {
            text = "Нет подключения к интернету"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (12 * density).toInt()
            }
        }
        contentContainer.addView(titleText)

        // Description text
        val descText = TextView(activity).apply {
            text = "Подключитесь к интернету для продолжения игры\nили приобретите полный доступ"
            setTextColor(Color.parseColor("#cccccc"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (24 * density).toInt()
            }
        }
        contentContainer.addView(descText)

        // "Buy full version" button
        val buyBtnBg = GradientDrawable().apply {
            setColor(Color.parseColor("#9c27b0"))
            cornerRadius = 10 * density
        }

        val buyButton = TextView(activity).apply {
            text = "★ Купить полную версию ★"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = buyBtnBg
            setPadding(
                (24 * density).toInt(),
                (14 * density).toInt(),
                (24 * density).toInt(),
                (14 * density).toInt()
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (12 * density).toInt()
            }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                Log.d(TAG, "Buy button clicked from blocking overlay")
                try {
                    Analytics.sendAdEvent(activity, "internet_check", "buy_clicked", "blocking_overlay")
                } catch (e: Exception) {
                    Log.e(TAG, "Error sending analytics: ${e.message}")
                }
                startPurchase()
            }
        }
        contentContainer.addView(buyButton)

        // Price hint
        val priceHint = TextView(activity).apply {
            val price = try {
                val p = YooKassaPay.getProductPrice()
                if (p.isNotEmpty()) p else "249 ₽"
            } catch (e: Exception) {
                "249 ₽"
            }
            text = "Разовая покупка за $price — навсегда без рекламы"
            setTextColor(Color.parseColor("#999999"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (20 * density).toInt()
            }
        }
        contentContainer.addView(priceHint)

        // "Retry connection" button
        val retryBtnBg = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            setStroke((1.5f * density).toInt(), Color.parseColor("#666666"))
            cornerRadius = 10 * density
        }

        val retryButton = TextView(activity).apply {
            text = "Повторить подключение"
            setTextColor(Color.parseColor("#aaaaaa"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
            background = retryBtnBg
            setPadding(
                (24 * density).toInt(),
                (12 * density).toInt(),
                (24 * density).toInt(),
                (12 * density).toInt()
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            isClickable = true
            isFocusable = true
            setOnClickListener {
                Log.d(TAG, "Retry connection clicked")
                try {
                    Analytics.sendAdEvent(activity, "internet_check", "retry_clicked", "blocking_overlay")
                } catch (e: Exception) {
                    Log.e(TAG, "Error sending analytics: ${e.message}")
                }
                // Perform immediate check in background
                checkNow()
            }
        }
        contentContainer.addView(retryButton)

        overlay.addView(contentContainer)

        // Add overlay to root layout
        try {
            layout.addView(overlay)
            blockingOverlay = overlay
            isBlocked.set(true)
            Log.i(TAG, "Blocking overlay shown")
        } catch (e: Exception) {
            Log.e(TAG, "Error adding blocking overlay: ${e.message}")
        }
    }

    private fun removeOverlay() {
        blockingOverlay?.let { overlay ->
            try {
                (overlay.parent as? ViewGroup)?.removeView(overlay)
                Log.i(TAG, "Blocking overlay removed")
            } catch (e: Exception) {
                Log.e(TAG, "Error removing overlay: ${e.message}")
            }
        }
        blockingOverlay = null
        isBlocked.set(false)
    }

    private fun startPurchase() {
        try {
            val amount = YooKassaPay.getProductAmount().ifEmpty { "249" }
            val currency = YooKassaPay.getProductCurrency().ifEmpty { "RUB" }

            YooKassaPay.clearOperationResult()
            YooKassaPay.startPurchase(
                amount,
                currency,
                "VocoCraft Полная версия",
                "Разблокировка всех функций — играйте без рекламы и без ограничений"
            )
            Log.d(TAG, "Purchase started from blocking overlay")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting purchase: ${e.message}")
        }
    }
}
