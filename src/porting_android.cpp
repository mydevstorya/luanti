// Luanti
// SPDX-License-Identifier: LGPL-2.1-or-later
// Copyright (C) 2014 celeron55, Perttu Ahola <celeron55@gmail.com>

#ifndef __ANDROID__
#error This file may only be compiled for android!
#endif

#include "util/numeric.h"
#include "porting.h"
#include "porting_android.h"
#include "threading/thread.h"
#include "config.h"
#include "filesys.h"
#include "log.h"
#include "settings.h"

#include <jni.h>
#define SDL_MAIN_HANDLED 1
#include <SDL.h>

#include <sstream>
#include <exception>
#include <cstdlib>
#include <atomic>

#ifdef GPROF
#include "prof.h"
#endif

extern int main(int argc, char *argv[]);

extern "C" JNIEXPORT void JNICALL
Java_com_VocoCraft_VocoCraft_GameActivity_saveSettings(JNIEnv* env, jobject /* this */) {
	if (!g_settings_path.empty())
		g_settings->updateConfigFile(g_settings_path.c_str());
}

// Native callbacks for interstitial ad events (called from Java)
extern "C" JNIEXPORT void JNICALL
Java_com_VocoCraft_VocoCraft_GameActivity_onInterstitialDismissedNative(JNIEnv* env, jobject /* this */) {
	infostream << "[YandexAds] Interstitial dismissed (native callback)" << std::endl;
	// Note: Game unpause should be handled in Lua after try_show_interstitial returns
	// This is just for logging purposes
}

extern "C" JNIEXPORT void JNICALL
Java_com_VocoCraft_VocoCraft_GameActivity_onInterstitialFailedNative(JNIEnv* env, jobject /* this */) {
	infostream << "[YandexAds] Interstitial failed (native callback)" << std::endl;
}

// Flag to indicate activity was resumed (e.g. after returning from RuStore payment)
// Declared outside namespace but not static so it can be accessed from porting namespace
std::atomic<bool> g_activity_resumed_flag{false};

// Flag to indicate purchase completed and UI should refresh
std::atomic<bool> g_purchase_complete_flag{false};

extern "C" JNIEXPORT void JNICALL
Java_com_VocoCraft_VocoCraft_GameActivity_nativeOnActivityResumed(JNIEnv* env, jobject /* this */) {
	infostream << "[Android] Activity resumed (native callback)" << std::endl;
	g_activity_resumed_flag.store(true);
}

// Called from GameActivity (via RuStorePay.kt) when purchase completes
extern "C" JNIEXPORT void JNICALL
Java_com_VocoCraft_VocoCraft_GameActivity_nativeOnPurchaseComplete(JNIEnv* env, jobject /* this */) {
	infostream << "[RuStorePay] Purchase complete (native callback) - triggering UI refresh" << std::endl;
	g_purchase_complete_flag.store(true);
	
	// Push a dummy SDL event to wake up the main loop immediately
	// This ensures the flag check happens without waiting for next frame
	SDL_Event event;
	event.type = SDL_USEREVENT;
	event.user.code = 0;
	event.user.data1 = nullptr;
	event.user.data2 = nullptr;
	SDL_PushEvent(&event);
	infostream << "[RuStorePay] SDL event pushed to wake main loop" << std::endl;
}

namespace porting {
	// used here:
	void cleanupAndroid();
	std::string getLanguageAndroid();
	bool setSystemPaths(); // used in porting.cpp
}

extern "C" int SDL_Main(int _argc, char *_argv[])
{
	Thread::setName("Main");

	char *argv[] = {strdup(PROJECT_NAME), strdup("--verbose"), nullptr};
	int retval = main(ARRLEN(argv) - 1, argv);
	free(argv[0]);
	free(argv[1]);

	porting::cleanupAndroid();
	infostream << "Shutting down." << std::endl;
	exit(retval);
}

