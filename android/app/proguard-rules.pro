# VocoCraft ProGuard Rules

# Keep our own classes
-keep class com.VocoCraft.VocoCraft.** { *; }

# Yandex Mobile Ads SDK
-keep class com.yandex.mobile.ads.** { *; }
-dontwarn com.yandex.mobile.ads.**

# Yandex common
-keep class com.yandex.** { *; }
-dontwarn com.yandex.**

# AppMetrica SDK
-keep class io.appmetrica.analytics.** { *; }
-dontwarn io.appmetrica.analytics.**

# SDL Activity
-keep class org.libsdl.app.** { *; }

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep Parcelables
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}

# Keep enums
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep R classes
-keepclassmembers class **.R$* {
    public static <fields>;
}

# Keep BuildConfig
-keep class com.VocoCraft.VocoCraft.BuildConfig { *; }

# Suppress warnings for missing classes
-dontwarn javax.annotation.**
-dontwarn org.codehaus.mojo.animal_sniffer.**
