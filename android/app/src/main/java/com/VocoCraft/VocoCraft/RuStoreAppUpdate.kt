/*
 * VocoCraft - RuStore In-App Updates SDK integration.
 *
 * Uses the FLEXIBLE flow: RuStore asks the user for confirmation, downloads
 * the update, and then shows the installation confirmation UI.
 *
 * Official documentation:
 * https://www.rustore.ru/help/sdk/updates/kotlin-java
 */

package com.VocoCraft.VocoCraft

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.Keep
import org.json.JSONObject
import ru.rustore.sdk.appupdate.listener.InstallStateUpdateListener
import ru.rustore.sdk.appupdate.manager.RuStoreAppUpdateManager
import ru.rustore.sdk.appupdate.manager.factory.RuStoreAppUpdateManagerFactory
import ru.rustore.sdk.appupdate.model.AppUpdateInfo
import ru.rustore.sdk.appupdate.model.AppUpdateOptions
import ru.rustore.sdk.appupdate.model.AppUpdateType
import ru.rustore.sdk.appupdate.model.InstallState
import ru.rustore.sdk.appupdate.model.InstallStatus
import ru.rustore.sdk.appupdate.model.UpdateAvailability

@Keep
@Suppress("unused")
object RuStoreAppUpdate {

    private const val TAG = "RuStoreAppUpdate"
    private const val SDK_VERSION = "10.2.0"
    private const val RESUME_CHECK_DELAY_MS = 750L

    private val mainHandler = Handler(Looper.getMainLooper())

    private var activity: Activity? = null
    private var updateManager: RuStoreAppUpdateManager? = null
    private var installListener: InstallStateUpdateListener? = null

    @Volatile
    private var checkInProgress = false

    @Volatile
    private var updateFlowActive = false

    @Volatile
    private var promptAttemptedThisSession = false

    @Volatile
    private var completeUpdateRequested = false

    private val resumeCheck = Runnable {
        checkForUpdate()
    }

    @JvmStatic
    fun init(hostActivity: Activity) {
        if (StoreDistribution.isGooglePlayInstall(hostActivity)) {
            Log.i(TAG, "Disabled for a Google Play installation")
            return
        }
        activity = hostActivity
        if (updateManager != null) {
            return
        }

        try {
            val manager = RuStoreAppUpdateManagerFactory.create(
                hostActivity.applicationContext
            )
            updateManager = manager
            val listener = InstallStateUpdateListener { state ->
                handleInstallState(state)
            }
            installListener = listener
            manager.registerListener(listener)
            track("initialized")
            Log.i(TAG, "RuStore App Update SDK $SDK_VERSION initialized")
        } catch (throwable: Throwable) {
            Log.w(TAG, "App Update SDK initialization failed", throwable)
            trackFailure("init", throwable)
        }
    }

    @JvmStatic
    fun onResume(hostActivity: Activity) {
        if (StoreDistribution.isGooglePlayInstall(hostActivity)) {
            return
        }
        activity = hostActivity
        if (updateManager == null) {
            init(hostActivity)
        }
        mainHandler.removeCallbacks(resumeCheck)
        mainHandler.postDelayed(resumeCheck, RESUME_CHECK_DELAY_MS)
    }

    /**
     * True while RuStore is checking, prompting, downloading, or installing.
     * Other promotional dialogs use this to avoid competing with an update.
     */
    @JvmStatic
    fun isUpdateFlowActive(): Boolean = checkInProgress || updateFlowActive

    @JvmStatic
    fun destroy() {
        mainHandler.removeCallbacks(resumeCheck)
        val manager = updateManager
        val listener = installListener
        if (manager != null && listener != null) {
            try {
                manager.unregisterListener(listener)
            } catch (throwable: Throwable) {
                Log.d(TAG, "Listener cleanup failed: ${throwable.message}")
            }
        }
        installListener = null
        updateManager = null
        activity = null
        checkInProgress = false
        updateFlowActive = false
        completeUpdateRequested = false
    }

    private fun checkForUpdate() {
        val manager = updateManager ?: return
        if (checkInProgress || activity?.isFinishing == true ||
            activity?.isDestroyed == true
        ) {
            return
        }

        checkInProgress = true
        track("check_started")
        try {
            manager.getAppUpdateInfo()
                .addOnSuccessListener { info ->
                    checkInProgress = false
                    handleUpdateInfo(info)
                }
                .addOnFailureListener { throwable ->
                    checkInProgress = false
                    updateFlowActive = false
                    Log.i(
                        TAG,
                        "RuStore update check unavailable: " +
                            throwable.javaClass.simpleName
                    )
                    // The official guide recommends not showing SDK errors to
                    // users. Devices without RuStore continue normally.
                    trackFailure("check", throwable)
                }
        } catch (throwable: Throwable) {
            checkInProgress = false
            updateFlowActive = false
            Log.w(TAG, "Exception while checking for updates", throwable)
            trackFailure("check_exception", throwable)
        }
    }

