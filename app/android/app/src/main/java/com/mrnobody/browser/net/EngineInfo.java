package com.mrnobody.browser.net;

import android.content.Context;
import android.content.pm.PackageInfo;

import androidx.webkit.WebViewCompat;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Which web engine is actually installed, and what it can do.
 *
 * <p>This exists because we host the system WebView rather than bundling an
 * engine, which has an underrated consequence: the user can change our engine.
 * Installing a hardened SystemWebView (Cromite and similar) upgrades blocking,
 * anti-fingerprinting and DNS behaviour for us, at zero bytes of our APK.
 *
 * <p>So the browser's capabilities are a property of the device, not of our
 * build, and the privacy dashboard should say which. "Fingerprint protection:
 * on" is a claim about our code; "engine: Android System WebView 1xx,
 * multi-profile unsupported" is a fact about whether it can work. The second
 * is what a user needs to judge the first.
 *
 * <p>Reporting only. Nothing here installs, recommends or auto-switches an
 * engine.
 */
public final class EngineInfo {

    private EngineInfo() {
    }

    /** WebView package name, or "" when it cannot be determined. */
    public static String provider(Context context) {
        try {
            PackageInfo info = WebViewCompat.getCurrentWebViewPackage(context);
            return info == null ? "" : info.packageName;
        } catch (Throwable t) {
            return "";
        }
    }

    /** WebView version string, or "" when unknown. */
    public static String version(Context context) {
        try {
            PackageInfo info = WebViewCompat.getCurrentWebViewPackage(context);
            return info == null || info.versionName == null ? "" : info.versionName;
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * A short label for the dashboard.
     *
     * <p>Names the engine when it is not Google's stock one, because that is
     * the case where the difference is worth surfacing.
     */
    public static String label(Context context) {
        String pkg = provider(context);
        String ver = version(context);
        if (pkg.isEmpty()) return "Unknown engine";

        String name;
        if (pkg.contains("cromite")) name = "Cromite WebView";
        else if (pkg.contains("bromite")) name = "Bromite WebView";
        else if (pkg.contains("mulch")) name = "Mulch WebView";
        else if (pkg.startsWith("com.google.android.webview")) name = "Android System WebView";
        else if (pkg.startsWith("com.android.webview")) name = "Android System WebView";
        else if (pkg.contains("chrome")) name = "Chrome (as WebView)";
        else name = pkg;

        return ver.isEmpty() ? name : name + " " + ver;
    }

    /**
     * Everything the dashboard needs, as plain values.
     *
     * <p>Each capability is feature-detected rather than inferred from the
     * version, because that is how the calls behave at runtime.
     */
    public static Map<String, Object> describe(Context context) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("engine", label(context));
        out.put("provider", provider(context));
        out.put("version", version(context));
        out.put("multiProfile", ProfileManager.isSupported());
        out.put("proxyOverride", WebViewRouter.isSupported());
        out.put("documentStartScript", FingerprintDefence.isSupported());
        return out;
    }
}
