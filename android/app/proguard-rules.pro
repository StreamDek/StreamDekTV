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
