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

    void close();
}
