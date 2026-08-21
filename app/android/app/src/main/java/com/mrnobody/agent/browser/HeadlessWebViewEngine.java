package com.mrnobody.agent.browser;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.mrnobody.agent.util.NetworkTargetPolicy;
import com.mrnobody.browser.net.NetworkGate;
import com.mrnobody.browser.net.ProfileManager;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import com.mrnobody.debug.ErrorLog;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A headless WebView-based engine — V1's "one tested lightweight backend". It
 * renders off-screen and extracts page text for the agent. Uses Android System
 * WebView as the rendering surface, so it adds no APK size. If a lighter/faster
 * engine proves necessary later, it replaces this class behind BrowserEngine.
 *
 * WebView work is marshalled onto the main thread internally, so tools can call
 * it from any thread. Extraction is best-effort; a timeout yields whatever
 * (possibly empty) text is available rather than hanging the agent.
 *
 * <p>An engine belongs to a {@link SessionScope}. Where the device's WebView
 * supports multi-profile, the scope becomes a real separate cookie and storage
 * jar, so one task cannot inherit or leak another task's logins. Where it does
 * not, the engine still works but shares the default jar -- {@link #isIsolated()}
 * reports which, and no caller may claim isolation without asking.
 */
public final class HeadlessWebViewEngine implements BrowserEngine {

    private final Context appContext;
    private final SessionScope scope;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private WebView webView;
    private volatile String currentTitle = "";
    private volatile boolean isolated = false;

    /** An engine on the shared agent session. */
    public HeadlessWebViewEngine(Context context) {
        this(context, SessionScope.shared());
    }

    public HeadlessWebViewEngine(Context context, SessionScope scope) {
        this.appContext = context.getApplicationContext();
        this.scope = scope == null ? SessionScope.shared() : scope;
    }

    /** The session this engine's browsing belongs to. */
    public SessionScope scope() {
        return scope;
    }

    /**
     * Whether this engine's storage is genuinely separate.
     *
     * <p>False until the WebView is created, and false forever on a device
     * whose WebView lacks multi-profile. Callers that surface a privacy claim
     * must read this rather than assuming the scope was honoured.
     */
    public boolean isIsolated() {
        return isolated;
    }

    /** Lazily create the WebView on the main thread. */
    private WebView webView() {
        if (webView == null) {
            webView = new WebView(appContext);
            // Before any navigation: setProfile throws once a WebView has
            // loaded a page, so this is the only moment isolation can be
            // established.
            isolated = ProfileManager.applyProfile(webView, scope.profileName());
            webView.getSettings().setJavaScriptEnabled(true);
            webView.getSettings().setDomStorageEnabled(true);
            // Same rule as the visible WebView: do not send agent URLs to Google.
            webView.getSettings().setSafeBrowsingEnabled(false);
            webView.setWebViewClient(routeGuardClient());
            // Data Saver grade, applied before first load so it holds from the
            // first request. Reads the current setting; OFF by default.
            try {
                com.mrnobody.browser.net.ResourceControls.apply(webView,
                        com.mrnobody.browser.MrNobodyApp.settings().resourcePolicy());
            } catch (Throwable ignored) {
                // Core not up (tests): OFF-equivalent defaults already applied.
            }
        }
        return webView;
    }

    @Override
    public void open(String url) {
        onMain(() -> {
            WebView wv = webView();
            applyGrantedCookies(wv, url);
            wv.loadUrl(url);
        });
    }

    /** A WebView client that refuses every request while the route is blocked. */
    private static WebViewClient routeGuardClient() {
        return new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view,
                                                               WebResourceRequest request) {
                return blockedByRoute(request);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return unsafeAgentRedirect(request);
            }
        };
    }

    private static WebResourceResponse blockedByRoute(WebResourceRequest request) {
        boolean main = request != null && request.isForMainFrame();
        String reason = NetworkGate.blockedReason();
        int status = 503;
        String statusText = "Protected route not ready";
        if (reason == null && request != null && request.getUrl() != null) {
            String target = request.getUrl().toString();
            String lower = target.toLowerCase(java.util.Locale.ROOT);
            // Data/blob resources do not open a network target. Main-frame
            // navigations and every HTTP(S) subresource do cross the boundary.
            if (main || lower.startsWith("http://") || lower.startsWith("https://")) {
                reason = NetworkTargetPolicy.publicReason(target,
                        NetworkGate.canConnect() && NetworkGate.resolvesTargetsLocally());
                status = 403;
                statusText = "Agent target refused";
            }
        }
        if (reason == null) return null;
        byte[] body = main ? reason.getBytes(StandardCharsets.UTF_8) : new byte[0];
        return new WebResourceResponse("text/plain", "utf-8",
                status, statusText, Collections.emptyMap(),
                new ByteArrayInputStream(body));
    }

    /** Block a redirect to an obviously local/literal target on the UI thread. */
    private static boolean unsafeAgentRedirect(WebResourceRequest request) {
        if (request == null || !request.isForMainFrame() || request.getUrl() == null) return false;
        return NetworkTargetPolicy.publicReason(request.getUrl().toString(), false) != null;
    }

    private static void applyGrantedCookies(WebView wv, String url) {
        try {
            com.mrnobody.browser.MrNobodyApp.accounts().applyTo(wv, url);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void back() {
        onMain(() -> { if (webView != null && webView.canGoBack()) webView.goBack(); });
    }

    @Override
    public void forward() {
        onMain(() -> { if (webView != null && webView.canGoForward()) webView.goForward(); });
    }

    @Override
    public void reload() {
        onMain(() -> { if (webView != null) webView.reload(); });
    }

    /**
     * Readable body text of the page that is already loaded.
     *
     * <p>Returning {@link #currentTitle} here was the bug: {@code browser.extract}
     * and page-anchor checks then saw only the tab title, so a fetched article
     * looked empty and a follow-up click had nothing to anchor to.
     */
    @Override
    public String extractText() {
        String text = evaluate(EXTRACT_JS, 8_000);
        if (text == null || text.trim().isEmpty()) return currentTitle;
        return text;
    }

    @Override
    public String title() {
        return currentTitle;
    }

    /** One WebView, so concurrent loads must be serialised or they interleave. */
    private final Object loadLock = new Object();

    @Override
    public String loadAndExtract(String url, long timeoutMs) {
        // Serialised: two tools (search escalation and page read) share this
        // engine, and two concurrent loadUrl calls on one WebView rebind the
        // client mid-flight and cross each other's callbacks.
        synchronized (loadLock) {
            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicReference<String> result = new AtomicReference<>("");

            final java.util.concurrent.atomic.AtomicBoolean released =
                    new java.util.concurrent.atomic.AtomicBoolean(false);

            onMain(() -> {
                WebView wv = webView();
                wv.setWebViewClient(new WebViewClient() {
                    @Override
                    public WebResourceResponse shouldInterceptRequest(WebView view,
                                                                       WebResourceRequest request) {
                        return blockedByRoute(request);
                    }

                    @Override
                    public boolean shouldOverrideUrlLoading(WebView view,
                                                            WebResourceRequest request) {
                        return unsafeAgentRedirect(request);
                    }

                    @Override
                    public void onPageFinished(WebView view, String finishedUrl) {
                        if (isBlankNavigation(finishedUrl)) return;
                        currentTitle = view.getTitle() == null ? "" : view.getTitle();
                        // Same delay as loadAndEvaluate: onPageFinished often
                        // fires before the body (or a JS-rendered article) exists.
                        view.postDelayed(() -> view.evaluateJavascript(EXTRACT_JS, value -> {
                            result.set(unescapeJs(value));
                            if (released.compareAndSet(false, true)) latch.countDown();
                        }), 400);
                    }
                });
                applyGrantedCookies(wv, url);
                wv.loadUrl(url);
            });

            try {
                latch.await(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            String text = result.get();
            if (text == null || text.trim().isEmpty()) {
                // Fall back to the title if body text was unavailable.
                return currentTitle;
            }
            return text;
        }
    }

    @Override
    public String loadAndEvaluate(String url, String script, long timeoutMs) {
        synchronized (loadLock) {
            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicReference<String> result = new AtomicReference<>("");

            onMain(() -> {
                WebView wv = webView();
                wv.setWebViewClient(new WebViewClient() {
                    @Override
                    public WebResourceResponse shouldInterceptRequest(WebView view,
                                                                       WebResourceRequest request) {
                        return blockedByRoute(request);
                    }

                    @Override
                    public boolean shouldOverrideUrlLoading(WebView view,
                                                            WebResourceRequest request) {
                        return unsafeAgentRedirect(request);
                    }

                    @Override
                    public void onPageFinished(WebView view, String finishedUrl) {
                        if (isBlankNavigation(finishedUrl)) return;
                        currentTitle = view.getTitle() == null ? "" : view.getTitle();
                        // A results page often finishes loading before it finishes
                        // rendering its results, so give the document a moment.
                        view.postDelayed(() -> view.evaluateJavascript(script, value -> {
                            result.set(unescapeJs(value));
                            latch.countDown();
                        }), 400);
                    }
                });
                applyGrantedCookies(wv, url);
                wv.loadUrl(url);
            });

            try {
                latch.await(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return result.get() == null ? "" : result.get();
        }
    }

    @Override
    public boolean click(String selector) {
        return evalBool("(function(){var e=document.querySelector("
                + jsString(selector) + ");if(!e)return false;e.click();return true})()");
    }

    @Override
    public boolean type(String selector, String text) {
        return evalBool("(function(){var e=document.querySelector("
                + jsString(selector) + ");if(!e)return false;"
                + "e.focus();"
                + "if(typeof e.value!=='undefined'){e.value=" + jsString(text) + ";}"
                + "else{e.textContent=" + jsString(text) + ";}"
                + "e.dispatchEvent(new Event('input',{bubbles:true}));return true})()");
    }

    @Override
    public boolean scroll(String direction) {
        String js;
        switch (direction == null ? "down" : direction.toLowerCase()) {
            case "up":    js = "window.scrollBy(0,-600)"; break;
            case "down":
            default:      js = "window.scrollBy(0,600)"; break;
        }
        return evalBool("(function(){" + js + ";return true})()");
    }

    @Override
    public void waitFor(long millis) {
        try {
            Thread.sleep(Math.max(0, millis));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean select(String selector, String option) {
        return evalBool("(function(){var e=document.querySelector("
                + jsString(selector) + ");if(!e||!e.options)return false;"
                + "var want=" + jsString(option) + ";"
                + "for(var i=0;i<e.options.length;i++){"
                + "if(e.options[i].value===want||e.options[i].text===want){"
                + "e.selectedIndex=i;"
                + "e.dispatchEvent(new Event('change',{bubbles:true}));"
                + "return true;}}return false})()");
    }

    @Override
    public boolean waitForSelector(String selector, long timeoutMs) {
        long deadline = System.currentTimeMillis() + Math.max(200, timeoutMs);
        while (System.currentTimeMillis() < deadline) {
            if (evalBool("(function(){return !!document.querySelector("
                    + jsString(selector) + ")})()")) {
                return true;
            }
            waitFor(200);
        }
        return false;
    }

    @Override
    public boolean uploadFile(String selector, String absolutePath) {
        // Headless WebView cannot attach a real file chooser. The tool
        // refuses honestly rather than pretending the input was filled.
        return false;
    }

    @Override
    public String evaluate(String script, long timeoutMs) {
        synchronized (loadLock) {
            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicReference<String> result = new AtomicReference<>("");
            onMain(() -> {
                WebView wv = webView();
                wv.evaluateJavascript(script, value -> {
                    result.set(unescapeJs(value));
                    latch.countDown();
                });
            });
            try {
                latch.await(Math.max(500, timeoutMs), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return result.get() == null ? "" : result.get();
        }
    }

    /** Evaluate a JS expression that must return a boolean, on the main thread. */
    private boolean evalBool(String js) {
        synchronized (loadLock) {
            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicReference<Boolean> result = new AtomicReference<>(false);
            onMain(() -> {
                WebView wv = webView();
                wv.evaluateJavascript(js, value -> {
                    result.set("true".equalsIgnoreCase(unescapeJs(value)));
                    latch.countDown();
                });
            });
            try {
                latch.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Boolean.TRUE.equals(result.get());
        }
    }

    /** JSON-string-encode a value for safe inlining into JS source. */
    private static String jsString(String s) {
        return "\"" + (s == null ? "" : s)
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r") + "\"";
    }

    @Override
    public void close() {
        // Serialised with loads: never destroy a WebView while a load is
        // driving it. The worker calls this after a task's run completes, when
        // no load should be in flight, but the lock makes that a guarantee
        // rather than an assumption.
        synchronized (loadLock) {
            onMain(() -> {
                if (webView != null) {
                    webView.stopLoading();
                    webView.destroy();
                    webView = null;
                }
                // Only after the WebView is destroyed: deleteProfile refuses
                // while a live WebView still holds the profile, so doing this
                // first would silently leave the session's data on disk.
                if (isolated && scope.isEphemeral()) {
                    ProfileManager.destroyProfileWhenIdle(scope.profileName());
                }
                isolated = false;
            });
        }
    }

    private void onMain(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) r.run();
        else mainHandler.post(r);
    }

    /**
     * Readable text of the rendered document. Prefers {@code innerText} so
     * scripts and chrome stay out; falls back to a main/article node when the
     * body is still empty (a JS-rendered page that finished too early).
     */
    static final String EXTRACT_JS =
            "(function(){try{"
                    + "var b=document.body;if(!b)return '';"
                    // textContent includes <style>/<script> text — the ':root{--…}'
                    // CSS soup a device answer once quoted as evidence. Clone the
                    // node, cut those subtrees, then read textContent.
                    + "function clean(n){var c=n.cloneNode(true);"
                    + "var kill=c.querySelectorAll('style,script,noscript,template,svg');"
                    + "for(var i=0;i<kill.length;i++){kill[i].parentNode.removeChild(kill[i]);}"
                    + "return c.textContent||'';}"
                    + "var main=document.querySelector('article,main,[role=main]');"
                    + "var t=main?(main.innerText||clean(main)):'';"
                    + "if(!t||t.replace(/\\s+/g,'').length<40){"
                    + "t=b.innerText||clean(b);"
                    + "}"
                    + "return t;"
                    + "}catch(e){return ''}})()";

    /** The page's own preview image, for evidence cards. */
    static final String OG_IMAGE_JS =
            "(function(){try{"
                    + "function attr(sel,a){var e=document.querySelector(sel);return e?e.getAttribute(a):'';}"
                    + "var u=attr('meta[property=\"og:image\"]','content')"
                    + "||attr('meta[property=\"og:image:url\"]','content')"
                    + "||attr('meta[name=\"twitter:image\"]','content')"
                    + "||attr('meta[name=\"twitter:image:src\"]','content')"
                    + "||attr('link[rel=\"image_src\"]','href')||'';"
                    + "if(!u){var img=document.querySelector('article img[src],main img[src],img[src]');"
                    + "if(img)u=img.src||'';}"
                    + "return u||'';"
                    + "}catch(e){return ''}})()";

    /** about:blank and empty callbacks are not a finished page. */
    static boolean isBlankNavigation(String url) {
        if (url == null || url.isEmpty()) return true;
        String u = url.toLowerCase(java.util.Locale.ROOT);
        return u.startsWith("about:") || u.startsWith("data:text/html");
    }

    /** Strip the JSON string wrapper and basic escapes evaluateJavascript adds. */
    private static String unescapeJs(String s) {
        if (s == null) return "";
        String out = s;
        if (out.startsWith("\"") && out.endsWith("\"") && out.length() >= 2) {
            out = out.substring(1, out.length() - 1);
        }
        return out
                .replace("\\n", "\n")
                .replace("\\t", "\t")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\u003c", "<")
                .replace("\\u003e", ">");
    }
}
