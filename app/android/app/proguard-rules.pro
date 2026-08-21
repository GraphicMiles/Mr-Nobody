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

# jtorctl is driven by TorService over the control socket; its event and
# command classes are looked up by name.
-keep class net.freehaven.tor.control.** { *; }
