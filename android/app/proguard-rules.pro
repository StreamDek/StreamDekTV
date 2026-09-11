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

# --- CloudStream (.cs3) provider runtime ---
# Mirrors the mobile app's rules. Loaded .cs3 plugins resolve their superclasses and call into this
# API by its original names at runtime, so none of it may be renamed, shrunk or repackaged — which
# includes StreamDek's own stand-ins for missing CloudStream classes (ui.settings.Globals), since
# nothing in the app calls them and R8 would otherwise drop them.
-keep class com.lagradost.** { *; }
-keepclassmembers class com.lagradost.** { *; }
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*,KotlinMetadata
-keepclassmembers class com.lagradost.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.lagradost.**$$serializer { *; }

# A .cs3 is dex compiled ahead of time against the original names of everything in its API surface,
# so any type that appears in a signature a plugin calls has to survive R8 unrenamed and unshrunk.
# That includes the AppCompatActivity a plugin's load() is handed (see CloudStreamRuntime.pluginHost)
# and the Fragment/Lifecycle APIs plugins reach through it.
-keep class kotlin.** { *; }
-keep class kotlinx.** { *; }
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
-keep class org.jsoup.** { *; }
-keep class org.json.** { *; }
-keep class org.jetbrains.annotations.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-keep class com.google.gson.** { *; }
-keep class androidx.appcompat.app.** { *; }
-keep class androidx.fragment.app.** { *; }
-keep class androidx.lifecycle.** { *; }
-keep class androidx.preference.** { *; }
-dontwarn com.fasterxml.jackson.**
# The trimmed runtime keeps method-body references to parts of the CloudStream app StreamDek does
# not ship; those paths are unreachable from the provider API, so the dangling references are expected.
-dontwarn com.lagradost.**
-dontwarn org.schabi.newpipe.**
-dontwarn org.conscrypt.**
-dontwarn org.chromium.net.**
-dontwarn com.google.android.material.**
-dontwarn com.google.android.gms.cast.**
-dontwarn androidx.navigation.**
-dontwarn androidx.recyclerview.**
-dontwarn androidx.viewbinding.**
-dontwarn androidx.viewpager2.**
-dontwarn androidx.palette.**
-dontwarn androidx.tvprovider.**
-dontwarn androidx.work.**
-dontwarn androidx.biometric.**
-dontwarn coil3.**
-dontwarn io.ktor.**
-dontwarn kotlinx.datetime.**
-dontwarn kotlinx.io.**
-dontwarn com.fleeksoft.ksoup.**
-dontwarn dev.whyoleg.cryptography.**
-dontwarn org.mozilla.javascript.**
-dontwarn org.mozilla.universalchardet.**
