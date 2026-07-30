/*
VocoCraft Yandex Mobile Ads Integration
Copyright (C) 2024 VocoCraft Team

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU Lesser General Public License as published by
the Free Software Foundation; either version 2.1 of the License, or
(at your option) any later version.
*/

package com.VocoCraft.VocoCraft;

import android.app.Activity;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.RelativeLayout;

import com.yandex.mobile.ads.banner.BannerAdEventListener;
import com.yandex.mobile.ads.banner.BannerAdSize;
import com.yandex.mobile.ads.banner.BannerAdView;
import com.yandex.mobile.ads.common.AdError;
import com.yandex.mobile.ads.common.AdRequest;
import com.yandex.mobile.ads.common.AdRequestConfiguration;
import com.yandex.mobile.ads.common.AdRequestError;
import com.yandex.mobile.ads.common.ImpressionData;
import com.yandex.mobile.ads.common.MobileAds;
import com.yandex.mobile.ads.interstitial.InterstitialAd;
import com.yandex.mobile.ads.interstitial.InterstitialAdEventListener;
import com.yandex.mobile.ads.interstitial.InterstitialAdLoadListener;
import com.yandex.mobile.ads.interstitial.InterstitialAdLoader;
import com.yandex.mobile.ads.rewarded.Reward;
import com.yandex.mobile.ads.rewarded.RewardedAd;
import com.yandex.mobile.ads.rewarded.RewardedAdEventListener;
import com.yandex.mobile.ads.rewarded.RewardedAdLoadListener;
import com.yandex.mobile.ads.rewarded.RewardedAdLoader;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Yandex Mobile Ads manager class.
 * Handles banner, interstitial and opt-in rewarded ads.
 */
public class YandexAds {
    private static final String TAG = "YandexAds";
    private static final int BANNER_RETRY_DELAY_MS = 15000; // 15 seconds
    private static final int MAX_BANNER_RETRIES = 10000;
    
    private static boolean initialized = false;
    private static String bannerAdUnitId = "demo-banner-yandex";
    private static String interstitialAdUnitId = "demo-interstitial-yandex";
    private static String rewardedAdUnitId = "demo-rewarded-yandex";
    
    // Banner
    private static BannerAdView bannerAdView = null;
    private static FrameLayout bannerContainer = null;
    private static boolean bannerVisible = false;
    private static boolean bannerRequested = false; // User wants banner shown
    private static int bannerHeight = 0;
    private static int bannerRetryCount = 0;
    private static android.os.Handler retryHandler = null;
    private static Runnable retryRunnable = null;
    
    // Store references for retry
    private static Activity currentActivity = null;
    private static ViewGroup currentGameLayout = null;
    private static View currentGameView = null;
    
    // Interstitial
    private static InterstitialAdLoader interstitialAdLoader = null;
    private static InterstitialAd interstitialAd = null;
    private static boolean isInterstitialLoading = false;
    private static InterstitialCallback interstitialCallback = null;

    // Rewarded (loaded for both free and Premium users)
    private static RewardedAdLoader rewardedAdLoader = null;
    private static RewardedAd rewardedAd = null;
    private static RewardedAd activeRewardedAd = null;
    private static boolean isRewardedLoading = false;
    private static boolean isRewardedShowing = false;
    private static RewardedCallback rewardedCallback = null;
    private static int activeRewardType = 0;
    private static boolean activeRewardEarned = false;
    private static android.os.Handler rewardedRetryHandler = null;
    private static Runnable rewardedRetryRunnable = null;
    
    /**
     * Callback interface for interstitial events
     */
    public interface InterstitialCallback {
        void onInterstitialDismissed();
        void onInterstitialFailed();
    }

    public interface RewardedCallback {
        void onRewardedClosed(boolean earned);
        void onRewardedFailed();
    }
    
    /**
     * Initialize Yandex Mobile Ads SDK
     */
    public static void init(Activity activity) {
        if (initialized) {
            Log.d(TAG, "Already initialized");
            return;
        }

        currentActivity = activity;
        Log.d(TAG, "Initializing Yandex Mobile Ads...");
        
        // Load ad unit IDs from secrets.properties
        loadAdUnitIds(activity);
        
        // Initialize SDK
        MobileAds.initialize(activity, () -> {
            Log.i(TAG, "Yandex Mobile Ads SDK initialized successfully");
            initialized = true;
            
            // Initialize interstitial loader
            initInterstitialLoader(activity);
            initRewardedLoader(activity);

            // Preload first interstitial
            loadInterstitial();
            loadRewarded();
        });
    }
    
