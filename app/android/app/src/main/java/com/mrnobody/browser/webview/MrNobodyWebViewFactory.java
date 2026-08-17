package com.mrnobody.browser.webview;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.Map;

import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.StandardMessageCodec;
import io.flutter.plugin.platform.PlatformView;
import io.flutter.plugin.platform.PlatformViewFactory;

/**
 * Builds one {@link MrNobodyWebView} per browser tab. Registered under the view
 * type {@code mrnobody/webview}; the Dart side creates it with
 * {@code {url, private}} creation params.
 */
public final class MrNobodyWebViewFactory extends PlatformViewFactory {

    public static final String VIEW_TYPE = "mrnobody/webview";

    private final BinaryMessenger messenger;

    public MrNobodyWebViewFactory(@NonNull BinaryMessenger messenger) {
        super(StandardMessageCodec.INSTANCE);
        this.messenger = messenger;
    }

    @NonNull
    @Override
    public PlatformView create(@NonNull Context context, int viewId, @Nullable Object args) {
        Map<String, Object> params = new HashMap<>();
        if (args instanceof Map) {
            //noinspection unchecked
            params.putAll((Map<String, Object>) args);
        }
        return new MrNobodyWebView(context, messenger, viewId, params);
    }
}
