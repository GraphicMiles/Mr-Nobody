package com.mrnobody.browser.net;

import java.net.Proxy;

/**
 * How this app's traffic reaches the internet.
 *
 * <p>Its own interface because the browser must not know whether it is talking
 * to Tor, a proxy, or nothing at all. The engine asks for a transport; the
 * route decides what that is.
 *
 * <p><b>The reason this exists.</b> {@code ProxyController} configures the
 * WebView and only the WebView. Five other places in this app open their own
 * sockets — downloads, the HTTP tool, search, and two AI providers. Before this
 * interface, turning on a privacy route would have moved the browser's traffic
 * and left all five going direct, which is worse than no privacy route at all:
 * the user is told they are covered while the agent narrates their activity to
 * a search engine in the clear.
 *
 * <p>So a route is not "a proxy setting for the WebView". It is the answer to
 * "how does <em>anything</em> in this process reach the network", and every
 * caller goes through {@link NetworkGate} to get it.
 *
 * <p>Implementations are plain Java so the routing decisions can be tested on
 * a JVM without an Android runtime.
 */
public interface NetworkRoute {

    /** Stable identifier, persisted in settings. */
    String id();

    /** Human-readable name for the UI. */
    String label();

    /**
     * The transport for direct socket users. {@link Proxy#NO_PROXY} means a
     * normal connection.
     */
    Proxy proxy();

    /**
     * True when traffic must never fall back to a direct connection.
     *
     * <p>The whole point of a privacy route is that its absence is a refusal,
     * not a downgrade. A Tor route that quietly becomes a direct route when
     * Orbot stops is a privacy failure that looks like success, so
     * {@link NetworkGate} refuses to open a connection instead.
     */
    boolean failClosed();

    /**
     * Whether the route can carry traffic right now.
     *
     * <p>Implementations must make this cheap — it is consulted on every
     * connection. Probing belongs behind a cache.
     */
    boolean isAvailable();

    /**
     * Re-check availability, ignoring any cached answer. Called when the user
     * switches routes and after a connection failure.
     */
    void refresh();

    /**
     * A proxy rule for {@code androidx.webkit}'s ProxyConfig, or null when the
     * WebView should connect directly.
     *
     * <p>Returned as a string rather than a ProxyConfig so this interface stays
     * free of Android types and testable on a JVM.
     */
    String webViewProxyRule();
}