    /**
     * Load ad unit IDs from secrets.properties based on build type
     */
    private static void loadAdUnitIds(Activity activity) {
        boolean isDebug = BuildConfig.DEBUG;
        String suffix = isDebug ? "_debug" : "_production";
        
        Log.d(TAG, "Loading ad unit IDs for " + (isDebug ? "DEBUG" : "PRODUCTION") + " build");
        
        try {
            InputStream inputStream = activity.getAssets().open("secrets.properties");
            Properties properties = new Properties();
            properties.load(inputStream);
            inputStream.close();
            
            String bannerId = properties.getProperty("yandex_banner_ad_unit_id" + suffix);
            String interstitialId = properties.getProperty("yandex_interstitial_ad_unit_id" + suffix);
            String rewardedId = properties.getProperty("yandex_rewarded_ad_unit_id" + suffix);
            
            if (bannerId != null && !bannerId.isEmpty() && !bannerId.startsWith("R-M-XXXX")) {
                bannerAdUnitId = bannerId;
                Log.d(TAG, "Loaded banner ad unit ID: " + bannerAdUnitId);
            } else {
                Log.w(TAG, "Using demo banner ad unit ID (production ID not configured)");
            }
            
            if (interstitialId != null && !interstitialId.isEmpty() && !interstitialId.startsWith("R-M-XXXX")) {
                interstitialAdUnitId = interstitialId;
                Log.d(TAG, "Loaded interstitial ad unit ID: " + interstitialAdUnitId);
            } else {
                Log.w(TAG, "Using demo interstitial ad unit ID (production ID not configured)");
            }

            if (rewardedId != null && !rewardedId.isEmpty() && !rewardedId.startsWith("R-M-XXXX")) {
                rewardedAdUnitId = rewardedId;
                Log.d(TAG, "Loaded rewarded ad unit ID");
            } else {
                Log.w(TAG, "Using demo rewarded ad unit ID (production ID not configured)");
            }
        } catch (IOException e) {
            Log.w(TAG, "secrets.properties not found, using demo ad unit IDs");
        }
    }
    
