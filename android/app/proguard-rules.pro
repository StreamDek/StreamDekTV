# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in /usr/local/Cellar/android-sdk/24.3.3/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# react-native-reanimated
-keep class com.swmansion.reanimated.** { *; }
-keep class com.facebook.react.turbomodule.** { *; }

# Add any project specific keep options here:

# Gson-backed API/session models are decoded reflectively in release builds.
# Keep field names, generic signatures, and serialized-name annotations so
# TMDB/account payloads continue to deserialize after R8 obfuscation.
-keepattributes Signature,*Annotation*
-keep class com.streamdek.tv.nativeapp.data.** { *; }
# Player diagnostics stay available in debug builds; strip verbose/info logging
# from release so MPV property callbacks do not spend time formatting log lines.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
# jsoup 1.18 ships an optional RE2/J regex backend (org.jsoup.helper.Re2jRegex). It is used only
# when com.google.re2j is on the classpath, which it is not here -- jsoup falls back to
# java.util.regex. R8 still sees the references and fails the release build over classes that are
# deliberately absent, so they are declared as expected-missing rather than pulled in.
-dontwarn com.google.re2j.**