    private fun handleUpdateInfo(info: AppUpdateInfo) {
        track(
            "check_result",
            "availability" to info.updateAvailability,
            "install_status" to info.installStatus,
            "available_version_code" to info.availableVersionCode,
            "available_version_name" to info.availableVersionName
        )

        if (info.installStatus == InstallStatus.DOWNLOADED) {
            updateFlowActive = true
            completeFlexibleUpdate()
            return
        }

        when (info.updateAvailability) {
            UpdateAvailability.UPDATE_AVAILABLE -> {
                updateFlowActive = true
                if (!promptAttemptedThisSession) {
                    startFlexibleUpdate(info)
                }
            }

            UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS -> {
                updateFlowActive = true
                Log.i(TAG, "RuStore update is already in progress")
            }

            else -> {
                updateFlowActive = false
                Log.d(TAG, "No RuStore update available")
            }
        }
    }

    private fun startFlexibleUpdate(info: AppUpdateInfo) {
        val manager = updateManager ?: return
        promptAttemptedThisSession = true

        if (!info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)) {
            updateFlowActive = false
            track("flexible_not_allowed")
            Log.w(TAG, "FLEXIBLE update is not allowed for this release")
            return
        }

        val options = AppUpdateOptions.Builder()
            .appUpdateType(AppUpdateType.FLEXIBLE)
            .build()
        track(
            "flow_started",
            "available_version_code" to info.availableVersionCode,
            "available_version_name" to info.availableVersionName
        )

        try {
            manager.startUpdateFlow(info, options)
                .addOnSuccessListener { resultCode ->
                    val accepted = resultCode == Activity.RESULT_OK
                    updateFlowActive = accepted
                    track(
                        "flow_result",
                        "result_code" to resultCode,
                        "accepted" to accepted
                    )
                    Log.i(TAG, "RuStore update flow result: $resultCode")
                }
                .addOnFailureListener { throwable ->
                    updateFlowActive = false
                    Log.w(TAG, "Unable to start RuStore update flow", throwable)
                    trackFailure("flow", throwable)
                }
        } catch (throwable: Throwable) {
            updateFlowActive = false
            Log.w(TAG, "Exception starting RuStore update flow", throwable)
            trackFailure("flow_exception", throwable)
        }
    }

    private fun handleInstallState(state: InstallState) {
        if (state.packageName != activity?.packageName) {
            return
        }

        when (state.installStatus) {
            InstallStatus.DOWNLOADING,
            InstallStatus.PENDING,
            InstallStatus.INSTALLING -> updateFlowActive = true

            InstallStatus.DOWNLOADED -> {
                updateFlowActive = true
                completeFlexibleUpdate()
            }

            InstallStatus.FAILED,
            InstallStatus.DOWNLOAD_INTERRUPTED -> {
                updateFlowActive = false
                completeUpdateRequested = false
            }
        }

        track(
            "install_state",
            "install_status" to state.installStatus,
            "bytes_downloaded" to state.bytesDownloaded,
            "total_bytes" to state.totalBytesToDownload,
            "error_code" to state.installErrorCode
        )
    }

    private fun completeFlexibleUpdate() {
        val manager = updateManager ?: return
        if (completeUpdateRequested) {
            return
        }
        completeUpdateRequested = true

        val options = AppUpdateOptions.Builder()
            .appUpdateType(AppUpdateType.FLEXIBLE)
            .build()
        track("install_requested")
        try {
            manager.completeUpdate(options)
                .addOnFailureListener { throwable ->
                    completeUpdateRequested = false
                    updateFlowActive = false
                    Log.w(TAG, "Unable to complete RuStore update", throwable)
                    trackFailure("install", throwable)
                }
        } catch (throwable: Throwable) {
            completeUpdateRequested = false
            updateFlowActive = false
            Log.w(TAG, "Exception completing RuStore update", throwable)
            trackFailure("install_exception", throwable)
        }
    }

    private fun track(action: String, vararg details: Pair<String, Any?>) {
        try {
            val actionNode = JSONObject().put(action, 1)
            val updateNode = JSONObject().put("sdk", actionNode)
            val contextNode = JSONObject()
                .put("sdk_version", SDK_VERSION)
                .put("source", "rustore")
            details.forEach { (key, value) ->
                contextNode.put(key, value ?: JSONObject.NULL)
            }
            val payload = JSONObject()
                .put("app_update", updateNode)
                .put("context", contextNode)
            Analytics.sendEventWithParams("app_update", payload.toString())
        } catch (throwable: Throwable) {
            Log.d(TAG, "Analytics serialization failed: ${throwable.message}")
        }
    }

    private fun trackFailure(stage: String, throwable: Throwable) {
        track(
            "failed",
            "stage" to stage,
            "error" to throwable.javaClass.simpleName
        )
    }
}
