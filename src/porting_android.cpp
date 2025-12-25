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

#ifdef GPROF
#include "prof.h"
#endif

extern int main(int argc, char *argv[]);

extern "C" JNIEXPORT void JNICALL
Java_net_minetest_minetest_GameActivity_saveSettings(JNIEnv* env, jobject /* this */) {
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
	if (showBannerMethod == nullptr) {
		errorstream << "[YandexAds JNI] showBanner method not found" << std::endl;
		jnienv->ExceptionClear();
		return;
	}
	
	jnienv->CallVoidMethod(activity, showBannerMethod);
	infostream << "[YandexAds JNI] showBanner called" << std::endl;
}

void hideBanner()
{
	if (jnienv == nullptr || activity == nullptr || activityClass == nullptr) {
		errorstream << "[YandexAds JNI] hideBanner() - JNI not initialized" << std::endl;
		return;
	}
	
	infostream << "[YandexAds JNI] hideBanner()" << std::endl;
	
	jmethodID hideBannerMethod = jnienv->GetMethodID(activityClass, "hideBanner", "()V");
	if (hideBannerMethod == nullptr) {
		errorstream << "[YandexAds JNI] hideBanner method not found" << std::endl;
		jnienv->ExceptionClear();
		return;
	}
	
	jnienv->CallVoidMethod(activity, hideBannerMethod);
	infostream << "[YandexAds JNI] hideBanner called" << std::endl;
}

bool isBannerVisible()
{
	if (jnienv == nullptr || activity == nullptr || activityClass == nullptr) {
		return false;
	}
	
	jmethodID isBannerVisibleMethod = jnienv->GetMethodID(activityClass, "isBannerVisible", "()Z");
	if (isBannerVisibleMethod == nullptr) {
		jnienv->ExceptionClear();
		return false;
	}
	
	return jnienv->CallBooleanMethod(activity, isBannerVisibleMethod);
}

bool isInterstitialReady()
{
	if (jnienv == nullptr || activity == nullptr || activityClass == nullptr) {
		return false;
	}
	
	jmethodID isReadyMethod = jnienv->GetMethodID(activityClass, "isInterstitialReady", "()Z");
	if (isReadyMethod == nullptr) {
		jnienv->ExceptionClear();
		return false;
	}
	
	return jnienv->CallBooleanMethod(activity, isReadyMethod);
}

bool tryShowInterstitial()
{
	if (jnienv == nullptr || activity == nullptr || activityClass == nullptr) {
		errorstream << "[YandexAds JNI] tryShowInterstitial() - JNI not initialized" << std::endl;
		return false;
	}
	
	infostream << "[YandexAds JNI] tryShowInterstitial()" << std::endl;
	
	jmethodID tryShowMethod = jnienv->GetMethodID(activityClass, "tryShowInterstitial", "()Z");
	if (tryShowMethod == nullptr) {
		errorstream << "[YandexAds JNI] tryShowInterstitial method not found" << std::endl;
		jnienv->ExceptionClear();
		return false;
	}
	
	bool result = jnienv->CallBooleanMethod(activity, tryShowMethod);
	infostream << "[YandexAds JNI] tryShowInterstitial returned: " << result << std::endl;
	return result;
}

}
