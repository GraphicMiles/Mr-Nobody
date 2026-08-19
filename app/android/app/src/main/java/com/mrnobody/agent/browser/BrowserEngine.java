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

    /**
     * Load a page and evaluate a script against the rendered DOM, returning
     * whatever the script produced.
     *
     * <p>This is how the agent reads a results page: the DOM after JavaScript
     * has run, rather than a regex over the HTML that arrived. Markup churn
     * breaks a regex; a selector against the live document survives it, and a
     * page that renders results client-side is only visible this way at all.
     */
    String loadAndEvaluate(String url, String script, long timeoutMs);

    /**
     * Evaluate {@code script} against the page that is already loaded.
     * Default is empty: an engine that cannot talk to a live document
     * still satisfies the interface.
     */
    default String evaluate(String script, long timeoutMs) {
        return "";
    }

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

    /** Pick an option in a {@code <select>} by value or visible text. */
    default boolean select(String selector, String option) {
        return false;
    }

    /** Poll until {@code selector} matches, or {@code timeoutMs} elapses. */
    default boolean waitForSelector(String selector, long timeoutMs) {
        return false;
    }

    /**
     * Attach a local file to a file input. {@code absolutePath} is already
     * confined to the workspace by the caller.
     */
    default boolean uploadFile(String selector, String absolutePath) {
        return false;
    }

    void close();
}
