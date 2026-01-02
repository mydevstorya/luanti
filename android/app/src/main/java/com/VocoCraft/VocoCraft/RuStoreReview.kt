/*
 * VocoCraft - RuStore Review SDK Integration (version 10.0.0)
 * 
 * Kotlin implementation for in-app review prompts.
 * Shows review dialog after 3rd app launch.
 * 
 * Based on official documentation:
 * https://www.rustore.ru/help/en/sdk/reviews-ratings/kotlin-java/10-0-0
 */

package com.VocoCraft.VocoCraft

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.annotation.Keep
import ru.rustore.sdk.review.RuStoreReviewManagerFactory
import ru.rustore.sdk.review.model.ReviewInfo

/**
 * RuStore Review SDK wrapper for VocoCraft.
 * Shows in-app review dialog starting from 3rd app launch.
 * Once user has reviewed, never shows again.
 */
@Keep
@Suppress("unused")
class RuStoreReview private constructor(private val context: Context) {

    companion object {
        private const val TAG = "RuStoreReview"

        // Preferences
        private const val PREFS_NAME = "rustore_review_cache"
        private const val KEY_LAUNCH_COUNT = "launch_count"
        private const val KEY_REVIEW_COMPLETED = "review_completed"
        
        // Show review starting from this launch number
        private const val MIN_LAUNCHES_FOR_REVIEW = 3

        // Singleton instance
        @Volatile
        private var instance: RuStoreReview? = null

        /**
         * Initialize RuStore Review and increment launch counter.
         * Call this from Activity.onCreate() AFTER RuStorePay.init()
         * 
         * @param activity The main activity
         */
        @JvmStatic
        fun init(activity: Activity) {
            Log.i(TAG, "init() called")
            if (instance == null) {
                synchronized(this) {
                    if (instance == null) {
                        instance = RuStoreReview(activity.applicationContext)
                    }
                }
            }
            instance?.activity = activity
            instance?.onAppLaunch()
        }

        /**
         * Try to show review dialog if conditions are met.
         * Should be called after app is fully loaded (e.g., after main menu appears).
         */
        @JvmStatic
        fun tryShowReview() {
            instance?.doTryShowReview()
        }
    }

    // Instance fields
    private var activity: Activity? = null
    private var reviewInfo: ReviewInfo? = null
    private var reviewFlowShown = false

    private fun getPrefs(): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Called on each app launch. Increments counter and prepares review if needed.
     */
    private fun onAppLaunch() {
        val prefs = getPrefs()
        
        // Check if user already completed review
        if (prefs.getBoolean(KEY_REVIEW_COMPLETED, false)) {
            Log.i(TAG, "User already reviewed, skipping initialization")
            return
        }

        // Increment launch counter
        val launchCount = prefs.getInt(KEY_LAUNCH_COUNT, 0) + 1
        prefs.edit().putInt(KEY_LAUNCH_COUNT, launchCount).apply()
        Log.i(TAG, "App launch #$launchCount")

        // If we've reached minimum launches, prepare review info
        if (launchCount >= MIN_LAUNCHES_FOR_REVIEW) {
            prepareReviewFlow()
        }
    }

    /**
     * Prepare ReviewInfo in advance (has ~5 min lifetime).
     */
    private fun prepareReviewFlow() {
        Log.i(TAG, "Preparing review flow...")
        
        try {
            val manager = RuStoreReviewManagerFactory.create(context)
            
            manager.requestReviewFlow()
                .addOnSuccessListener { info ->
                    reviewInfo = info
                    Log.i(TAG, "ReviewInfo obtained successfully")
                }
                .addOnFailureListener { throwable ->
                    // Don't show error to user - they didn't initiate this
                    Log.w(TAG, "Failed to get ReviewInfo: ${throwable.message}")
                    handleReviewError(throwable)
                }
        } catch (e: Exception) {
            Log.e(TAG, "Exception preparing review: ${e.message}")
        }
    }

