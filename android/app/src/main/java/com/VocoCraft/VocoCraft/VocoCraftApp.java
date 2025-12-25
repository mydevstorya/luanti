/*
VocoCraft Application
Copyright (C) 2024 VocoCraft Team

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU Lesser General Public License as published by
the Free Software Foundation; either version 2.1 of the License, or
(at your option) any later version.
*/

package com.VocoCraft.VocoCraft;

import android.app.Application;

/**
 * Application class for VocoCraft.
 * Initializes global services like Analytics (AppMetrica) and Yandex Mobile Ads.
 */
public class VocoCraftApp extends Application {
    
    @Override
    public void onCreate() {
        super.onCreate();
        
        // Initialize AppMetrica Analytics
        Analytics.init(this);
        
        // Note: Yandex Mobile Ads is initialized later in GameActivity
        // because it requires Activity context for proper initialization
    }
}
