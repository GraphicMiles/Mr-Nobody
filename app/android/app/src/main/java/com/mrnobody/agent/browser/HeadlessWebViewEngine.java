package com.mrnobody.agent.browser;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebView;
import android.webkit.WebViewClient;

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
 */
public final class HeadlessWebViewEngine implements BrowserEngine {

    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private WebView webView;
    private volatile String currentTitle = "";

    public HeadlessWebViewEngine(Context context) {
        this.appContext = context.getApplicationContext();
    }

    /** Lazily create the WebView on the main thread. */
    private WebView webView() {
        if (webView == null) {
            webView = new WebView(appContext);
            webView.getSettings().setJavaScriptEnabled(true);
            webView.getSettings().setDomStorageEnabled(true);
        }
        return webView;
    }

    @Override
    public void open(String url) {
        onMain(() -> webView().loadUrl(url));
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

    @Override
    public String extractText() {
        return currentTitle;
    }

    @Override
    public String title() {
        return currentTitle;
    }

    @Override
    public String loadAndExtract(String url, long timeoutMs) {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> result = new AtomicReference<>("");

        onMain(() -> {
            WebView wv = webView();
            wv.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String finishedUrl) {
                    currentTitle = view.getTitle() == null ? "" : view.getTitle();
                    view.evaluateJavascript(
                            "(function(){try{return document.body?document.body.innerText:''}catch(e){return ''}})()",
                            value -> {
                                result.set(unescapeJs(value));
                                latch.countDown();
                            });
                }
            });
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

    @Override
    public String loadAndEvaluate(String url, String script, long timeoutMs) {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> result = new AtomicReference<>("");

        onMain(() -> {
            WebView wv = webView();
            wv.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String finishedUrl) {
                    currentTitle = view.getTitle() == null ? "" : view.getTitle();
                    // A results page often finishes loading before it finishes
                    // rendering its results, so give the document a moment.
                    view.postDelayed(() -> view.evaluateJavascript(script, value -> {
                        result.set(unescapeJs(value));
                        latch.countDown();
                    }), 350);
                }
            });
            wv.loadUrl(url);
        });

        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return result.get() == null ? "" : result.get();
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

    /** Evaluate a JS expression that must return a boolean, on the main thread. */
    private boolean evalBool(String js) {
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
        onMain(() -> {
            if (webView != null) {
                webView.destroy();
                webView = null;
            }
        });
    }

    private void onMain(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) r.run();
        else mainHandler.post(r);
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