    /**
     * Try to show the review dialog.
     */
    private fun doTryShowReview() {
        val prefs = getPrefs()
        
        // Check if already completed review
        if (prefs.getBoolean(KEY_REVIEW_COMPLETED, false)) {
            Log.d(TAG, "Review already completed, not showing")
            return
        }

        // Check launch count
        val launchCount = prefs.getInt(KEY_LAUNCH_COUNT, 0)
        if (launchCount < MIN_LAUNCHES_FOR_REVIEW) {
            Log.d(TAG, "Not enough launches yet ($launchCount < $MIN_LAUNCHES_FOR_REVIEW)")
            return
        }

        // Check if we already showed review this session
        if (reviewFlowShown) {
            Log.d(TAG, "Review flow already shown this session")
            return
        }

        // Check if we have valid ReviewInfo
        val info = reviewInfo
        if (info == null) {
            Log.d(TAG, "ReviewInfo not ready, trying to prepare...")
            prepareReviewFlow()
            return
        }

        // Check if activity is available
        val currentActivity = activity
        if (currentActivity == null) {
            Log.w(TAG, "No activity available for review")
            return
        }

        // Show the review flow
        launchReviewFlow(currentActivity, info)
    }

    /**
     * Send review analytics event with action and optional details.
     * Single event "review" with tree of parameters.
     */
    private fun sendReviewAnalytics(action: String, result: String? = null, error: String? = null) {
        val launchCount = getPrefs().getInt(KEY_LAUNCH_COUNT, 0)
        val params = StringBuilder("{\"action\":\"$action\",\"launch_count\":$launchCount")
        if (result != null) {
            params.append(",\"result\":\"$result\"")
        }
        if (error != null) {
            // Escape quotes and backslashes in error message
            val safeError = error.replace("\\", "\\\\").replace("\"", "\\\"")
            params.append(",\"error\":\"$safeError\"")
        }
        params.append("}")
        Log.d(TAG, "Sending analytics: review with params: ${params}")
        Analytics.sendEventWithParams("review", params.toString())
    }

    /**
     * Launch the actual review UI.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun launchReviewFlow(activity: Activity, info: ReviewInfo) {
        Log.i(TAG, "Launching review flow...")
        reviewFlowShown = true
        
        // Analytics: review flow shown
        sendReviewAnalytics("shown")

        try {
            val manager = RuStoreReviewManagerFactory.create(context)
            
            manager.launchReviewFlow(info)
                .addOnSuccessListener {
                    Log.i(TAG, "Review flow completed successfully")
                    // Analytics: review completed
                    sendReviewAnalytics("completed", result = "success")
                    // Mark review as completed so we never show again
                    markReviewCompleted()
                }
                .addOnFailureListener { throwable ->
                    Log.w(TAG, "Review flow failed: ${throwable.message}")
                    val errorName = throwable.javaClass.simpleName
                    // Analytics: review failed
                    sendReviewAnalytics("completed", result = "failed", error = errorName)
                    handleReviewError(throwable)
                    // Don't mark as completed on error - might want to try again later
                }
        } catch (e: Exception) {
            Log.e(TAG, "Exception launching review: ${e.message}")
            sendReviewAnalytics("completed", result = "exception", error = e.message)
        }
    }

    /**
     * Mark that user has completed (or attempted) review.
     */
    private fun markReviewCompleted() {
        getPrefs().edit().putBoolean(KEY_REVIEW_COMPLETED, true).apply()
        Log.i(TAG, "Review marked as completed")
    }

    /**
     * Handle review-related errors.
     * Based on SDK documentation error types.
     */
    private fun handleReviewError(throwable: Throwable) {
        val errorName = throwable.javaClass.simpleName
        when (errorName) {
            "RuStoreReviewExists" -> {
                // User already reviewed this app - mark as completed
                Log.i(TAG, "User already has a review, marking completed")
                markReviewCompleted()
            }
            "RuStoreRequestLimitReached" -> {
                // Too many requests - will try again next launch
                Log.i(TAG, "Request limit reached, will try next launch")
            }
            "RuStoreNotInstalledException",
            "RuStoreOutdatedException",
            "RuStoreUserUnauthorizedException" -> {
                // RuStore issues - mark completed to avoid repeated attempts
                Log.w(TAG, "RuStore unavailable ($errorName), marking completed")
                markReviewCompleted()
            }
            else -> {
                Log.w(TAG, "Unhandled review error: $errorName")
            }
        }
    }
}
