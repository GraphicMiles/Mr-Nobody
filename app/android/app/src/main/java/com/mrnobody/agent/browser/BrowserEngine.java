package com.mrnobody.agent.browser;

/**
 * Engine-independent browser capability, used by the BrowserTool. The visible
 * WebView and the headless engine both satisfy this; the agent depends on the
 * interface, never a concrete engine (see docs/ARCHITECTURE.md).
 */
public interface BrowserEngine {

    void open(String url);

    void back();

    void forward();

    void reload();

    /** Extract readable text from the current page (best-effort). */
    String extractText();

    /** Extract the page title, or null. */
    String title();

    /**
     * Load a URL and extract page text, blocking up to {@code timeoutMs}.
     * Implementations must run the WebView work on the main thread internally
     * and return best-effort text (never throw for expected failures).
     */
    String loadAndExtract(String url, long timeoutMs);

    /**
     * Interaction actions (V1 basic set). All return true on success, false on
     * failure. Implementations drive the engine's DOM via JavaScript; these
     * must never throw and must be safe to call from a background thread.
     */
    boolean click(String selector);

    boolean type(String selector, String text);

    boolean scroll(String direction);

    void waitFor(long millis);

    void close();
}
