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

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Yandex Mobile Ads manager class.
 * Handles banner and interstitial ads.
 */
public class YandexAds {
    private static final String TAG = "YandexAds";
    
    private static boolean initialized = false;
    private static String bannerAdUnitId = "demo-banner-yandex";
    private static String interstitialAdUnitId = "demo-interstitial-yandex";
    
    // Banner
    private static BannerAdView bannerAdView = null;
    private static FrameLayout bannerContainer = null;
    private static boolean bannerVisible = false;
    private static int bannerHeight = 0;
    
    // Interstitial
    private static InterstitialAdLoader interstitialAdLoader = null;
    private static InterstitialAd interstitialAd = null;
    private static boolean isInterstitialLoading = false;
    private static InterstitialCallback interstitialCallback = null;
    
    /**
     * Callback interface for interstitial events
     */
    public interface InterstitialCallback {
        void onInterstitialDismissed();
        void onInterstitialFailed();
    }
    
    /**
     * Initialize Yandex Mobile Ads SDK
     */
    public static void init(Activity activity) {
        if (initialized) {
            Log.d(TAG, "Already initialized");
            return;
        }
        
        Log.d(TAG, "Initializing Yandex Mobile Ads...");
        
        // Load ad unit IDs from secrets.properties
        loadAdUnitIds(activity);
        
        // Initialize SDK
        MobileAds.initialize(activity, () -> {
            Log.i(TAG, "Yandex Mobile Ads SDK initialized successfully");
            initialized = true;
            
            // Initialize interstitial loader
            initInterstitialLoader(activity);
            
            // Preload first interstitial
            loadInterstitial();
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
        } catch (IOException e) {
            Log.w(TAG, "secrets.properties not found, using demo ad unit IDs");
        }
    }
    
    /**
     * Initialize interstitial ad loader
     */
    private static void initInterstitialLoader(Activity activity) {
        interstitialAdLoader = new InterstitialAdLoader(activity);
        interstitialAdLoader.setAdLoadListener(new InterstitialAdLoadListener() {
            @Override
            public void onAdLoaded(InterstitialAd ad) {
                Log.i(TAG, "Interstitial ad loaded successfully");
                interstitialAd = ad;
                isInterstitialLoading = false;
            }
            
            @Override
            public void onAdFailedToLoad(AdRequestError error) {
                Log.e(TAG, "Interstitial ad failed to load: " + error.getDescription());
                interstitialAd = null;
                isInterstitialLoading = false;
            }
        });
    }
    
    /**
     * Load interstitial ad
     */
    private static void loadInterstitial() {
        if (interstitialAdLoader == null || isInterstitialLoading) {
            return;
        }
        
        Log.d(TAG, "Loading interstitial ad...");
        isInterstitialLoading = true;
        
        AdRequestConfiguration config = new AdRequestConfiguration.Builder(interstitialAdUnitId).build();
        interstitialAdLoader.loadAd(config);
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
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            Log.w(TAG, "tryShowInterstitial: activity is null or finishing");
            if (callback != null) {
                callback.onInterstitialFailed();
            }
            return false;
        }
        
        Log.d(TAG, "tryShowInterstitial called, ad ready: " + isInterstitialReady());
        
        if (interstitialAd == null) {
            Log.w(TAG, "Interstitial not ready, attempting to load");
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
                Log.d(TAG, "Interstitial ad shown");
            }
            
            @Override
            public void onAdFailedToShow(AdError error) {
                Log.e(TAG, "Interstitial failed to show: " + error.getDescription());
                cleanupInterstitial();
                loadInterstitial();
                if (interstitialCallback != null) {
                    interstitialCallback.onInterstitialFailed();
                    interstitialCallback = null;
                }
            }
            
            @Override
            public void onAdDismissed() {
                Log.d(TAG, "Interstitial ad dismissed");
                cleanupInterstitial();
                loadInterstitial();
                if (interstitialCallback != null) {
                    interstitialCallback.onInterstitialDismissed();
                    interstitialCallback = null;
                }
            }
            
            @Override
            public void onAdClicked() {
                Log.d(TAG, "Interstitial ad clicked");
            }
            
            @Override
            public void onAdImpression(ImpressionData impressionData) {
                Log.d(TAG, "Interstitial ad impression recorded");
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
            interstitialAd.setAdEventListener(null);
            interstitialAd = null;
        }
    }
    
    /**
     * Show adaptive sticky banner at the bottom of the screen
     * This resizes the game view to make room for the banner
     */
    public static void showBanner(Activity activity, ViewGroup gameLayout, View gameView) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            Log.w(TAG, "showBanner: activity is null or finishing");
            return;
        }
        
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
                }
                
                @Override
                public void onAdFailedToLoad(AdRequestError error) {
                    Log.e(TAG, "Banner ad failed to load: " + error.getDescription());
                    hideBannerInternal(activity, gameLayout, gameView);
                }
                
                @Override
                public void onAdClicked() {
                    Log.d(TAG, "Banner ad clicked");
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
     * Hide the banner and restore game view
     */
    public static void hideBanner(Activity activity, ViewGroup gameLayout, View gameView) {
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
     */
    public static void destroy() {
        Log.d(TAG, "Destroying YandexAds resources");
        
        if (bannerAdView != null) {
            bannerAdView.destroy();
            bannerAdView = null;
        }
        
        if (interstitialAdLoader != null) {
            interstitialAdLoader.setAdLoadListener(null);
            interstitialAdLoader = null;
        }
        
        cleanupInterstitial();
        
        bannerContainer = null;
        bannerVisible = false;
        initialized = false;
    }
}
