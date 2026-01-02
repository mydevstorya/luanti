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

// ==================== RuStore Pay SDK ====================

/**
 * Check if user has active subscription (from cache)
 * @return true if subscribed
 */
bool rustoreHasSubscription();

/**
 * Start async subscription check from server
 */
void rustoreCheckSubscriptionAsync();

/**
 * Start subscription purchase flow
 */
void rustorePurchaseSubscription();

/**
 * Get subscription expiration date as Unix timestamp (seconds)
 */
long rustoreGetExpirationDate();

/**
 * Get formatted monthly price
 */
std::string rustoreGetMonthlyPrice();

/**
 * Get formatted trial price
 */
std::string rustoreGetTrialPrice();

/**
 * Get trial duration in days (parsed from ISO 8601)
 */
int rustoreGetTrialDays();

/**
 * Get formatted promo price (if available, empty string otherwise)
 */
std::string rustoreGetPromoPrice();

/**
 * Get promo duration in days (parsed from ISO 8601)
 */
int rustoreGetPromoDays();

/**
 * Check if product info has been successfully fetched from RuStore
 */
bool rustoreIsProductInfoFetched();

/**
 * Check if async operation is in progress
 */
bool rustoreIsOperationInProgress();

/**
 * Get last operation result
 * 0 = NONE, 1 = SUCCESS, 2 = ERROR, 3 = CANCELLED, 4 = NO_INTERNET, 5 = NOT_AVAILABLE
 */
int rustoreGetLastOperationResult();

/**
 * Get last error message
 */
std::string rustoreGetLastError();

/**
 * Clear last operation result
 */
void rustoreClearOperationResult();

/**
 * Restore purchases (call on app start)
 */
void rustoreRestorePurchases();

/**
 * Fetch product info (prices) from RuStore
 */
void rustoreFetchProductInfo();

/**
 * Clear subscription cache (for testing)
 */
void rustoreClearCache();

/**
 * Check if activity was resumed (e.g. after returning from RuStore payment)
 * and clear the flag. Returns true if activity was resumed.
 */
bool checkAndClearActivityResumedFlag();

}
