package com.mrnobody.browser.webview;

import android.annotation.SuppressLint;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.mrnobody.browser.MrNobodyApp;
import com.mrnobody.browser.blocking.FilterEngine;
import com.mrnobody.browser.blocking.TrackingParams;
import com.mrnobody.browser.download.DownloadDestination;
import com.mrnobody.browser.download.DownloadNaming;

import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.Map;

import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.platform.PlatformView;

/**
 * The visible browser surface, hosted as a Flutter platform view.
 *
 * <p>This exists because the privacy promise cannot be kept from Dart. Blocking
 * an ad or a tracker means refusing a sub-resource request <em>before</em> it
 * leaves the device, and the only place that decision can be made is
 * {@link WebViewClient#shouldInterceptRequest}, which no Flutter WebView plugin
 * exposes. Hosting the WebView ourselves puts the filter engine back on the
 * request path, in-process — no channel hop per request — and brings the
 * download listener, the cookie policy and the JavaScript switch with it.
 *
 * <p>Flutter owns the chrome around this view; this class owns the page.
 */
class MrNobodyWebView implements PlatformView, MethodChannel.MethodCallHandler {

    /** Blocked requests are answered with an empty 200, not an error page. */
    private static final String BLOCKED_MIME = "text/plain";

    private final Context context;
    private final WebView webView;
    private final MethodChannel channel;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final boolean isPrivate;

    private int lastReportedScrollY;
    private boolean destroyed;

    @SuppressLint("SetJavaScriptEnabled")
    MrNobodyWebView(@NonNull Context context, @NonNull BinaryMessenger messenger, int viewId,
                    @NonNull Map<String, Object> params) {
        this.context = context;
        this.isPrivate = Boolean.TRUE.equals(params.get("private"));
        this.webView = new WebView(context);
        this.channel = new MethodChannel(messenger, "mrnobody/webview_" + viewId);
        this.channel.setMethodCallHandler(this);

        applySettings();
        applyCookiePolicy();

        webView.setBackgroundColor(0xFF000000);
        webView.setWebViewClient(client);
        webView.setWebChromeClient(chromeClient);
        webView.setDownloadListener(this::onDownloadRequested);
        webView.setOnScrollChangeListener((v, x, y, oldX, oldY) -> reportScroll(y));

        Object url = params.get("url");
        if (url instanceof String && !((String) url).isEmpty()) {
            webView.loadUrl((String) url);
        }
    }

    /** User settings that the engine must honour on every page. */
    private void applySettings() {
        WebSettings s = webView.getSettings();
        boolean js = MrNobodyApp.settings().isJsEnabled();
        s.setJavaScriptEnabled(js);
        s.setDomStorageEnabled(js);
        s.setDatabaseEnabled(!isPrivate);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        // A page must not be able to read the device's files or our own assets.
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setSafeBrowsingEnabled(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        if (isPrivate) {
            s.setCacheMode(WebSettings.LOAD_NO_CACHE);
            s.setSaveFormData(false);
        }
    }

    /**
     * Third-party cookies are refused — which is what the privacy dashboard
     * claims, so it has to be enforced here rather than asserted there.
     */
    private void applyCookiePolicy() {
        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true); // first-party cookies still work
        cookies.setAcceptThirdPartyCookies(webView, false);
    }

    // ------------------------------------------------------------ page hooks

