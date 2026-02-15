/*
VocoCraft Analytics
Copyright (C) 2024 VocoCraft Team

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU Lesser General Public License as published by
the Free Software Foundation; either version 2.1 of the License, or
(at your option) any later version.
*/

package com.VocoCraft.VocoCraft;

import android.app.Application;
import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import androidx.annotation.Keep;

import io.appmetrica.analytics.AppMetrica;
import io.appmetrica.analytics.AppMetricaConfig;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Analytics wrapper for AppMetrica.
 * Provides methods for native code (via JNI) and Lua to send analytics events.
 */
@Keep
public class Analytics {
    private static final String TAG = "VocoCraft-Analytics";
    private static final String SECRETS_FILE = "secrets.properties";
    
    private static boolean initialized = false;
    private static Context appContext;
    
    /**
     * Load AppMetrica API key from secrets.properties file in assets
     */
    private static String loadApiKey(Context context) {
        Properties properties = new Properties();
        try {
            AssetManager assetManager = context.getAssets();
            InputStream inputStream = assetManager.open(SECRETS_FILE);
            properties.load(inputStream);
            inputStream.close();
            
            String apiKey = properties.getProperty("appmetrica_api_key");
            if (apiKey != null && !apiKey.isEmpty() && !apiKey.equals("YOUR_APPMETRICA_API_KEY")) {
                return apiKey;
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to load secrets.properties: " + e.getMessage());
        }
        return null;
    }
    
    /**
     * Check if running in main process (not :AppMetrica service process)
     */
    private static boolean isMainProcess(Context context) {
        String processName = getProcessName();
        String packageName = context.getPackageName();
        Log.d(TAG, "Process name: " + processName + ", package: " + packageName);
        return packageName.equals(processName);
    }
    
    private static String getProcessName() {
        // Android 28+ has direct method
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            return Application.getProcessName();
        }
        // Fallback for older versions
        try {
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.FileReader("/proc/self/cmdline"));
            String processName = reader.readLine().trim();
            reader.close();
            // Remove null bytes
            return processName.replace("\0", "");
        } catch (Exception e) {
            return "";
        }
    }
    
    /**
     * Initialize AppMetrica SDK. Should be called from Application.onCreate()
     */
    public static void init(Application application) {
        if (initialized) {
            Log.w(TAG, "AppMetrica already initialized");
            return;
        }
        
        appContext = application.getApplicationContext();
        
        // Only send app_start from main process
        boolean isMain = isMainProcess(appContext);
        Log.d(TAG, "Process check: isMainProcess=" + isMain);
        
        String apiKey = loadApiKey(appContext);
        if (apiKey == null) {
            Log.w(TAG, "AppMetrica API key not found in secrets.properties. Analytics disabled.");
            return;
        }
        
        AppMetricaConfig config = AppMetricaConfig.newConfigBuilder(apiKey)
                .withLogs()  // Enable logs for debugging, remove in production
                .withSessionTimeout(120)  // Session timeout in seconds
                .build();
        
        AppMetrica.activate(application, config);
        initialized = true;
        
        Log.i(TAG, "AppMetrica initialized successfully");
        
        // Send app_start event only from main process (not :AppMetrica service)
        if (isMain) {
            AppMetrica.reportEvent("app_start");
            Log.i(TAG, "Sent app_start event (main process)");
        }
    }
    
    /**
     * Send a simple event without parameters.
     * Called from native code via JNI.
     * 
     * @param eventName Name of the event
     */
    @Keep
    public static void sendEvent(String eventName) {
        if (!initialized) {
            Log.w(TAG, "AppMetrica not initialized, event ignored: " + eventName);
            return;
        }
        
        AppMetrica.reportEvent(eventName);
        Log.d(TAG, "Event sent: " + eventName);
    }
    
    /**
     * Send an event with a JSON string of parameters.
     * Called from native code via JNI.
     * 
     * @param eventName Name of the event
     * @param jsonParams JSON string with event parameters
     */
    @Keep
    public static void sendEventWithParams(String eventName, String jsonParams) {
        if (!initialized) {
            Log.w(TAG, "AppMetrica not initialized, event ignored: " + eventName);
            return;
        }
        
        // AppMetrica accepts JSON string directly
        AppMetrica.reportEvent(eventName, jsonParams);
        Log.d(TAG, "Event sent: " + eventName + " with params: " + jsonParams);
    }
    
    /**
     * Send world creation event.
     * 
     * @param worldName Name of the created world
     * @param gameId ID of the game/mod used
     * @param mapgen Mapgen type used
     */
    @Keep
    public static void sendWorldCreatedEvent(String worldName, String gameId, String mapgen) {
        Log.i(TAG, "sendWorldCreatedEvent called: world=" + worldName + ", game=" + gameId + ", mapgen=" + mapgen);
        
        if (!initialized) {
            Log.w(TAG, "AppMetrica not initialized, world_created event ignored");
            return;
        }
        
        Map<String, Object> params = new HashMap<>();
        // Don't send world_name for privacy
        //params.put("game_id", gameId);
        params.put("mapgen", mapgen);
        
        Log.i(TAG, "Sending world_created event to AppMetrica...");
        AppMetrica.reportEvent("world_created", params);
        Log.i(TAG, "world_created event sent successfully!");
    }
    
    /**
     * Send a generic event with action and detail parameters.
     * Unlike sendAdEvent, the event name has no prefix.
     *
     * @param eventName Name of the event (e.g. "internet_check")
     * @param action    Action performed (e.g. "blocked", "retry_clicked")
     * @param detail    Additional detail string
     */
    @Keep
    public static void sendEvent(String eventName, String action, String detail) {
        if (!initialized) return;
        Map<String, Object> params = new HashMap<>();
        params.put("action", action);
        if (detail != null) {
            params.put("detail", detail);
        }
        AppMetrica.reportEvent(eventName, params);
        Log.d(TAG, "Event: " + eventName + ", action=" + action + ", detail=" + detail);
    }

    /**
     * Send ad-related event for tracking ad performance.
     * Events are sent as "ad_banner" or "ad_interstitial" with structured parameters.
     * 
     * @param context Context for checking initialization
     * @param adType Type of ad: "banner" or "interstitial"
     * @param action Action: "request", "loaded", "failed", "shown", "clicked", "impression", "dismissed", etc.
     * @param details Additional details (ad unit ID, error message, etc.)
     */
    @Keep
    public static void sendAdEvent(Context context, String adType, String action, String details) {
        if (!initialized || context == null) {
            return;
        }
        
        // Additional safety check for context validity
        if (context instanceof android.app.Activity) {
            android.app.Activity activity = (android.app.Activity) context;
            if (activity.isFinishing() || activity.isDestroyed()) {
                return;
            }
        }
        
        Map<String, Object> params = new HashMap<>();
        params.put("action", action);
        params.put("ad_unit_id", details != null && details.contains("|") ? details.split("\\|")[0] : details);
        
        // Parse additional details if present (format: "ad_unit_id|error_code|description")
        if (details != null && details.contains("|")) {
            String[] parts = details.split("\\|");
            if (parts.length >= 2) {
                params.put("error_code", parts[1]);
            }
            if (parts.length >= 3) {
                params.put("error_message", parts[2]);
            }
        }
        
        // Send as "ad_banner" or "ad_interstitial" event
        String eventName = "ad_" + adType;
        AppMetrica.reportEvent(eventName, params);
        Log.d(TAG, "Ad event: " + eventName + ", action=" + action);
    }
}
