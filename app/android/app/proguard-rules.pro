# Rules for code that R8 cannot see the callers of.
#
# tor-android: libtor.so resolves TorService's fields and methods through
# JNI *at runtime* — GetFieldID("torConfiguration", "J") and friends. R8
# sees private fields with no Java references and strips or renames them,
# and the release APK then dies with NoSuchFieldError the moment the tor
# thread starts (device-observed 2026-08-21; debug builds don't minify,
# which is why CI emulators never saw it). The 0.4.7.14 AAR ships no
# consumer rules, so the app must carry them.
-keep class org.torproject.jni.** { *; }
-keepclassmembers class org.torproject.jni.** { *; }

# jtorctl is driven by TorService over the control socket; its event and
# command classes are looked up by name.
-keep class net.freehaven.tor.control.** { *; }
-keepclassmembers class net.freehaven.tor.control.** { *; }

# Flutter wrapper
-keep class io.flutter.app.** { *; }
-keep class io.flutter.plugin.** { *; }
-keep class io.flutter.util.** { *; }
-keep class io.flutter.view.** { *; }
-keep class io.flutter.** { *; }
-keep class io.flutter.plugins.** { *; }

# Mr Nobody core — keep all browser and agent packages (reflection used for TorService)
-keep class com.mrnobody.** { *; }
-keepclassmembers class com.mrnobody.** { *; }

# WorkManager
-keep class androidx.work.** { *; }
-keep class com.google.common.util.concurrent.** { *; }

# WebView
-keep class androidx.webkit.** { *; }

# Gson / JSON (if used)
-keep class org.json.** { *; }

# Remove logging in release
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}

# P2 size: keep only required, allow obfuscation for rest
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
-dontwarn **