    /**
     * Initialize interstitial ad loader
     */
    private static void initInterstitialLoader(Activity activity) {
        try {
            interstitialAdLoader = new InterstitialAdLoader(activity);
            interstitialAdLoader.setAdLoadListener(new InterstitialAdLoadListener() {
                @Override
                public void onAdLoaded(InterstitialAd ad) {
                    try {
                        Log.i(TAG, "Interstitial ad loaded successfully");
                        interstitialAd = ad;
                        isInterstitialLoading = false;
                    } catch (Exception e) {
                        Log.e(TAG, "Error in onAdLoaded: " + e.getMessage());
                    }
                }
                
                @Override
                public void onAdFailedToLoad(AdRequestError error) {
                    try {
                        Log.e(TAG, "Interstitial ad failed to load: " + error.getDescription());
                        interstitialAd = null;
                        isInterstitialLoading = false;
                    } catch (Exception e) {
                        Log.e(TAG, "Error in onAdFailedToLoad: " + e.getMessage());
                    }
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Error initializing interstitial loader: " + e.getMessage());
            interstitialAdLoader = null;
        }
    }
    
    /**
     * Load interstitial ad
     */
    private static void loadInterstitial() {
        // Skip loading ads if user has active subscription
        if (YooKassaPay.hasPurchase()) {
            Log.d(TAG, "loadInterstitial: skipped - user has subscription");
            return;
        }
        
        if (interstitialAdLoader == null || isInterstitialLoading) {
            return;
        }
        
        try {
            Log.d(TAG, "Loading interstitial ad...");
            isInterstitialLoading = true;
            
            AdRequestConfiguration config = new AdRequestConfiguration.Builder(interstitialAdUnitId).build();
            interstitialAdLoader.loadAd(config);
        } catch (Exception e) {
            Log.e(TAG, "Error loading interstitial: " + e.getMessage());
            isInterstitialLoading = false;
        }
    }
    
    /**
     * Check if interstitial ad is ready
     */
    public static boolean isInterstitialReady() {
        return interstitialAd != null;
    }
    
    /**
     * Try to show interstitial ad
     * Returns true if ad will be shown, false if no ad available
     */
    public static boolean tryShowInterstitial(Activity activity, InterstitialCallback callback) {
        // Skip showing ads if user has active subscription
        if (YooKassaPay.hasPurchase()) {
            Log.d(TAG, "tryShowInterstitial: skipped - user has subscription");
            if (callback != null) {
                callback.onInterstitialDismissed(); // Treat as dismissed so game continues
            }
            return false;
        }
        
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            Log.w(TAG, "tryShowInterstitial: activity is null or finishing");
            if (callback != null) {
                callback.onInterstitialFailed();
            }
            return false;
        }
        
        Log.d(TAG, "tryShowInterstitial called, ad ready: " + isInterstitialReady());
        Analytics.sendAdEvent(activity, "interstitial", "request", interstitialAdUnitId);
        
        if (interstitialAd == null) {
            Log.w(TAG, "Interstitial not ready, attempting to load");
            Analytics.sendAdEvent(activity, "interstitial", "not_ready", interstitialAdUnitId);
            loadInterstitial();
            if (callback != null) {
                callback.onInterstitialFailed();
            }
            return false;
        }
        
        interstitialCallback = callback;
        
        interstitialAd.setAdEventListener(new InterstitialAdEventListener() {
            @Override
            public void onAdShown() {
                try {
                    Log.d(TAG, "Interstitial ad shown");
                    Analytics.sendAdEvent(activity, "interstitial", "shown", interstitialAdUnitId);
                } catch (Exception e) {
                    Log.e(TAG, "Error in onAdShown: " + e.getMessage());
                }
            }
            
            @Override
            public void onAdFailedToShow(AdError error) {
                try {
                    Log.e(TAG, "Interstitial failed to show: " + error.getDescription());
                    Analytics.sendAdEvent(activity, "interstitial", "failed_to_show", 
                        interstitialAdUnitId + "|" + error.getDescription());
                    cleanupInterstitial();
                    loadInterstitial();
                    InterstitialCallback cb = interstitialCallback;
                    interstitialCallback = null;
                    if (cb != null) {
                        cb.onInterstitialFailed();
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error in onAdFailedToShow: " + e.getMessage());
                }
            }
            
            @Override
            public void onAdDismissed() {
                try {
                    Log.d(TAG, "Interstitial ad dismissed");
                    Analytics.sendAdEvent(activity, "interstitial", "dismissed", interstitialAdUnitId);
                    cleanupInterstitial();
                    loadInterstitial();
                    InterstitialCallback cb = interstitialCallback;
                    interstitialCallback = null;
                    if (cb != null) {
                        cb.onInterstitialDismissed();
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error in onAdDismissed: " + e.getMessage());
                }
            }
            
            @Override
            public void onAdClicked() {
                try {
                    Log.d(TAG, "Interstitial ad clicked");
                    Analytics.sendAdEvent(activity, "interstitial", "clicked", interstitialAdUnitId);
                } catch (Exception e) {
                    Log.e(TAG, "Error in onAdClicked: " + e.getMessage());
                }
            }
            
            @Override
            public void onAdImpression(ImpressionData impressionData) {
                try {
                    Log.d(TAG, "Interstitial ad impression recorded");
                    Analytics.sendAdEvent(activity, "interstitial", "impression", interstitialAdUnitId);
                } catch (Exception e) {
                    Log.e(TAG, "Error in onAdImpression: " + e.getMessage());
                }
            }
        });
        
        interstitialAd.show(activity);
        return true;
    }
    
    /**
     * Cleanup interstitial ad after showing
     */
    private static void cleanupInterstitial() {
        if (interstitialAd != null) {
            try {
                interstitialAd.setAdEventListener(null);
            } catch (Exception e) {
                Log.e(TAG, "Error clearing interstitial listener: " + e.getMessage());
            }
            interstitialAd = null;
        }
    }

    private static void initRewardedLoader(Activity activity) {
        try {
            rewardedAdLoader = new RewardedAdLoader(activity);
            rewardedAdLoader.setAdLoadListener(new RewardedAdLoadListener() {
                @Override
                public void onAdLoaded(RewardedAd ad) {
                    rewardedAd = ad;
                    isRewardedLoading = false;
                    cancelRewardedRetry();
                    Analytics.sendAdEvent(activity, "rewarded", "loaded", rewardedAdUnitId);
                    RewardOverlayManager.onAdAvailabilityChanged();
                    Log.i(TAG, "Rewarded ad loaded successfully");
                }

                @Override
                public void onAdFailedToLoad(AdRequestError error) {
                    rewardedAd = null;
                    isRewardedLoading = false;
                    Analytics.sendAdEvent(activity, "rewarded", "failed",
                            rewardedAdUnitId + "|" + error.getCode() + "|" + error.getDescription());
                    RewardOverlayManager.onAdAvailabilityChanged();
                    scheduleRewardedRetry();
                    Log.e(TAG, "Rewarded ad failed to load: " + error.getDescription());
                }
            });
        } catch (Exception e) {
            rewardedAdLoader = null;
            isRewardedLoading = false;
            Log.e(TAG, "Error initializing rewarded loader: " + e.getMessage());
        }
    }

    private static void loadRewarded() {
        if (!initialized || rewardedAdLoader == null || rewardedAd != null
                || isRewardedLoading || isRewardedShowing) {
            return;
        }
        try {
            isRewardedLoading = true;
            Analytics.sendAdEvent(currentActivity, "rewarded", "request", rewardedAdUnitId);
            AdRequestConfiguration config =
                    new AdRequestConfiguration.Builder(rewardedAdUnitId).build();
            rewardedAdLoader.loadAd(config);
        } catch (Exception e) {
            isRewardedLoading = false;
            scheduleRewardedRetry();
            Log.e(TAG, "Error loading rewarded ad: " + e.getMessage());
        }
    }

    public static void ensureRewardedLoaded() {
        Activity host = currentActivity;
        if (host == null || host.isFinishing() || host.isDestroyed()) {
            return;
        }
        host.runOnUiThread(YandexAds::loadRewarded);
    }

    public static boolean isRewardedReady() {
        return rewardedAd != null && !isRewardedShowing;
    }

    /**
     * Rewarded ads are opt-in bonuses and therefore remain available to
     * Premium users. The reward is reported only from onRewarded().
     */
    public static boolean tryShowRewarded(Activity activity, int rewardType,
            RewardedCallback callback) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            if (callback != null) {
                callback.onRewardedFailed();
            }
            return false;
        }
        if (rewardedAd == null) {
            Analytics.sendAdEvent(activity, "rewarded", "not_ready", rewardedAdUnitId);
            loadRewarded();
            if (callback != null) {
                callback.onRewardedFailed();
            }
            return false;
        }

        // A rewarded object is single-use. Move it out of the ready slot before
        // showing so every bonus button disappears immediately and cannot reuse
        // the same video while a replacement is still loading.
        activeRewardedAd = rewardedAd;
        rewardedAd = null;
        isRewardedShowing = true;
        RewardOverlayManager.onAdAvailabilityChanged();

        rewardedCallback = callback;
        activeRewardType = rewardType;
        activeRewardEarned = false;
        RewardOverlayManager.trackAdEvent(rewardType, "request", null);
        Analytics.sendAdEvent(activity, "rewarded", "show_request", rewardedAdUnitId);

        activeRewardedAd.setAdEventListener(new RewardedAdEventListener() {
            @Override
            public void onAdShown() {
                Analytics.sendAdEvent(activity, "rewarded", "shown", rewardedAdUnitId);
                RewardOverlayManager.trackAdEvent(activeRewardType, "shown", null);
            }

            @Override
            public void onAdFailedToShow(AdError error) {
                int failedType = activeRewardType;
                Analytics.sendAdEvent(activity, "rewarded", "failed_to_show",
                        rewardedAdUnitId + "|show|" + error.getDescription());
                RewardOverlayManager.trackAdEvent(
                        failedType, "failed_to_show", error.getDescription());
                RewardedCallback cb = rewardedCallback;
                cleanupRewarded();
                loadRewarded();
                if (cb != null) {
                    cb.onRewardedFailed();
                }
            }

            @Override
            public void onAdDismissed() {
                int dismissedType = activeRewardType;
                boolean earned = activeRewardEarned;
                Analytics.sendAdEvent(activity, "rewarded", "dismissed", rewardedAdUnitId);
                RewardOverlayManager.trackAdEvent(
                        dismissedType, earned ? "dismissed_earned" : "dismissed_early", null);
                RewardedCallback cb = rewardedCallback;
                cleanupRewarded();
                loadRewarded();
                if (cb != null) {
                    cb.onRewardedClosed(earned);
                }
            }

            @Override
            public void onAdClicked() {
                Analytics.sendAdEvent(activity, "rewarded", "clicked", rewardedAdUnitId);
                RewardOverlayManager.trackAdEvent(activeRewardType, "clicked", null);
            }

            @Override
            public void onAdImpression(ImpressionData impressionData) {
                Analytics.sendAdEvent(activity, "rewarded", "impression", rewardedAdUnitId);
                RewardOverlayManager.trackAdEvent(activeRewardType, "impression", null);
            }

            @Override
            public void onRewarded(Reward reward) {
                activeRewardEarned = true;
                Analytics.sendAdEvent(activity, "rewarded", "rewarded", rewardedAdUnitId);
                RewardOverlayManager.trackAdEvent(
                        activeRewardType, "rewarded",
                        reward.getType() + "_" + reward.getAmount());
            }
        });

        activeRewardedAd.show(activity);
        return true;
    }

    private static void cleanupRewarded() {
        if (activeRewardedAd != null) {
            try {
                activeRewardedAd.setAdEventListener(null);
            } catch (Exception e) {
                Log.e(TAG, "Error clearing rewarded listener: " + e.getMessage());
            }
        }
        activeRewardedAd = null;
        isRewardedShowing = false;
        rewardedCallback = null;
        activeRewardType = 0;
        activeRewardEarned = false;
        isRewardedLoading = false;
        RewardOverlayManager.onAdAvailabilityChanged();
    }

    private static void scheduleRewardedRetry() {
        Activity host = currentActivity;
        if (!initialized || host == null || host.isFinishing() || host.isDestroyed()) {
            return;
        }
        if (rewardedRetryHandler == null) {
            rewardedRetryHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        }
        if (rewardedRetryRunnable != null) {
            rewardedRetryHandler.removeCallbacks(rewardedRetryRunnable);
        }
        rewardedRetryRunnable = () -> {
            rewardedRetryRunnable = null;
            loadRewarded();
        };
        rewardedRetryHandler.postDelayed(rewardedRetryRunnable, 30000);
    }

    private static void cancelRewardedRetry() {
        if (rewardedRetryHandler != null && rewardedRetryRunnable != null) {
            rewardedRetryHandler.removeCallbacks(rewardedRetryRunnable);
        }
        rewardedRetryRunnable = null;
    }
    
    /**
     * Show adaptive sticky banner at the bottom of the screen
     * This resizes the game view to make room for the banner
     */
    public static void showBanner(Activity activity, ViewGroup gameLayout, View gameView) {
        // Skip showing ads if user has active subscription
        if (YooKassaPay.hasPurchase()) {
            Log.d(TAG, "showBanner: skipped - user has subscription");
            return;
        }
        
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            Log.w(TAG, "showBanner: activity is null or finishing");
            return;
        }
        
        // Mark that we want banner shown (for retry logic)
        bannerRequested = true;
        bannerRetryCount = 0;
        currentActivity = activity;
        currentGameLayout = gameLayout;
        currentGameView = gameView;
        
        if (bannerVisible) {
            Log.d(TAG, "Banner already visible");
            return;
        }
        
        activity.runOnUiThread(() -> {
            if (activity.isFinishing() || activity.isDestroyed()) {
                return;
            }
            
            Log.d(TAG, "Showing banner ad...");
            
            // Calculate adaptive banner size
            DisplayMetrics displayMetrics = activity.getResources().getDisplayMetrics();
            int adWidth = (int) (displayMetrics.widthPixels / displayMetrics.density);
            BannerAdSize adSize = BannerAdSize.stickySize(activity, adWidth);
            bannerHeight = adSize.getHeight();
            
            Log.d(TAG, "Banner size: " + adWidth + "x" + bannerHeight);
            
            // Send analytics: banner request
            Analytics.sendAdEvent(activity, "banner", "request", bannerAdUnitId);
            
            // Create banner container at bottom
            if (bannerContainer == null) {
                bannerContainer = new FrameLayout(activity);
                bannerContainer.setId(View.generateViewId());
            }
            
            // Create banner ad view
            bannerAdView = new BannerAdView(activity);
            bannerAdView.setAdUnitId(bannerAdUnitId);
            bannerAdView.setAdSize(adSize);
            
            bannerAdView.setBannerAdEventListener(new BannerAdEventListener() {
                @Override
                public void onAdLoaded() {
                    Log.i(TAG, "Banner ad loaded");
                    bannerVisible = true;
                    bannerRetryCount = 0; // Reset retry count on success
                    Analytics.sendAdEvent(activity, "banner", "loaded", bannerAdUnitId);
                }
                
                @Override
                public void onAdFailedToLoad(AdRequestError error) {
                    Log.e(TAG, "Banner ad failed to load: " + error.getDescription());
                    Analytics.sendAdEvent(activity, "banner", "failed", 
                        bannerAdUnitId + "|" + error.getCode() + "|" + error.getDescription());
                    
                    // Clean up failed banner
                    cleanupBannerView(activity, gameLayout, gameView);
                    
                    // Schedule retry if user still wants banner and we haven't exceeded max retries
                    if (bannerRequested && bannerRetryCount < MAX_BANNER_RETRIES) {
                        bannerRetryCount++;
                        Log.d(TAG, "Scheduling banner retry " + bannerRetryCount + "/" + MAX_BANNER_RETRIES);
                        scheduleBannerRetry(activity, gameLayout, gameView);
                    }
                }
                
                @Override
                public void onAdClicked() {
                    Log.d(TAG, "Banner ad clicked");
                    Analytics.sendAdEvent(activity, "banner", "clicked", bannerAdUnitId);
                }
                
                @Override
                public void onLeftApplication() {
                    Log.d(TAG, "Left application from banner");
                }
                
                @Override
                public void onReturnedToApplication() {
                    Log.d(TAG, "Returned to application from banner");
                }
                
                @Override
                public void onImpression(ImpressionData impressionData) {
                    Log.d(TAG, "Banner ad impression recorded");
                    Analytics.sendAdEvent(activity, "banner", "impression", bannerAdUnitId);
                }
            });
            
            // Add banner to container
            FrameLayout.LayoutParams bannerParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            );
            bannerContainer.addView(bannerAdView, bannerParams);
            
            // Add container to layout at bottom
            RelativeLayout.LayoutParams containerParams = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            );
            containerParams.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
            gameLayout.addView(bannerContainer, containerParams);
            
            // Adjust game view to be above banner
            if (gameView.getLayoutParams() instanceof RelativeLayout.LayoutParams) {
                RelativeLayout.LayoutParams gameParams = (RelativeLayout.LayoutParams) gameView.getLayoutParams();
                gameParams.addRule(RelativeLayout.ABOVE, bannerContainer.getId());
                gameView.setLayoutParams(gameParams);
            }
            
            // Load the ad
            bannerAdView.loadAd(new AdRequest.Builder().build());
            
            Log.d(TAG, "Banner setup complete, loading ad...");
        });
    }
    
    /**
     * Schedule a banner retry after delay
     */
    private static void scheduleBannerRetry(Activity activity, ViewGroup gameLayout, View gameView) {
        // Don't schedule retry if user has subscription
        if (YooKassaPay.hasPurchase()) {
            Log.d(TAG, "scheduleBannerRetry: skipped - user has subscription");
            return;
        }
        
        // Don't schedule retry if we're shutting down
        if (!bannerRequested || activity == null || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        
        if (retryHandler == null) {
            retryHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        }
        
        // Cancel any pending retry
        if (retryRunnable != null) {
            retryHandler.removeCallbacks(retryRunnable);
        }
        
        retryRunnable = () -> {
            try {
                if (bannerRequested && !bannerVisible && 
                    activity != null && !activity.isFinishing() && !activity.isDestroyed()) {
                    Log.d(TAG, "Retrying banner load...");
                    showBannerInternal(activity, gameLayout, gameView);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error during banner retry: " + e.getMessage());
            }
        };
        
        retryHandler.postDelayed(retryRunnable, BANNER_RETRY_DELAY_MS);
    }
    
    /**
     * Clean up banner view without affecting retry logic
     */
    private static void cleanupBannerView(Activity activity, ViewGroup gameLayout, View gameView) {
        if (bannerAdView != null) {
            bannerAdView.destroy();
            bannerAdView = null;
        }
        
        if (bannerContainer != null && gameLayout != null) {
            try {
                gameLayout.removeView(bannerContainer);
            } catch (Exception e) {
                Log.e(TAG, "Error removing banner container: " + e.getMessage());
            }
            bannerContainer.removeAllViews();
            bannerContainer = null;
        }
        
        // Restore game view layout
        if (gameView != null && gameView.getLayoutParams() instanceof RelativeLayout.LayoutParams) {
            RelativeLayout.LayoutParams gameParams = (RelativeLayout.LayoutParams) gameView.getLayoutParams();
            gameParams.removeRule(RelativeLayout.ABOVE);
            gameView.setLayoutParams(gameParams);
        }
        
        bannerVisible = false;
        bannerHeight = 0;
    }
    
    /**
     * Internal method to show banner (used for initial and retry)
     */
    private static void showBannerInternal(Activity activity, ViewGroup gameLayout, View gameView) {
        // Skip if user has subscription
        if (YooKassaPay.hasPurchase()) {
            Log.d(TAG, "showBannerInternal: skipped - user has subscription");
            return;
        }
        
        if (activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        
        // Same logic as in showBanner's runOnUiThread block
        DisplayMetrics displayMetrics = activity.getResources().getDisplayMetrics();
        int adWidth = (int) (displayMetrics.widthPixels / displayMetrics.density);
        BannerAdSize adSize = BannerAdSize.stickySize(activity, adWidth);
        bannerHeight = adSize.getHeight();
        
        Log.d(TAG, "Banner retry - size: " + adWidth + "x" + bannerHeight);
        Analytics.sendAdEvent(activity, "banner", "retry_request", bannerAdUnitId + "|attempt_" + bannerRetryCount);
        
        if (bannerContainer == null) {
            bannerContainer = new FrameLayout(activity);
            bannerContainer.setId(View.generateViewId());
        }
        
        bannerAdView = new BannerAdView(activity);
        bannerAdView.setAdUnitId(bannerAdUnitId);
        bannerAdView.setAdSize(adSize);
        
        bannerAdView.setBannerAdEventListener(new BannerAdEventListener() {
            @Override
            public void onAdLoaded() {
                Log.i(TAG, "Banner ad loaded on retry");
                bannerVisible = true;
                bannerRetryCount = 0;
                Analytics.sendAdEvent(activity, "banner", "loaded_on_retry", bannerAdUnitId);
            }
            
            @Override
            public void onAdFailedToLoad(AdRequestError error) {
                Log.e(TAG, "Banner retry failed: " + error.getDescription());
                Analytics.sendAdEvent(activity, "banner", "retry_failed", 
                    bannerAdUnitId + "|" + error.getCode() + "|attempt_" + bannerRetryCount);
                
                cleanupBannerView(activity, gameLayout, gameView);
                
                if (bannerRequested && bannerRetryCount < MAX_BANNER_RETRIES) {
                    bannerRetryCount++;
                    scheduleBannerRetry(activity, gameLayout, gameView);
                }
            }
            
            @Override
            public void onAdClicked() {
                Analytics.sendAdEvent(activity, "banner", "clicked", bannerAdUnitId);
            }
            
            @Override
            public void onLeftApplication() {}
            
            @Override
            public void onReturnedToApplication() {}
            
            @Override
            public void onImpression(ImpressionData impressionData) {
                Analytics.sendAdEvent(activity, "banner", "impression", bannerAdUnitId);
            }
        });
        
        FrameLayout.LayoutParams bannerParams = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        bannerContainer.addView(bannerAdView, bannerParams);
        
        RelativeLayout.LayoutParams containerParams = new RelativeLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        containerParams.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
        gameLayout.addView(bannerContainer, containerParams);
        
        if (gameView.getLayoutParams() instanceof RelativeLayout.LayoutParams) {
            RelativeLayout.LayoutParams gameParams = (RelativeLayout.LayoutParams) gameView.getLayoutParams();
            gameParams.addRule(RelativeLayout.ABOVE, bannerContainer.getId());
            gameView.setLayoutParams(gameParams);
        }
        
        bannerAdView.loadAd(new AdRequest.Builder().build());
    }
    
    /**
     * Hide the banner and restore game view
     */
    public static void hideBanner(Activity activity, ViewGroup gameLayout, View gameView) {
        // Stop requesting banner
        bannerRequested = false;
        bannerRetryCount = 0;
        
        // Cancel pending retries
        if (retryHandler != null && retryRunnable != null) {
            retryHandler.removeCallbacks(retryRunnable);
            retryRunnable = null;
        }
        
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            Log.w(TAG, "hideBanner: activity is null or finishing");
            bannerVisible = false;
            return;
        }
        
        activity.runOnUiThread(() -> {
            hideBannerInternal(activity, gameLayout, gameView);
        });
    }
    
    private static void hideBannerInternal(Activity activity, ViewGroup gameLayout, View gameView) {
        Log.d(TAG, "Hiding banner ad...");
        
        if (bannerAdView != null) {
            bannerAdView.destroy();
            bannerAdView = null;
        }
        
        if (bannerContainer != null && gameLayout != null) {
            try {
                gameLayout.removeView(bannerContainer);
            } catch (Exception e) {
                Log.e(TAG, "Error removing banner container: " + e.getMessage());
            }
            bannerContainer.removeAllViews();
            bannerContainer = null;
        }
        
        // Restore game view layout
        if (gameView != null && gameView.getLayoutParams() instanceof RelativeLayout.LayoutParams) {
            RelativeLayout.LayoutParams gameParams = (RelativeLayout.LayoutParams) gameView.getLayoutParams();
            gameParams.removeRule(RelativeLayout.ABOVE);
            gameView.setLayoutParams(gameParams);
        }
        
        bannerVisible = false;
        bannerHeight = 0;
        
        Log.d(TAG, "Banner hidden");
    }
    
    /**
     * Check if banner is currently visible
     */
    public static boolean isBannerVisible() {
        return bannerVisible;
    }
    
    /**
     * Get current banner height in dp
     */
    public static int getBannerHeight() {
        return bannerHeight;
    }
    
    /**
     * Destroy all ads and release resources
     * Should be called in Activity.onDestroy()
     */
    public static void destroy() {
        Log.d(TAG, "Destroying YandexAds resources");
        
        // Stop requesting banner first
        bannerRequested = false;
        
        // Cancel any pending retries immediately
        if (retryHandler != null) {
            if (retryRunnable != null) {
                retryHandler.removeCallbacks(retryRunnable);
                retryRunnable = null;
            }
            retryHandler.removeCallbacksAndMessages(null);
            retryHandler = null;
        }
        
        // Clear interstitial callback to prevent calls after destroy
        interstitialCallback = null;

        cancelRewardedRetry();
        if (rewardedRetryHandler != null) {
            rewardedRetryHandler.removeCallbacksAndMessages(null);
            rewardedRetryHandler = null;
        }
        rewardedCallback = null;
        
        // Cleanup interstitial
        if (interstitialAd != null) {
            try {
                interstitialAd.setAdEventListener(null);
            } catch (Exception e) {
                Log.e(TAG, "Error clearing interstitial listener: " + e.getMessage());
            }
            interstitialAd = null;
        }
        
        if (interstitialAdLoader != null) {
            try {
                interstitialAdLoader.setAdLoadListener(null);
            } catch (Exception e) {
                Log.e(TAG, "Error clearing interstitial loader listener: " + e.getMessage());
            }
            interstitialAdLoader = null;
        }

        if (rewardedAd != null) {
            try {
                rewardedAd.setAdEventListener(null);
            } catch (Exception e) {
                Log.e(TAG, "Error clearing rewarded listener: " + e.getMessage());
            }
            rewardedAd = null;
        }
        if (activeRewardedAd != null) {
            try {
                activeRewardedAd.setAdEventListener(null);
            } catch (Exception e) {
                Log.e(TAG, "Error clearing active rewarded listener: " + e.getMessage());
            }
            activeRewardedAd = null;
        }
        if (rewardedAdLoader != null) {
            try {
                rewardedAdLoader.setAdLoadListener(null);
            } catch (Exception e) {
                Log.e(TAG, "Error clearing rewarded loader listener: " + e.getMessage());
            }
            rewardedAdLoader = null;
        }
        
        // Cleanup banner
        if (bannerAdView != null) {
            try {
                bannerAdView.setBannerAdEventListener(null);
                bannerAdView.destroy();
            } catch (Exception e) {
                Log.e(TAG, "Error destroying banner: " + e.getMessage());
            }
            bannerAdView = null;
        }
        
        // Clear container reference
        if (bannerContainer != null) {
            try {
                bannerContainer.removeAllViews();
            } catch (Exception e) {
                Log.e(TAG, "Error clearing banner container: " + e.getMessage());
            }
            bannerContainer = null;
        }
        
        // Clear all static references to prevent memory leaks
        currentActivity = null;
        currentGameLayout = null;
        currentGameView = null;
        
        // Reset state
        bannerVisible = false;
        bannerHeight = 0;
        bannerRetryCount = 0;
        isInterstitialLoading = false;
        isRewardedLoading = false;
        isRewardedShowing = false;
        activeRewardType = 0;
        activeRewardEarned = false;
        initialized = false;
        
        Log.d(TAG, "YandexAds resources destroyed");
    }
}
