package com.mrnobody.browser.webview;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
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
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.mrnobody.browser.MrNobodyApp;
import com.mrnobody.browser.net.FingerprintDefence;
import com.mrnobody.browser.net.ProfileManager;
import com.mrnobody.browser.blocking.FilterEngine;
import com.mrnobody.browser.blocking.NavigationGuard;
import com.mrnobody.browser.blocking.TrackingParams;
import com.mrnobody.browser.download.BlobSourceResolver;
import com.mrnobody.browser.download.DownloadEngine;
import com.mrnobody.browser.download.DownloadNaming;
import com.mrnobody.browser.download.DownloadRecord;
import com.mrnobody.browser.download.DownloadRisk;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

    /** Width of a tab-grid thumbnail, in pixels. */
    private static final int THUMBNAIL_WIDTH = 360;

    /**
     * Fingerprint noise seed, fixed for the life of the process. Stable within
     * a session on purpose: re-randomising per call would be detectable by
     * reading the same value twice.
     */
    private static final long FINGERPRINT_SEED = new java.util.Random().nextLong();

    private final Context context;
    private final WebView webView;
    private final SwipeRefreshLayout container;
    private final MethodChannel channel;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final boolean isPrivate;
    private final int tabId;

    /**
     * The current view instance per tab, so the stable tab-keyed channel can
     * survive a platform-view rebuild. A tab's view is destroyed and rebuilt
     * whenever the user leaves and returns to the browser, but the tab — and
     * the channel Dart talks to it over — must not move underneath it.
     */
    private static final java.util.Map<Integer, MrNobodyWebView> ACTIVE =
            new java.util.HashMap<>();

    /**
     * The MethodChannel per tab. Registered once and kept for the tab's whole
     * life: its handler delegates to whatever {@link #ACTIVE} instance holds
     * the tab right now. Clearing the handler when a view is detached is what
     * made Dart's applySettings/loadUrl throw MissingPluginException — Dart
     * holds the tab's channel for the tab's lifetime, so the handler must too.
     */
    private static final java.util.Map<Integer, MethodChannel> CHANNELS =
            new java.util.HashMap<>();

    /** The channel for a tab, registered lazily and never cleared until release. */
    private static MethodChannel channelFor(BinaryMessenger messenger, int tabId) {
        synchronized (CHANNELS) {
            MethodChannel ch = CHANNELS.get(tabId);
            if (ch == null) {
                ch = new MethodChannel(messenger, "mrnobody/webview_tab_" + tabId);
                ch.setMethodCallHandler((call, result) -> {
                    MrNobodyWebView v = ACTIVE.get(tabId);
                    if (v == null || v.destroyed) {
                        result.notImplemented();
                        return;
                    }
                    v.onMethodCall(call, result);
                });
                CHANNELS.put(tabId, ch);
            }
            return ch;
        }
    }

    /**
     * The tab is closed for good: drop its channel and mark the live view dead
     * so no late call can reach a destroyed WebView.
     */
    public static void releaseChannel(int tabId) {
        synchronized (CHANNELS) {
            MethodChannel ch = CHANNELS.remove(tabId);
            if (ch != null) ch.setMethodCallHandler(null);
        }
        MrNobodyWebView v = ACTIVE.remove(tabId);
        if (v != null) {
            v.destroyed = true;
            v.pendingDownloads.clear();
            v.container.setRefreshing(false);
            // Release the platform-view owner's attachment as well as the tab
            // registry's reference. Flutter may keep an offstage route alive;
            // it must not keep the private WebView mounted during deletion.
            v.container.removeAllViews();
        }
    }

    /** True when this tab genuinely has its own cookie/storage jar. */
    private final boolean isolated;

    private int lastReportedScrollY;
    private boolean destroyed;

    /** Harmful-looking downloads waiting for an explicit Flutter decision. */
    private final Map<String, PendingDownload> pendingDownloads = new LinkedHashMap<>();

    private static final class PendingDownload {
        final String url;
        final String userAgent;
        final String contentDisposition;
        final String mimeType;
        final String referrer;
        final String fileName;

        PendingDownload(String url, String userAgent, String contentDisposition,
                        String mimeType, String referrer, String fileName) {
            this.url = url;
            this.userAgent = userAgent;
            this.contentDisposition = contentDisposition;
            this.mimeType = mimeType;
            this.referrer = referrer;
            this.fileName = fileName;
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    MrNobodyWebView(@NonNull Context context, @NonNull BinaryMessenger messenger, int viewId,
                    @NonNull Map<String, Object> params) {
        this.context = context;
        this.isPrivate = Boolean.TRUE.equals(params.get("private"));
        this.tabId = params.get("tabId") instanceof Number
                ? ((Number) params.get("tabId")).intValue()
                : -1;

        // A tab's page outlives its platform view. Leaving the browser and
        // coming back rebuilds the view; adopting the tab's existing WebView is
        // what keeps the document, the history and the scroll position instead
        // of handing the user a black rectangle with nothing loaded in it.
        WebView retained = tabId >= 0 ? TabWebViews.get(tabId) : null;
        boolean fresh = retained == null;
        this.webView = fresh ? new WebView(context) : retained;

        // Storage isolation has to happen here, before the WebView navigates:
        // setProfile throws once a page has loaded. Only a fresh WebView is
        // eligible -- a retained one has already been bound and, if it is
        // private, was bound to the same profile when it was created.
        this.isolated = fresh && isPrivate
                ? ProfileManager.applyPrivate(webView)
                : (isPrivate && ProfileManager.isSupported());

        // Reduce unnecessary uniqueness. Also before first load, for the same
        // reason: a patch applied after page script has run is theatre.
        if (fresh && MrNobodyApp.settings().isFingerprintProtection()) {
            FingerprintDefence.apply(webView, FINGERPRINT_SEED);
        }

        if (fresh && tabId >= 0) TabWebViews.put(tabId, webView, isPrivate);

        // The channel is keyed by the stable tab id, not the ephemeral view id.
        // Flutter assigns a fresh view id to every platform-view rebuild, so a
        // view-id-keyed channel goes stale the moment the user leaves and
        // returns to the browser. A tab owns its page and its channel: the
        // channel is registered once per tab (see channelFor) and lives until
        // the tab is actually closed, so commands keep working across
        // detach/reattach.
        if (tabId >= 0) {
            this.channel = channelFor(messenger, tabId);
            ACTIVE.put(tabId, this);
        } else {
            this.channel = new MethodChannel(messenger, "mrnobody/webview_" + viewId);
            this.channel.setMethodCallHandler(this);
        }

        applySettings();
        applyCookiePolicy();

        // Re-bound on every adoption: the clients close over *this* instance,
        // so a retained page would otherwise keep reporting to a dead channel.
        webView.setBackgroundColor(0xFF000000);
        webView.setWebViewClient(client);
        webView.setWebChromeClient(chromeClient);
        webView.setDownloadListener(this::onDownloadRequested);
        webView.setOnScrollChangeListener((v, x, y, oldX, oldY) -> reportScroll(y));

        // The view is hosted in a container we own, so detaching it on dispose
        // cannot disturb whatever Flutter does with the platform view itself.
        TabWebViews.detach(webView);
        this.container = new SwipeRefreshLayout(context);
        this.container.setColorSchemeColors(0xFFFAFAFA);
        this.container.setProgressBackgroundColorSchemeColor(0xFF181818);
        this.container.setOnChildScrollUpCallback(
                (parent, child) -> webView.canScrollVertically(-1));
        this.container.setOnRefreshListener(() -> {
            if (destroyed) {
                container.setRefreshing(false);
                return;
            }
            webView.reload();
        });
        this.container.addView(webView, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        Object url = params.get("url");
        if (fresh && url instanceof String && !((String) url).isEmpty()) {
            webView.loadUrl((String) url);
        } else if (!fresh) {
            // Tell Dart what this tab is actually showing: the widget was
            // rebuilt, so its url/title/loading state need restating.
            main.post(this::reportCurrentState);
        }
    }

    /** Restate the adopted page's identity to a freshly attached Dart engine. */
    private void reportCurrentState() {
        if (destroyed) return;
        Map<String, Object> data = new HashMap<>();
        String url = webView.getUrl();
        if (url != null && !url.isEmpty()) data.put("url", url);
        String title = webView.getTitle();
        if (title != null && !title.isEmpty()) data.put("title", title);
        data.put("loading", false);
        send("onNavigation", data);
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
        // Data Saver grade: autoplay, images and caching per the user's choice.
        com.mrnobody.browser.net.ResourceControls.apply(webView,
                MrNobodyApp.settings().resourcePolicy());
        // A page must not be able to read the device's files or our own assets.
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        // Advertisers commonly attach a popup/pop-under to an otherwise valid
        // tap. Route every separate-window request through onCreateWindow so
        // it can never become a second surface; an ordinary link may instead
        // be opened safely in this tab.
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setSupportMultipleWindows(true);
        // Off on purpose: Safe Browsing sends visited URLs to Google. That is
        // the opposite of the privacy spec. Malware protection is the OS /
        // Play Protect job, not a reason for this browser to phone home.
        s.setSafeBrowsingEnabled(false);
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
        // Deliberately not CookieManager.getInstance(): that is always the
        // *default* profile's manager, so on an isolated tab it would
        // configure the wrong jar -- the policy would look set and not be.
        CookieManager cookies = ProfileManager.cookiesFor(webView);
        cookies.setAcceptCookie(true); // first-party cookies still work
        cookies.setAcceptThirdPartyCookies(webView, false);
    }

    /**
     * Whether this tab really is storage-isolated, for the UI to report
     * accurately rather than assume.
     */
    boolean isIsolated() {
        return isolated;
    }

    // ------------------------------------------------------------ page hooks

    private final WebViewClient client = new WebViewClient() {

        @Nullable
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            // Runs off the UI thread, once per request. Keep it cheap.
            boolean mainFrame = request.isForMainFrame();
            if (mainFrame) {
                // A new document: the per-page counters start again. Main-frame
                // ad hosts must still be checked; returning here was how
                // doubleclick.net escaped into a Google marketing redirect.
                MrNobodyApp.filters().resetPageCounters();
            }
            String url = request.getUrl() == null ? null : request.getUrl().toString();
            FilterEngine.Category category = MrNobodyApp.filters().shouldBlock(url);
            if (category == FilterEngine.Category.NONE) return null;

            reportBlocked(category, mainFrame);
            return new WebResourceResponse(BLOCKED_MIME, "utf-8",
                    new ByteArrayInputStream(new byte[0]));
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            if (uri == null) return false;
            String url = uri.toString();

            // Anything that is not the web belongs to another app (tel:,
            // mailto:, intent:). Hand it over instead of trying to render it.
            if (openExternallyIfRequired(uri)) return true;

            // Decide before a blocked main document starts. The request
            // interceptor below is the defence-in-depth backstop for paths that
            // do not invoke this callback.
            if (request.isForMainFrame()) {
                FilterEngine.Category category = NavigationGuard.evaluate(
                        MrNobodyApp.filters(), view.getUrl(), url, true);
                if (category != FilterEngine.Category.NONE) {
                    reportBlocked(category, true);
                    return true;
                }
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
            container.setRefreshing(false);
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
            container.setRefreshing(false);
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

        @Override
        public boolean onCreateWindow(WebView view, boolean isDialog,
                                      boolean isUserGesture,
                                      android.os.Message resultMsg) {
            // Never create the requested second WebView: that is the popup /
            // pop-under surface. A normal target=_blank anchor is still useful,
            // so resolve its focused href and open an allowed one in this tab.
            // Script-created windows and button-triggered ads have no focused
            // anchor and are refused with a non-error notice.
            if (!isUserGesture) {
                sendNotice("Popup blocked");
                return false;
            }
            android.os.Handler hrefHandler = new android.os.Handler(
                    android.os.Looper.getMainLooper()) {
                @Override
                public void handleMessage(android.os.Message message) {
                    String target = message.getData().getString("url");
                    handlePopupTarget(view, target);
                }
            };
            view.requestFocusNodeHref(hrefHandler.obtainMessage());
            return false;
        }

        /**
         * A file input on a visible page. Headless sessions cannot do this —
         * they have no window — so the agent parks and asks the user to open
         * the page here, where Android's picker can actually run.
         */
        @Override
        public boolean onShowFileChooser(WebView view,
                                         android.webkit.ValueCallback<android.net.Uri[]> filePathCallback,
                                         FileChooserParams fileChooserParams) {
            return com.mrnobody.browser.FileChooserHost.prompt(filePathCallback, fileChooserParams);
        }
    };

    // ------------------------------------------------------------- downloads

    /**
     * Start a download in the app's own engine.
     *
     * <p>Not {@code DownloadManager}: that service cannot write into a folder
     * chosen through the Storage Access Framework, cannot be paused, shows a
     * notification we do not control, and keeps running after Mr Nobody is
     * uninstalled because the transfer belongs to the system. All four were
     * things the user ran into. {@link DownloadEngine} owns the socket, so the
     * file goes where they asked and stops when they say stop.
     */
    private void onDownloadRequested(String url, String userAgent, String contentDisposition,
                                     String mimeType, long contentLength) {
        if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
            offerNetworkDownload(url, userAgent, contentDisposition, mimeType,
                    webView.getUrl());
            return;
        }
        if (url != null && url.startsWith("blob:")) {
            recoverBlobDownload(userAgent, contentDisposition, mimeType, webView.getUrl());
            return;
        }

        // This is a URL-mechanism limitation, not a file-type limitation. HTTP
        // downloads accept arbitrary MIME types; only a page-private URL with
        // no recoverable source has to be refused.
        sendDownloadError("This page did not expose a downloadable file link");
    }

    /**
     * A site may fetch an ordinary file, turn it into a temporary blob: URL and
     * click that. The blob exists only inside the page and cannot be opened by
     * Android's HTTP stack. Ask the page for strong source candidates, then let
     * pure Java ranking choose rather than guessing or special-casing a host.
     */
    private void recoverBlobDownload(String userAgent, String contentDisposition,
                                     String mimeType, String referrer) {
        webView.evaluateJavascript(BLOB_SOURCE_JS, raw -> {
            if (destroyed) return;
            String source = BlobSourceResolver.resolve(parseBlobCandidates(raw), mimeType);
            if (source == null) {
                sendDownloadError("This page created an in-memory file but did not expose "
                        + "a safe source link");
                return;
            }
            offerNetworkDownload(source, userAgent, contentDisposition, mimeType, referrer);
        });
    }

    private void offerNetworkDownload(String url, String userAgent,
                                      String contentDisposition, String mimeType,
                                      String referrer) {
        // Not URLUtil.guessFileName: it answers "downloadfile.bin" whenever the
        // server says octet-stream. The engine refines the name again from the
        // real response headers.
        String name = DownloadNaming.fileName(url, contentDisposition, mimeType);
        DownloadRisk.Assessment risk = DownloadRisk.assess(name, mimeType, url);
        if (!risk.requiresConfirmation) {
            startNetworkDownload(url, userAgent, contentDisposition, mimeType, referrer);
            return;
        }

        if (pendingDownloads.size() >= 8) {
            String oldest = pendingDownloads.keySet().iterator().next();
            pendingDownloads.remove(oldest);
        }
        String requestId = UUID.randomUUID().toString();
        pendingDownloads.put(requestId, new PendingDownload(
                url, userAgent, contentDisposition, mimeType, referrer, name));

        Map<String, Object> data = new HashMap<>();
        data.put("id", requestId);
        data.put("name", name);
        data.put("mime", mimeType == null ? "" : mimeType);
        String host = Uri.parse(url).getHost();
        data.put("host", host == null ? "" : host);
        data.put("warning", risk.reason);
        send("onDownloadApproval", data);
    }

    private void startNetworkDownload(String url, String userAgent,
                                      String contentDisposition, String mimeType,
                                      String referrer) {
        Map<String, Object> data = new HashMap<>();
        String name = DownloadNaming.fileName(url, contentDisposition, mimeType);
        try {
            DownloadRecord record = DownloadEngine.get(context)
                    .enqueue(url, name, mimeType, userAgent, referrer);
            data.put("name", record.fileName);
            data.put("id", record.id);
            data.put("folder", record.destLabel);
        } catch (Exception e) {
            data.put("error", "Could not start the download");
        }
        send("onDownload", data);
    }

    private void sendDownloadError(String message) {
        Map<String, Object> data = new HashMap<>();
        data.put("error", message);
        send("onDownload", data);
    }

    static List<BlobSourceResolver.Candidate> parseBlobCandidates(String raw) {
        List<BlobSourceResolver.Candidate> out = new ArrayList<>();
        if (raw == null || raw.isEmpty() || "null".equals(raw)) return out;
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length() && out.size() < 256; i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                String url = item.optString("url", "");
                String kind = item.optString("kind", "");
                if (!url.isEmpty()) out.add(new BlobSourceResolver.Candidate(url, kind));
            }
        } catch (Exception e) {
            com.mrnobody.debug.ErrorLog.record("blob download source list was invalid");
        }
        return out;
    }

    private static final String BLOB_SOURCE_JS =
            "(function(){try{var out=[],seen={};"
                    + "function add(u,k){try{u=new URL(u,document.baseURI).href;}catch(e){return;}"
                    + "if(!/^https?:/i.test(u)||seen[u])return;seen[u]=1;"
                    + "out.push({url:u,kind:k});}"
                    + "document.querySelectorAll('link[itemprop=\\\"contentUrl\\\"][href]')"
                    + ".forEach(function(e){add(e.href,'content');});"
                    + "document.querySelectorAll('meta[property=\\\"og:image\\\"][content],"
                    + "meta[name=\\\"twitter:image\\\"][content]')"
                    + ".forEach(function(e){add(e.content,'content');});"
                    + "document.querySelectorAll('a[download][href]')"
                    + ".forEach(function(e){add(e.href,'download');});"
                    + "var r=performance.getEntriesByType('resource');"
                    + "for(var i=r.length-1;i>=0;i--)add(r[i].name,'resource');"
                    + "return out;}catch(e){return [];}})()";

    // -------------------------------------------------------------- commands

    @Override
    public void onMethodCall(@NonNull MethodCall call, @NonNull MethodChannel.Result result) {
        if (destroyed) {
            result.success(null);
            return;
        }
        switch (call.method) {
            case "resolveDownload": {
                String requestId = call.argument("id");
                Boolean allow = call.argument("allow");
                if (requestId == null || allow == null) {
                    result.error("bad_arg", "id and allow required", null);
                    return;
                }
                PendingDownload pending = pendingDownloads.remove(requestId);
                if (pending == null) {
                    result.success(false);
                    return;
                }
                if (allow) {
                    startNetworkDownload(pending.url, pending.userAgent,
                            pending.contentDisposition, pending.mimeType, pending.referrer);
                }
                result.success(true);
                return;
            }
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
                // A reload has to have something to reload. If the page was
                // evicted (or never loaded), reload() is a silent no-op — which
                // is exactly how the Reload button came to do nothing — so fall
                // back to re-fetching the last known address.
                if (webView.getUrl() == null || webView.getUrl().isEmpty()) {
                    String last = webView.getOriginalUrl();
                    if (last != null && !last.isEmpty() && !"about:blank".equals(last)) {
                        webView.loadUrl(last);
                    }
                } else {
                    webView.reload();
                }
                result.success(null);
                return;
            case "stop":
                webView.stopLoading();
                container.setRefreshing(false);
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
            case "capture": {
                // A tab card should show the page, not a drawing of one.
                result.success(captureThumbnail());
                return;
            }
            case "extractText":
                // Used by the agent path when it reads the visible page.
                webView.evaluateJavascript("document.body ? document.body.innerText : ''",
                        value -> result.success(value));
                return;
            default:
                result.notImplemented();
        }
    }

    /**
     * A small JPEG of what the page currently looks like, for the tab grid.
     *
     * <p>Never captured for a private tab: a thumbnail is a picture of what
     * someone was reading, and a private tab exists precisely so that no such
     * record is kept. The bytes are handed to Dart and held in memory only —
     * nothing writes them to disk.
     */
    private byte[] captureThumbnail() {
        if (isPrivate) return null;
        try {
            int width = webView.getWidth();
            int height = webView.getHeight();
            if (width <= 0 || height <= 0) return null;
            float scale = (float) THUMBNAIL_WIDTH / width;
            int outWidth = Math.max(1, Math.round(width * scale));
            int outHeight = Math.max(1, Math.round(height * scale));
            // RGB_565: a thumbnail does not need an alpha channel, and this is
            // half the memory of ARGB_8888 per capture.
            Bitmap bitmap = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.RGB_565);
            Canvas canvas = new Canvas(bitmap);
            canvas.scale(scale, scale);
            webView.draw(canvas);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 55, out);
            bitmap.recycle();
            return out.toByteArray();
        } catch (Throwable t) {
            // A capture is a nicety; never let it take the page down.
            return null;
        }
    }

    private boolean openExternallyIfRequired(@Nullable Uri uri) {
        if (uri == null) return false;
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (scheme.equals("http") || scheme.equals("https")) return false;
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException ignored) {
            // No app handles it — do nothing rather than crash.
        }
        return true;
    }

    private void handlePopupTarget(WebView source, @Nullable String target) {
        if (target == null || target.trim().isEmpty()) {
            sendNotice("Popup blocked");
            return;
        }
        String resolvedTarget = target.trim();
        Uri uri = Uri.parse(resolvedTarget);
        if (uri.getScheme() == null && source.getUrl() != null) {
            try {
                resolvedTarget = new java.net.URI(source.getUrl())
                        .resolve(resolvedTarget).toString();
                uri = Uri.parse(resolvedTarget);
            } catch (Exception ignored) {
                sendNotice("Popup blocked");
                return;
            }
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (scheme.equals("javascript") || scheme.equals("data")
                || scheme.equals("about") || scheme.equals("blob")
                || scheme.equals("file") || scheme.equals("content")) {
            sendNotice("Popup blocked");
            return;
        }
        if (openExternallyIfRequired(uri)) return;

        String webUrl = uri.toString();
        FilterEngine.Category category = NavigationGuard.evaluate(
                MrNobodyApp.filters(), source.getUrl(), webUrl, true);
        if (category != FilterEngine.Category.NONE) {
            reportBlocked(category, true);
            return;
        }
        if (MrNobodyApp.settings().isParamStrippingEnabled()) {
            String stripped = TrackingParams.strip(webUrl);
            if (stripped != null) webUrl = stripped;
        }
        source.loadUrl(webUrl);
    }

    // ---------------------------------------------------------------- plumbing

    private void reportBlocked(FilterEngine.Category category,
                               boolean mainFrame) {
        MrNobodyApp.report().increment(
                category == FilterEngine.Category.AD ? "ads" : "trackers");
        send("onBlocked", counters(category));
        if (mainFrame) {
            sendNotice(category == FilterEngine.Category.TRACKER
                    ? "Tracker navigation blocked"
                    : "Ad redirect blocked");
        }
    }

    private void sendNotice(String message) {
        Map<String, Object> data = new HashMap<>();
        data.put("message", message);
        send("onNotice", data);
    }

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
        return container;
    }

    /**
     * The platform view is going away — but the tab may not be. Detach the page
     * and leave it registered so the next view for this tab adopts it intact.
     * The WebView is destroyed in {@link TabWebViews#release} when the tab is
     * actually closed.
     */
    @Override
    public void dispose() {
        container.setRefreshing(false);
        if (tabId < 0) {
            // No tab identity to retain against: this view owned its page.
            destroyed = true;
            pendingDownloads.clear();
            channel.setMethodCallHandler(null);
            container.removeAllViews();
            webView.stopLoading();
            webView.loadUrl("about:blank");
            webView.destroy();
            return;
        }
        // The tab lives on. Detach the container only. The channel, the clients
        // and the retained WebView all outlive this view instance and are
        // adopted by the next view for this tab. Clearing the handler here is
        // exactly the MissingPluginException that broke applySettings/loadUrl:
        // Dart holds the tab's channel for the tab's lifetime, so the handler
        // must too (it is cleared only in releaseChannel, when the tab closes).
        container.removeAllViews();
    }
}