    private final WebViewClient client = new WebViewClient() {

        @Nullable
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            // Runs off the UI thread, once per request. Keep it cheap.
            if (request.isForMainFrame()) {
                // A new document: the per-page counters start again.
                MrNobodyApp.filters().resetPageCounters();
                return null;
            }
            String url = request.getUrl() == null ? null : request.getUrl().toString();
            FilterEngine.Category category = MrNobodyApp.filters().shouldBlock(url);
            if (category == FilterEngine.Category.NONE) return null;

            MrNobodyApp.report().increment(
                    category == FilterEngine.Category.AD ? "ads" : "trackers");
            send("onBlocked", counters(category));
            return new WebResourceResponse(BLOCKED_MIME, "utf-8",
                    new ByteArrayInputStream(new byte[0]));
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            if (uri == null) return false;
            String url = uri.toString();
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();

            // Anything that is not the web belongs to another app (tel:, mailto:,
            // intent:). Hand it over instead of failing to render it.
            if (!scheme.equals("http") && !scheme.equals("https")) {
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, uri);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(intent);
                } catch (ActivityNotFoundException | SecurityException ignored) {
                    // No app handles it — do nothing rather than crash.
                }
                return true;
            }

            // Strip known tracking parameters from top-level navigations.
            if (request.isForMainFrame() && MrNobodyApp.settings().isParamStrippingEnabled()) {
                String stripped = TrackingParams.strip(url);
                if (stripped != null && !stripped.equals(url)) {
                    view.loadUrl(stripped);
                    return true;
                }
            }
            return false;
        }

        @Override
        public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
            Map<String, Object> data = new HashMap<>();
            data.put("url", url);
            data.put("loading", true);
            send("onNavigation", data);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            if (!isPrivate) {
                // History is off by default and never recorded in a private tab.
                MrNobodyApp.history().add(url, view.getTitle());
            }
            Map<String, Object> data = new HashMap<>();
            data.put("url", url);
            data.put("title", view.getTitle() == null ? "" : view.getTitle());
            data.put("loading", false);
            data.put("canGoBack", view.canGoBack());
            data.put("canGoForward", view.canGoForward());
            send("onNavigation", data);
            send("onBlocked", counters(null));
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            // A failed image or script is not a failed page.
            if (!request.isForMainFrame()) return;
            Map<String, Object> data = new HashMap<>();
            data.put("error", error.getDescription() == null ? "Network error" : error.getDescription().toString());
            data.put("code", error.getErrorCode());
            send("onError", data);
        }
    };

    private final WebChromeClient chromeClient = new WebChromeClient() {
        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            Map<String, Object> data = new HashMap<>();
            data.put("progress", newProgress);
            send("onProgress", data);
        }

        @Override
        public void onReceivedTitle(WebView view, String title) {
            Map<String, Object> data = new HashMap<>();
            data.put("title", title == null ? "" : title);
            send("onTitle", data);
        }
    };

    // ------------------------------------------------------------- downloads

    /**
     * Hand a download to Android's own DownloadManager — the system handles the
     * notification, the retry and the "open with" that the Downloads screen
     * then reads back.
     */
    private void onDownloadRequested(String url, String userAgent, String contentDisposition,
                                     String mimeType, long contentLength) {
        Map<String, Object> data = new HashMap<>();
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
            // blob:/data: downloads need a page-side reader; refuse clearly
            // instead of appearing to start and silently doing nothing.
            data.put("error", "This download type isn't supported yet");
            send("onDownload", data);
            return;
        }
        // Not URLUtil.guessFileName: it answers "downloadfile.bin" whenever the
        // server says octet-stream, which is how an .mkv arrives unopenable.
        String name = DownloadNaming.fileName(url, contentDisposition, mimeType);
        try {
            DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm == null) {
                data.put("error", "Downloads are unavailable on this device");
                send("onDownload", data);
                return;
            }
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            request.setTitle(name);
            request.setDescription("Downloaded by Mr Nobody");
            if (mimeType != null && !mimeType.trim().isEmpty()) request.setMimeType(mimeType);
            request.addRequestHeader("User-Agent", userAgent);
            request.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);

            // With a folder chosen, stage in app storage and move the finished
            // file there: DownloadManager cannot write into a SAF tree, and a
            // half-written file should never appear in the user's folder.
            DownloadDestination destination = new DownloadDestination(context);
            java.io.File staged = null;
            if (destination.isCustom()) {
                staged = DownloadDestination.stagingFile(context, name);
                request.setDestinationUri(Uri.fromFile(staged));
            } else {
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
            }
            long id = dm.enqueue(request);
            if (staged != null) {
                destination.rememberPending(id, staged.getAbsolutePath(), name, mimeType);
            }
            data.put("name", name);
            data.put("id", id);
            data.put("folder", destination.label());
        } catch (Exception e) {
            data.put("error", "Could not start the download");
        }
        send("onDownload", data);
    }

    // -------------------------------------------------------------- commands

    @Override
    public void onMethodCall(@NonNull MethodCall call, @NonNull MethodChannel.Result result) {
        if (destroyed) {
            result.success(null);
            return;
        }
        switch (call.method) {
            case "loadUrl": {
                String url = call.argument("url");
                if (url == null || url.isEmpty()) {
                    result.error("bad_arg", "url required", null);
                    return;
                }
                if (MrNobodyApp.settings().isParamStrippingEnabled()) {
                    String stripped = TrackingParams.strip(url);
                    if (stripped != null && !stripped.isEmpty()) url = stripped;
                }
                webView.loadUrl(url);
                result.success(null);
                return;
            }
            case "reload":
                webView.reload();
                result.success(null);
                return;
            case "stop":
                webView.stopLoading();
                result.success(null);
                return;
            case "goBack":
                if (webView.canGoBack()) webView.goBack();
                result.success(null);
                return;
            case "goForward":
                if (webView.canGoForward()) webView.goForward();
                result.success(null);
                return;
            case "canGoBack":
                result.success(webView.canGoBack());
                return;
            case "canGoForward":
                result.success(webView.canGoForward());
                return;
            case "currentUrl":
                result.success(webView.getUrl());
                return;
            case "title":
                result.success(webView.getTitle());
                return;
            case "applySettings":
                applySettings();
                result.success(null);
                return;
            case "extractText":
                // Used by the agent path when it reads the visible page.
                webView.evaluateJavascript("document.body ? document.body.innerText : ''",
                        value -> result.success(value));
                return;
            default:
                result.notImplemented();
        }
    }

    // ---------------------------------------------------------------- plumbing

    private Map<String, Object> counters(@Nullable FilterEngine.Category category) {
        Map<String, Object> data = new HashMap<>();
        data.put("ads", MrNobodyApp.filters().getPageAdsBlocked());
        data.put("trackers", MrNobodyApp.filters().getPageTrackersBlocked());
        if (category != null) data.put("category", category.name());
        return data;
    }

    /** Scroll drives the collapsing chrome; only report meaningful movement. */
    private void reportScroll(int y) {
        if (Math.abs(y - lastReportedScrollY) < 8) return;
        lastReportedScrollY = y;
        Map<String, Object> data = new HashMap<>();
        data.put("y", y);
        send("onScroll", data);
    }

    private void send(String method, Map<String, Object> args) {
        if (destroyed) return;
        main.post(() -> {
            if (destroyed) return;
            channel.invokeMethod(method, args);
        });
    }

    @NonNull
    @Override
    public View getView() {
        return webView;
    }

    @Override
    public void dispose() {
        destroyed = true;
        channel.setMethodCallHandler(null);
        webView.setOnScrollChangeListener(null);
        webView.setWebChromeClient(null);
        webView.stopLoading();
        webView.loadUrl("about:blank");
        if (isPrivate) {
            // Best effort: a private tab leaves nothing cached behind it.
            webView.clearCache(true);
            webView.clearFormData();
            webView.clearHistory();
        }
        webView.destroy();
    }
}
