package com.VocoCraft.VocoCraft

import android.content.Context
import android.os.Build
import android.util.Log

/** Resolves the store that installed this package for runtime-only integrations. */
object StoreDistribution {
    private const val TAG = "StoreDistribution"
    private const val GOOGLE_PLAY_INSTALLER = "com.android.vending"

    @JvmStatic
    fun isGooglePlayInstall(context: Context): Boolean {
        val installer = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.packageManager
                    .getInstallSourceInfo(context.packageName)
                    .installingPackageName
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getInstallerPackageName(context.packageName)
            }
        } catch (throwable: Throwable) {
            Log.d(TAG, "Unable to resolve install source: ${throwable.message}")
            null
        }

        return installer == GOOGLE_PLAY_INSTALLER
    }
}