namespace porting {
JNIEnv      *jnienv = nullptr;
jobject      activity;
jclass       activityClass;

void osSpecificInit()
{
	jnienv = (JNIEnv*)SDL_AndroidGetJNIEnv();
	activity = (jobject)SDL_AndroidGetActivity();
	activityClass = jnienv->GetObjectClass(activity);

	// Set default language
	auto lang = getLanguageAndroid();
	unsetenv("LANGUAGE");
	setenv("LANG", lang.c_str(), 1);

#ifdef GPROF
	// in the start-up code
	warningstream << "Initializing GPROF profiler" << std::endl;
	monstartup("libluanti.so");
#endif
}

void cleanupAndroid()
{
#ifdef GPROF
	warningstream << "Shutting down GPROF profiler" << std::endl;
	setenv("CPUPROFILE", (path_user + DIR_DELIM + "gmon.out").c_str(), 1);
	moncleanup();
#endif
}

static std::string readJavaString(jstring j_str)
{
	// Get string as a UTF-8 C string
	const char *c_str = jnienv->GetStringUTFChars(j_str, nullptr);
	// Save it
	std::string str(c_str);
	// And free the C string
	jnienv->ReleaseStringUTFChars(j_str, c_str);
	return str;
}

bool setSystemPaths()
{
	// Set user and share paths
	{
		jmethodID getUserDataPath = jnienv->GetMethodID(activityClass,
				"getUserDataPath", "()Ljava/lang/String;");
		FATAL_ERROR_IF(getUserDataPath==nullptr,
				"porting::initializePathsAndroid unable to find Java getUserDataPath method");
		jobject result = jnienv->CallObjectMethod(activity, getUserDataPath);
		std::string str = readJavaString((jstring) result);
		path_user = str;
		path_share = str;
	}

	// Set cache path
	{
		jmethodID getCachePath = jnienv->GetMethodID(activityClass,
				"getCachePath", "()Ljava/lang/String;");
		FATAL_ERROR_IF(getCachePath==nullptr,
				"porting::initializePathsAndroid unable to find Java getCachePath method");
		jobject result = jnienv->CallObjectMethod(activity, getCachePath);
		path_cache = readJavaString((jstring) result);
	}

	return true;
}

void showTextInputDialog(const std::string &hint, const std::string &current, int editType)
{
	jmethodID showdialog = jnienv->GetMethodID(activityClass, "showTextInputDialog",
			"(Ljava/lang/String;Ljava/lang/String;I)V");

	FATAL_ERROR_IF(showdialog == nullptr,
			"porting::showTextInputDialog unable to find Java showTextInputDialog method");

	jstring jhint         = jnienv->NewStringUTF(hint.c_str());
	jstring jcurrent      = jnienv->NewStringUTF(current.c_str());
	jint    jeditType     = editType;

	jnienv->CallVoidMethod(activity, showdialog,
			jhint, jcurrent, jeditType);
}

void showComboBoxDialog(const std::string *optionList, s32 listSize, s32 selectedIdx)
{
	jmethodID showdialog = jnienv->GetMethodID(activityClass, "showSelectionInputDialog",
			"([Ljava/lang/String;I)V");

	FATAL_ERROR_IF(showdialog == nullptr,
			"porting::showComboBoxDialog unable to find Java showSelectionInputDialog method");

	jclass       jStringClass = jnienv->FindClass("java/lang/String");
	jobjectArray jOptionList  = jnienv->NewObjectArray(listSize, jStringClass, NULL);
	jint         jselectedIdx = selectedIdx;

	for (s32 i = 0; i < listSize; i ++) {
		jnienv->SetObjectArrayElement(jOptionList, i,
				jnienv->NewStringUTF(optionList[i].c_str()));
	}

	jnienv->CallVoidMethod(activity, showdialog, jOptionList,
			jselectedIdx);
}

void openURIAndroid(const char *url)
{
	jmethodID url_open = jnienv->GetMethodID(activityClass, "openURI",
		"(Ljava/lang/String;)V");

	FATAL_ERROR_IF(url_open == nullptr,
		"porting::openURIAndroid unable to find Java openURI method");

	jstring jurl = jnienv->NewStringUTF(url);
	jnienv->CallVoidMethod(activity, url_open, jurl);
}

void shareFileAndroid(const std::string &path)
{
	jmethodID url_open = jnienv->GetMethodID(activityClass, "shareFile",
			"(Ljava/lang/String;)V");

	FATAL_ERROR_IF(url_open == nullptr,
			"porting::shareFileAndroid unable to find Java shareFile method");

	jstring jurl = jnienv->NewStringUTF(path.c_str());
	jnienv->CallVoidMethod(activity, url_open, jurl);
}

void setPlayingNowNotification(bool show)
{
	jmethodID play_notification = jnienv->GetMethodID(activityClass,
			"setPlayingNowNotification", "(Z)V");

	FATAL_ERROR_IF(play_notification == nullptr,
			"porting::setPlayingNowNotification unable to find Java setPlayingNowNotification method");

	jboolean jshow = show;
	jnienv->CallVoidMethod(activity, play_notification, jshow);
}

AndroidDialogType getLastInputDialogType()
{
	jmethodID lastdialogtype = jnienv->GetMethodID(activityClass,
			"getLastDialogType", "()I");

	FATAL_ERROR_IF(lastdialogtype == nullptr,
			"porting::getLastInputDialogType unable to find Java getLastDialogType method");

	int dialogType = jnienv->CallIntMethod(activity, lastdialogtype);
	return static_cast<AndroidDialogType>(dialogType);
}

AndroidDialogState getInputDialogState()
{
	jmethodID inputdialogstate = jnienv->GetMethodID(activityClass,
			"getInputDialogState", "()I");

	FATAL_ERROR_IF(inputdialogstate == nullptr,
			"porting::getInputDialogState unable to find Java getInputDialogState method");

	int dialogState = jnienv->CallIntMethod(activity, inputdialogstate);
	return static_cast<AndroidDialogState>(dialogState);
}

std::string getInputDialogMessage()
{
	jmethodID dialogvalue = jnienv->GetMethodID(activityClass,
			"getDialogMessage", "()Ljava/lang/String;");

	FATAL_ERROR_IF(dialogvalue == nullptr,
			"porting::getInputDialogMessage unable to find Java getDialogMessage method");

	jobject result = jnienv->CallObjectMethod(activity,
			dialogvalue);
	return readJavaString((jstring) result);
}

int getInputDialogSelection()
{
	jmethodID dialogvalue = jnienv->GetMethodID(activityClass, "getDialogSelection", "()I");

	FATAL_ERROR_IF(dialogvalue == nullptr,
			"porting::getInputDialogSelection unable to find Java getDialogSelection method");

	return jnienv->CallIntMethod(activity, dialogvalue);
}

float getDisplayDensity()
{
	static bool firstrun = true;
	static float value = 0;

	if (firstrun) {
		jmethodID getDensity = jnienv->GetMethodID(activityClass,
				"getDensity", "()F");

		FATAL_ERROR_IF(getDensity == nullptr,
			"porting::getDisplayDensity unable to find Java getDensity method");

		value = jnienv->CallFloatMethod(activity, getDensity);
		firstrun = false;
	}

	return value;
}

v2u32 getDisplaySize()
{
	static bool firstrun = true;
	static v2u32 retval;

	if (firstrun) {
		jmethodID getDisplayWidth = jnienv->GetMethodID(activityClass,
				"getDisplayWidth", "()I");

		FATAL_ERROR_IF(getDisplayWidth == nullptr,
			"porting::getDisplayWidth unable to find Java getDisplayWidth method");

		retval.X = jnienv->CallIntMethod(activity,
				getDisplayWidth);

		jmethodID getDisplayHeight = jnienv->GetMethodID(activityClass,
				"getDisplayHeight", "()I");

		FATAL_ERROR_IF(getDisplayHeight == nullptr,
			"porting::getDisplayHeight unable to find Java getDisplayHeight method");

		retval.Y = jnienv->CallIntMethod(activity,
				getDisplayHeight);

		firstrun = false;
	}

	return retval;
}

std::string getLanguageAndroid()
{
	jmethodID getLanguage = jnienv->GetMethodID(activityClass,
			"getLanguage", "()Ljava/lang/String;");

	FATAL_ERROR_IF(getLanguage == nullptr,
		"porting::getLanguageAndroid unable to find Java getLanguage method");

	jobject result = jnienv->CallObjectMethod(activity,
			getLanguage);
	return readJavaString((jstring) result);
}

bool hasPhysicalKeyboardAndroid()
{
	jmethodID hasPhysicalKeyboard = jnienv->GetMethodID(activityClass,
			"hasPhysicalKeyboard", "()Z");

	FATAL_ERROR_IF(hasPhysicalKeyboard == nullptr,
		"porting::hasPhysicalKeyboardAndroid unable to find Java hasPhysicalKeyboard method");

	jboolean result = jnienv->CallBooleanMethod(activity,
			hasPhysicalKeyboard);
	return result;
}

// Analytics functions (AppMetrica)

void sendAnalyticsEvent(const std::string &eventName)
{
	jclass analyticsClass = jnienv->FindClass("com/VocoCraft/VocoCraft/Analytics");
	if (analyticsClass == nullptr) {
		errorstream << "Analytics class not found" << std::endl;
		jnienv->ExceptionClear();
		return;
	}

	jmethodID sendEvent = jnienv->GetStaticMethodID(analyticsClass,
			"sendEvent", "(Ljava/lang/String;)V");

	if (sendEvent == nullptr) {
		errorstream << "Analytics.sendEvent method not found" << std::endl;
		jnienv->ExceptionClear();
		return;
	}

	jstring jEventName = jnienv->NewStringUTF(eventName.c_str());
	jnienv->CallStaticVoidMethod(analyticsClass, sendEvent, jEventName);
	jnienv->DeleteLocalRef(jEventName);
	jnienv->DeleteLocalRef(analyticsClass);
}

void sendAnalyticsEventWithParams(const std::string &eventName, const std::string &jsonParams)
{
	jclass analyticsClass = jnienv->FindClass("com/VocoCraft/VocoCraft/Analytics");
	if (analyticsClass == nullptr) {
		errorstream << "Analytics class not found" << std::endl;
		jnienv->ExceptionClear();
		return;
	}

	jmethodID sendEventWithParams = jnienv->GetStaticMethodID(analyticsClass,
			"sendEventWithParams", "(Ljava/lang/String;Ljava/lang/String;)V");

	if (sendEventWithParams == nullptr) {
		errorstream << "Analytics.sendEventWithParams method not found" << std::endl;
		jnienv->ExceptionClear();
		return;
	}

	jstring jEventName = jnienv->NewStringUTF(eventName.c_str());
	jstring jJsonParams = jnienv->NewStringUTF(jsonParams.c_str());
	jnienv->CallStaticVoidMethod(analyticsClass, sendEventWithParams, jEventName, jJsonParams);
	jnienv->DeleteLocalRef(jEventName);
	jnienv->DeleteLocalRef(jJsonParams);
	jnienv->DeleteLocalRef(analyticsClass);
}

void sendWorldCreatedEvent(const std::string &worldName, const std::string &gameId, const std::string &mapgen)
{
	infostream << "[Analytics JNI] sendWorldCreatedEvent: " << worldName << ", " << gameId << ", " << mapgen << std::endl;
	
	jclass analyticsClass = jnienv->FindClass("com/VocoCraft/VocoCraft/Analytics");
	if (analyticsClass == nullptr) {
		errorstream << "[Analytics JNI] Analytics class not found" << std::endl;
		jnienv->ExceptionClear();
		return;
	}
	infostream << "[Analytics JNI] Found Analytics class" << std::endl;

	jmethodID sendWorldCreated = jnienv->GetStaticMethodID(analyticsClass,
			"sendWorldCreatedEvent", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");

	if (sendWorldCreated == nullptr) {
		errorstream << "[Analytics JNI] Analytics.sendWorldCreatedEvent method not found" << std::endl;
		jnienv->ExceptionClear();
		return;
	}
	infostream << "[Analytics JNI] Found sendWorldCreatedEvent method" << std::endl;

	jstring jWorldName = jnienv->NewStringUTF(worldName.c_str());
	jstring jGameId = jnienv->NewStringUTF(gameId.c_str());
	jstring jMapgen = jnienv->NewStringUTF(mapgen.c_str());
	infostream << "[Analytics JNI] Calling Java method..." << std::endl;
	jnienv->CallStaticVoidMethod(analyticsClass, sendWorldCreated, jWorldName, jGameId, jMapgen);
	infostream << "[Analytics JNI] Java method called successfully" << std::endl;
	jnienv->DeleteLocalRef(jWorldName);
	jnienv->DeleteLocalRef(jGameId);
	jnienv->DeleteLocalRef(jMapgen);
	jnienv->DeleteLocalRef(analyticsClass);
}

// Yandex Ads functions

void showBanner()
{
	if (jnienv == nullptr || activity == nullptr || activityClass == nullptr) {
		errorstream << "[YandexAds JNI] showBanner() - JNI not initialized" << std::endl;
		return;
	}
	
	infostream << "[YandexAds JNI] showBanner()" << std::endl;
	
	jmethodID showBannerMethod = jnienv->GetMethodID(activityClass, "showBanner", "()V");
	if (jnienv->ExceptionCheck()) {
		jnienv->ExceptionClear();
		errorstream << "[YandexAds JNI] Exception getting showBanner method" << std::endl;
		return;
	}
	if (showBannerMethod == nullptr) {
		errorstream << "[YandexAds JNI] showBanner method not found" << std::endl;
		return;
	}
	
	jnienv->CallVoidMethod(activity, showBannerMethod);
	if (jnienv->ExceptionCheck()) {
		jnienv->ExceptionClear();
		errorstream << "[YandexAds JNI] Exception in showBanner" << std::endl;
		return;
	}
	infostream << "[YandexAds JNI] showBanner called" << std::endl;
}

void hideBanner()
{
	if (jnienv == nullptr || activity == nullptr || activityClass == nullptr) {
		// Silent return on shutdown - not an error
		return;
	}
	
	infostream << "[YandexAds JNI] hideBanner()" << std::endl;
	
	jmethodID hideBannerMethod = jnienv->GetMethodID(activityClass, "hideBanner", "()V");
	if (jnienv->ExceptionCheck()) {
		jnienv->ExceptionClear();
		return;
	}
	if (hideBannerMethod == nullptr) {
		errorstream << "[YandexAds JNI] hideBanner method not found" << std::endl;
		return;
	}
	
	jnienv->CallVoidMethod(activity, hideBannerMethod);
	if (jnienv->ExceptionCheck()) {
		jnienv->ExceptionClear();
		errorstream << "[YandexAds JNI] Exception in hideBanner" << std::endl;
		return;
	}
	infostream << "[YandexAds JNI] hideBanner called" << std::endl;
}

bool isBannerVisible()
{
	if (jnienv == nullptr || activity == nullptr || activityClass == nullptr) {
		return false;
	}
	
	jmethodID isBannerVisibleMethod = jnienv->GetMethodID(activityClass, "isBannerVisible", "()Z");
	if (jnienv->ExceptionCheck()) {
		jnienv->ExceptionClear();
		return false;
	}
	if (isBannerVisibleMethod == nullptr) {
		return false;
	}
	
	bool result = jnienv->CallBooleanMethod(activity, isBannerVisibleMethod);
	if (jnienv->ExceptionCheck()) {
		jnienv->ExceptionClear();
		return false;
	}
	return result;
}

bool isInterstitialReady()
{
	if (jnienv == nullptr || activity == nullptr || activityClass == nullptr) {
		return false;
	}
	
	jmethodID isReadyMethod = jnienv->GetMethodID(activityClass, "isInterstitialReady", "()Z");
	if (jnienv->ExceptionCheck()) {
		jnienv->ExceptionClear();
		return false;
	}
	if (isReadyMethod == nullptr) {
		return false;
	}
	
	bool result = jnienv->CallBooleanMethod(activity, isReadyMethod);
	if (jnienv->ExceptionCheck()) {
		jnienv->ExceptionClear();
		return false;
	}
	return result;
}

bool tryShowInterstitial()
{
	if (jnienv == nullptr || activity == nullptr || activityClass == nullptr) {
		return false;
	}
	
	infostream << "[YandexAds JNI] tryShowInterstitial()" << std::endl;
	
	jmethodID tryShowMethod = jnienv->GetMethodID(activityClass, "tryShowInterstitial", "()Z");
	if (jnienv->ExceptionCheck()) {
		jnienv->ExceptionClear();
		return false;
	}
	if (tryShowMethod == nullptr) {
		errorstream << "[YandexAds JNI] tryShowInterstitial method not found" << std::endl;
		return false;
	}
	
	bool result = jnienv->CallBooleanMethod(activity, tryShowMethod);
	if (jnienv->ExceptionCheck()) {
		jnienv->ExceptionClear();
		return false;
	}
	infostream << "[YandexAds JNI] tryShowInterstitial returned: " << result << std::endl;
	return result;
}

// ==================== RuStore Pay SDK ====================

static jclass getRuStorePayClass()
{
	static jclass cls = nullptr;
	if (cls == nullptr) {
		jclass localCls = jnienv->FindClass("com/VocoCraft/VocoCraft/RuStorePay");
		if (localCls != nullptr) {
			cls = (jclass)jnienv->NewGlobalRef(localCls);
			jnienv->DeleteLocalRef(localCls);
		}
	}
	return cls;
}

bool rustoreHasSubscription()
{
	if (jnienv == nullptr) return false;
	
	jclass cls = getRuStorePayClass();
	if (cls == nullptr) {
		errorstream << "[RuStorePay JNI] RuStorePay class not found" << std::endl;
		if (jnienv->ExceptionCheck()) jnienv->ExceptionClear();
		return false;
	}
	
	jmethodID method = jnienv->GetStaticMethodID(cls, "hasSubscription", "()Z");
	if (method == nullptr) {
		errorstream << "[RuStorePay JNI] hasSubscription method not found" << std::endl;
		if (jnienv->ExceptionCheck()) jnienv->ExceptionClear();
		return false;
	}
	
	bool result = jnienv->CallStaticBooleanMethod(cls, method);
	if (jnienv->ExceptionCheck()) {
		jnienv->ExceptionClear();
		return false;
	}
	
	return result;
}

void rustoreCheckSubscriptionAsync()
{
	if (jnienv == nullptr) return;
	
	jclass cls = getRuStorePayClass();
	if (cls == nullptr) {
		if (jnienv->ExceptionCheck()) jnienv->ExceptionClear();
		return;
	}
	
	jmethodID method = jnienv->GetStaticMethodID(cls, "checkSubscriptionAsync", "()V");
	if (method == nullptr) {
		if (jnienv->ExceptionCheck()) jnienv->ExceptionClear();
		return;
	}
	
	jnienv->CallStaticVoidMethod(cls, method);
	if (jnienv->ExceptionCheck()) jnienv->ExceptionClear();
}

void rustorePurchaseSubscription()
{
	if (jnienv == nullptr) return;
	
	infostream << "[RuStorePay JNI] rustorePurchaseSubscription()" << std::endl;
	
	jclass cls = getRuStorePayClass();
	if (cls == nullptr) {
		errorstream << "[RuStorePay JNI] RuStorePay class not found" << std::endl;
		if (jnienv->ExceptionCheck()) jnienv->ExceptionClear();
		return;
	}
	
	jmethodID method = jnienv->GetStaticMethodID(cls, "purchaseSubscription", "()V");
	if (method == nullptr) {
		errorstream << "[RuStorePay JNI] purchaseSubscription method not found" << std::endl;
		if (jnienv->ExceptionCheck()) jnienv->ExceptionClear();
		return;
	}
	
	jnienv->CallStaticVoidMethod(cls, method);
	if (jnienv->ExceptionCheck()) {
		jnienv->ExceptionClear();
		errorstream << "[RuStorePay JNI] Exception in purchaseSubscription" << std::endl;
	}
	
	infostream << "[RuStorePay JNI] purchaseSubscription called" << std::endl;
}

long rustoreGetExpirationDate()
{
	if (jnienv == nullptr) return 0;
	
	jclass cls = getRuStorePayClass();
	if (cls == nullptr) {
		if (jnienv->ExceptionCheck()) jnienv->ExceptionClear();
		return 0;
	}
	
	jmethodID method = jnienv->GetStaticMethodID(cls, "getExpirationDate", "()J");
	if (method == nullptr) {
		if (jnienv->ExceptionCheck()) jnienv->ExceptionClear();
		return 0;
	}
	
	jlong result = jnienv->CallStaticLongMethod(cls, method);
	if (jnienv->ExceptionCheck()) {
		jnienv->ExceptionClear();
		return 0;
	}
	
	return (long)result;
}

std::string rustoreGetMonthlyPrice()
{
	if (jnienv == nullptr) return "199 ₽";
	
	jclass cls = getRuStorePayClass();
	if (cls == nullptr) {
		if (jnienv->ExceptionCheck()) jnienv->ExceptionClear();
		return "199 ₽";
	}
	
	jmethodID method = jnienv->GetStaticMethodID(cls, "getMonthlyPrice", "()Ljava/lang/String;");
	if (method == nullptr) {
		if (jnienv->ExceptionCheck()) jnienv->ExceptionClear();
		return "199 ₽";
	}
	
	jstring jstr = (jstring)jnienv->CallStaticObjectMethod(cls, method);
	if (jnienv->ExceptionCheck()) {
		jnienv->ExceptionClear();
		return "199 ₽";
	}
	
	if (jstr == nullptr) return "199 ₽";
	
	const char *cstr = jnienv->GetStringUTFChars(jstr, nullptr);
	std::string result(cstr);
	jnienv->ReleaseStringUTFChars(jstr, cstr);
	jnienv->DeleteLocalRef(jstr);
	
	return result;
}

std::string rustoreGetTrialPrice()
{
	if (jnienv == nullptr) return "Бесплатно";
	
	jclass cls = getRuStorePayClass();
	if (cls == nullptr) {
		if (jnienv->ExceptionCheck()) jnienv->ExceptionClear();
		return "Бесплатно";
	}
	
	jmethodID method = jnienv->GetStaticMethodID(cls, "getTrialPrice", "()Ljava/lang/String;");
	if (method == nullptr) {
		if (jnienv->ExceptionCheck()) jnienv->ExceptionClear();
		return "Бесплатно";
	}
	
	jstring jstr = (jstring)jnienv->CallStaticObjectMethod(cls, method);
	if (jnienv->ExceptionCheck()) {
		jnienv->ExceptionClear();
		return "Бесплатно";
	}
	
	if (jstr == nullptr) return "Бесплатно";
	
	const char *cstr = jnienv->GetStringUTFChars(jstr, nullptr);
	std::string result(cstr);
	jnienv->ReleaseStringUTFChars(jstr, cstr);
	jnienv->DeleteLocalRef(jstr);
	
	return result;
}

int rustoreGetTrialDays()
{
	if (jnienv == nullptr) return 0;
	
	jclass cls = getRuStorePayClass();
	if (cls == nullptr) {
		if (jnienv->ExceptionCheck()) jnienv->ExceptionClear();
		return 0;
	}
	
	jmethodID method = jnienv->GetStaticMethodID(cls, "getTrialDays", "()I");
	if (method == nullptr) {
		if (jnienv->ExceptionCheck()) jnienv->ExceptionClear();
		return 0;
	}
	
	int result = jnienv->CallStaticIntMethod(cls, method);
	if (jnienv->ExceptionCheck()) {
		jnienv->ExceptionClear();
		return 0;
	}
	
	return result;
}

std::string rustoreGetPromoPrice()
{
	if (jnienv == nullptr) return "";
	
	jclass cls = getRuStorePayClass();
	if (cls == nullptr) {
		if (jnienv->ExceptionCheck()) jnienv->ExceptionClear();
		return "";
	}
	
	jmethodID method = jnienv->GetStaticMethodID(cls, "getPromoPrice", "()Ljava/lang/String;");
	if (method == nullptr) {
		if (jnienv->ExceptionCheck()) jnienv->ExceptionClear();
		return "";
	}
	
	jstring jstr = (jstring) jnienv->CallStaticObjectMethod(cls, method);
	if (jnienv->ExceptionCheck()) {
		jnienv->ExceptionClear();
		return "";
	}
	
	if (jstr == nullptr) return "";
	
	const char *cstr = jnienv->GetStringUTFChars(jstr, nullptr);
	std::string result(cstr);
	jnienv->ReleaseStringUTFChars(jstr, cstr);
	jnienv->DeleteLocalRef(jstr);
	
	return result;
}

int rustoreGetPromoDays()
{
	if (jnienv == nullptr) return 0;
	
	jclass cls = getRuStorePayClass();
	if (cls == nullptr) {
		if (jnienv->ExceptionCheck()) jnienv->ExceptionClear();
		return 0;
	}
	
	jmethodID method = jnienv->GetStaticMethodID(cls, "getPromoDays", "()I");
	if (method == nullptr) {
		if (jnienv->ExceptionCheck()) jnienv->ExceptionClear();
		return 0;
	}
	
	int result = jnienv->CallStaticIntMethod(cls, method);
	if (jnienv->ExceptionCheck()) {
		jnienv->ExceptionClear();
		return 0;
	}
	
	return result;
}

bool rustoreIsProductInfoFetched()
{
	if (jnienv == nullptr) return false;
	
	jclass cls = getRuStorePayClass();
	if (cls == nullptr) {
		if (jnienv->ExceptionCheck()) jnienv->ExceptionClear();
		return false;
	}
	
	jmethodID method = jnienv->GetStaticMethodID(cls, "isProductInfoFetched", "()Z");
	if (method == nullptr) {
		if (jnienv->ExceptionCheck()) jnienv->ExceptionClear();
		return false;
	}
	
	bool result = jnienv->CallStaticBooleanMethod(cls, method);
	if (jnienv->ExceptionCheck()) {
		jnienv->ExceptionClear();
		return false;
	}
	
	return result;
}

bool rustoreIsOperationInProgress()
{
	if (jnienv == nullptr) return false;
	
	jclass cls = getRuStorePayClass();
	if (cls == nullptr) {
		if (jnienv->ExceptionCheck()) jnienv->ExceptionClear();
		return false;
	}
	
	jmethodID method = jnienv->GetStaticMethodID(cls, "isOperationInProgress", "()Z");
	if (method == nullptr) {
		if (jnienv->ExceptionCheck()) jnienv->ExceptionClear();
		return false;
	}
	
	bool result = jnienv->CallStaticBooleanMethod(cls, method);
	if (jnienv->ExceptionCheck()) {
		jnienv->ExceptionClear();
		return false;
	}
	
	return result;
}

int rustoreGetLastOperationResult()
{
	if (jnienv == nullptr) return 0;
	
	jclass cls = getRuStorePayClass();
	if (cls == nullptr) {
		if (jnienv->ExceptionCheck()) jnienv->ExceptionClear();
		return 0;
	}
	
	jmethodID method = jnienv->GetStaticMethodID(cls, "getLastOperationResult", "()I");
	if (method == nullptr) {
		if (jnienv->ExceptionCheck()) jnienv->ExceptionClear();
		return 0;
	}
	
	int result = jnienv->CallStaticIntMethod(cls, method);
	if (jnienv->ExceptionCheck()) {
		jnienv->ExceptionClear();
		return 0;
	}
	
	return result;
}

std::string rustoreGetLastError()
{
	if (jnienv == nullptr) return "";
	
	jclass cls = getRuStorePayClass();
	if (cls == nullptr) {
		if (jnienv->ExceptionCheck()) jnienv->ExceptionClear();
		return "";
	}
	
	jmethodID method = jnienv->GetStaticMethodID(cls, "getLastError", "()Ljava/lang/String;");
	if (method == nullptr) {
		if (jnienv->ExceptionCheck()) jnienv->ExceptionClear();
		return "";
	}
	
	jstring jstr = (jstring)jnienv->CallStaticObjectMethod(cls, method);
	if (jnienv->ExceptionCheck()) {
		jnienv->ExceptionClear();
		return "";
	}
	
	if (jstr == nullptr) return "";
	
	const char *cstr = jnienv->GetStringUTFChars(jstr, nullptr);
	std::string result(cstr);
	jnienv->ReleaseStringUTFChars(jstr, cstr);
	jnienv->DeleteLocalRef(jstr);
	
	return result;
}

void rustoreClearOperationResult()
{
	if (jnienv == nullptr) return;
	
	jclass cls = getRuStorePayClass();
	if (cls == nullptr) {
		if (jnienv->ExceptionCheck()) jnienv->ExceptionClear();
		return;
	}
	
	jmethodID method = jnienv->GetStaticMethodID(cls, "clearOperationResult", "()V");
	if (method == nullptr) {
		if (jnienv->ExceptionCheck()) jnienv->ExceptionClear();
		return;
	}
	
	jnienv->CallStaticVoidMethod(cls, method);
	if (jnienv->ExceptionCheck()) jnienv->ExceptionClear();
}

void rustoreRestorePurchases()
{
	if (jnienv == nullptr) return;
	
	infostream << "[RuStorePay JNI] rustoreRestorePurchases()" << std::endl;
	
	jclass cls = getRuStorePayClass();
	if (cls == nullptr) {
		errorstream << "[RuStorePay JNI] RuStorePay class not found" << std::endl;
		if (jnienv->ExceptionCheck()) jnienv->ExceptionClear();
		return;
	}
	
	jmethodID method = jnienv->GetStaticMethodID(cls, "restorePurchases", "()V");
	if (method == nullptr) {
		errorstream << "[RuStorePay JNI] restorePurchases method not found" << std::endl;
		if (jnienv->ExceptionCheck()) jnienv->ExceptionClear();
		return;
	}
	
	jnienv->CallStaticVoidMethod(cls, method);
	if (jnienv->ExceptionCheck()) jnienv->ExceptionClear();
	
	infostream << "[RuStorePay JNI] restorePurchases called" << std::endl;
}

void rustoreFetchProductInfo()
{
	if (jnienv == nullptr) return;
	
	jclass cls = getRuStorePayClass();
	if (cls == nullptr) {
		if (jnienv->ExceptionCheck()) jnienv->ExceptionClear();
		return;
	}
	
	jmethodID method = jnienv->GetStaticMethodID(cls, "fetchProductInfo", "()V");
	if (method == nullptr) {
		if (jnienv->ExceptionCheck()) jnienv->ExceptionClear();
		return;
	}
	
	jnienv->CallStaticVoidMethod(cls, method);
	if (jnienv->ExceptionCheck()) jnienv->ExceptionClear();
}

void rustoreClearCache()
{
	if (jnienv == nullptr) return;
	
	jclass cls = getRuStorePayClass();
	if (cls == nullptr) {
		if (jnienv->ExceptionCheck()) jnienv->ExceptionClear();
		return;
	}
	
	jmethodID method = jnienv->GetStaticMethodID(cls, "clearCache", "()V");
	if (method == nullptr) {
		if (jnienv->ExceptionCheck()) jnienv->ExceptionClear();
		return;
	}
	
	jnienv->CallStaticVoidMethod(cls, method);
	if (jnienv->ExceptionCheck()) jnienv->ExceptionClear();
}

bool checkAndClearActivityResumedFlag()
{
	// Check both flags - activity resumed OR purchase complete
	// Either one should trigger UI refresh
	bool resumed = ::g_activity_resumed_flag.exchange(false);
	bool purchase = ::g_purchase_complete_flag.exchange(false);
	if (resumed || purchase) {
		infostream << "[Android] UI refresh triggered: resumed=" << resumed << ", purchase=" << purchase << std::endl;
	}
	return resumed || purchase;
}

}
