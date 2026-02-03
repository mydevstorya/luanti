// Luanti
// SPDX-License-Identifier: LGPL-2.1-or-later
// Copyright (C) 2014 celeron55, Perttu Ahola <celeron55@gmail.com>

#pragma once

#ifndef __ANDROID__
#error This header has to be included on Android port only!
#endif

#include "irrlichttypes_bloated.h"
#include <string>

namespace porting {
/**
 * Show a text input dialog in Java
 * @param hint Hint to be shown
 * @param current Initial value to be displayed
 * @param editType Type of the text field
 * (1 = multi-line text input; 2 = single-line text input; 3 = password field)
 */
void showTextInputDialog(const std::string &hint, const std::string &current, int editType);

/**
 * Show a selection dialog in Java
 * @param optionList The list of options
 * @param listSize Size of the list
 * @param selectedIdx Selected index
 */
void showComboBoxDialog(const std::string *optionList, s32 listSize, s32 selectedIdx);

/**
 * Opens a share intent to the file at path
 *
 * @param path
 */
void shareFileAndroid(const std::string &path);

/**
 * Shows/hides notification that the game is running
 *
 * @param show whether to show/hide the notification
 */
void setPlayingNowNotification(bool show);

/*
 * Types of Android input dialog:
 * 1. Text input (single/multi-line text and password field)
 * 2. Selection input (combo box)
 */
enum AndroidDialogType { TEXT_INPUT, SELECTION_INPUT };

/*
 * WORKAROUND for not working callbacks from Java -> C++
 * Get the type of the last input dialog
 */
AndroidDialogType getLastInputDialogType();

/*
 * States of Android input dialog:
 * 1. The dialog is currently shown.
 * 2. The dialog has its input sent.
 * 3. The dialog is canceled/dismissed.
 */
enum AndroidDialogState { DIALOG_SHOWN, DIALOG_INPUTTED, DIALOG_CANCELED };

/*
 * WORKAROUND for not working callbacks from Java -> C++
 * Get the state of the input dialog
 */
AndroidDialogState getInputDialogState();

/*
 * WORKAROUND for not working callbacks from Java -> C++
 * Get the text in the current/last input dialog
 * This function clears the dialog state (set to canceled). Make sure to save
 * the dialog state before calling this function.
 */
std::string getInputDialogMessage();

/*
 * WORKAROUND for not working callbacks from Java -> C++
 * Get the selection in the current/last input dialog
 * This function clears the dialog state (set to canceled). Make sure to save
 * the dialog state before calling this function.
 */
int getInputDialogSelection();


bool hasPhysicalKeyboardAndroid();

float getDisplayDensity();
v2u32 getDisplaySize();

/**
 * Send an analytics event (AppMetrica)
 * @param eventName Name of the event
 */
void sendAnalyticsEvent(const std::string &eventName);

/**
 * Send an analytics event with parameters (AppMetrica)
 * @param eventName Name of the event
 * @param jsonParams JSON string with parameters
 */
void sendAnalyticsEventWithParams(const std::string &eventName, const std::string &jsonParams);

/**
 * Send world created analytics event
 * @param worldName Name of the world
 * @param gameId ID of the game
 * @param mapgen Mapgen type
 */
void sendWorldCreatedEvent(const std::string &worldName, const std::string &gameId, const std::string &mapgen);

// Yandex Ads functions

/**
 * Show adaptive sticky banner at the bottom of the screen
 */
void showBanner();

/**
 * Hide the banner
 */
void hideBanner();

/**
 * Check if banner is currently visible
 */
bool isBannerVisible();

/**
 * Check if interstitial ad is ready
 */
bool isInterstitialReady();

/**
 * Try to show interstitial ad
 * @return true if ad will be shown, false if no ad available
 */
bool tryShowInterstitial();

// ==================== RuStore Pay SDK (commented out - replaced by YooKassa) ====================
/*
bool rustoreHasSubscription();
void rustoreCheckSubscriptionAsync();
void rustorePurchaseSubscription();
long rustoreGetExpirationDate();
std::string rustoreGetMonthlyPrice();
std::string rustoreGetTrialPrice();
int rustoreGetTrialDays();
std::string rustoreGetPromoPrice();
int rustoreGetPromoDays();
bool rustoreIsProductInfoFetched();
bool rustoreIsOperationInProgress();
int rustoreGetLastOperationResult();
std::string rustoreGetLastError();
void rustoreClearOperationResult();
void rustoreRestorePurchases();
void rustoreFetchProductInfo();
void rustoreClearCache();
*/

// ==================== YooKassa Pay SDK ====================

/**
 * Check if user has purchased full version (from cache)
 * @return true if purchased
 */
bool yookassaHasPurchase();

/**
 * Start purchase flow with YooKassa tokenization
 * @param amount Price amount as string (e.g., "249.00")
 * @param currency Currency code (e.g., "RUB")
 * @param title Product title
 * @param description Product description
 */
void yookassaStartPurchase(const std::string &amount, const std::string &currency,
                           const std::string &title, const std::string &description);

/**
 * Get unique device identifier (similar to Unity's SystemInfo.deviceUniqueIdentifier)
 */
std::string yookassaGetDeviceUuid();

/**
 * Get formatted product price
 */
std::string yookassaGetProductPrice();

/**
 * Check if product info has been successfully fetched from backend
 */
bool yookassaIsProductInfoFetched();

/**
 * Check if async operation is in progress
 */
bool yookassaIsOperationInProgress();

/**
 * Get last operation result
 * 0 = NONE, 1 = SUCCESS, 2 = ERROR, 3 = CANCELLED, 4 = PENDING_CONFIRMATION, 5 = CONFIRMATION_NEEDED
 */
int yookassaGetLastOperationResult();

/**
 * Get last error message
 */
std::string yookassaGetLastError();

/**
 * Clear last operation result
 */
void yookassaClearOperationResult();

/**
 * Restore purchases from backend
 */
void yookassaRestorePurchases();

/**
 * Fetch product info (prices) from backend
 */
void yookassaFetchProductInfo();

/**
 * Clear purchase cache (for testing)
 */
void yookassaClearCache();

/**
 * Get pending confirmation URL (for 3DS/SBP/SberPay)
 */
std::string yookassaGetPendingConfirmationUrl();

/**
 * Get pending payment method type
 */
std::string yookassaGetPendingPaymentMethodType();

/**
 * Start confirmation process (3DS/SBP/SberPay)
 */
void yookassaStartConfirmation(const std::string &confirmationUrl, const std::string &paymentMethodType);

/**
 * Check if activity was resumed (e.g. after returning from payment)
 * and clear the flag. Returns true if activity was resumed.
 */
bool checkAndClearActivityResumedFlag();

}
