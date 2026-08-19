package com.mrnobody.browser;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;

import androidx.annotation.Nullable;

import com.mrnobody.debug.ErrorLog;

/**
 * Hands a visible-tab file input to Android's picker.
 *
 * <p>A headless WebView cannot fill {@code <input type="file">}: there is no
 * window, so {@code onShowFileChooser} never fires. The honest path is this
 * one — the user opens the page in a real tab, taps the input, and picks a
 * file. The agent parks and points them here rather than pretending the
 * input was filled.
 */
public final class FileChooserHost {

    public static final int REQUEST = 4302;

    private static volatile Activity host;
    private static ValueCallback<Uri[]> pending;

    private FileChooserHost() {
    }

    public static void setHost(Activity activity) {
        host = activity;
    }

    public static void clearHost(Activity activity) {
        if (host == activity) host = null;
    }

    /**
     * Open the system picker. Returns false when nobody is in the
     * foreground; the caller must then cancel the WebView callback.
     */
    public static boolean prompt(ValueCallback<Uri[]> callback,
                                 @Nullable WebChromeClient.FileChooserParams params) {
        Activity activity = host;
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            if (callback != null) callback.onReceiveValue(null);
            return false;
        }
        if (pending != null) pending.onReceiveValue(null);
        pending = callback;
        try {
            Intent intent = params != null ? params.createIntent() : null;
            if (intent == null || intent.getAction() == null) {
                intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
            }
            activity.startActivityForResult(intent, REQUEST);
            return true;
        } catch (Exception e) {
            ErrorLog.record("file chooser failed: " + e);
            pending = null;
            if (callback != null) callback.onReceiveValue(null);
            return false;
        }
    }

    /** @return true when this result belonged to a file chooser. */
    public static boolean deliver(int requestCode, int resultCode, @Nullable Intent data) {
        if (requestCode != REQUEST) return false;
        ValueCallback<Uri[]> callback = pending;
        pending = null;
        if (callback == null) return true;
        Uri[] uris = null;
        if (resultCode == Activity.RESULT_OK && data != null) {
            if (data.getClipData() != null) {
                int n = data.getClipData().getItemCount();
                uris = new Uri[n];
                for (int i = 0; i < n; i++) {
                    uris[i] = data.getClipData().getItemAt(i).getUri();
                }
            } else if (data.getData() != null) {
                uris = new Uri[]{data.getData()};
            }
        }
        callback.onReceiveValue(uris);
        return true;
    }
}
