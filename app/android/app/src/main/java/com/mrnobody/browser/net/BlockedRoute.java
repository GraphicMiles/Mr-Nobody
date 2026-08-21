package com.mrnobody.browser.net;

import java.net.Proxy;

/**
 * A deliberate no-network state used while a privacy route is being prepared.
 *
 * <p>Keeping the previous DirectRoute during Tor bootstrap or while Chromium
 * applies a proxy creates a small but real clear-net escape window. This route
 * closes that window: NetworkGate refuses every native connection, and the
 * WebView clients consult the same gate before serving a request.
 */
public final class BlockedRoute implements NetworkRoute {

    public static final String ID = "blocked-transition";

    private final String label;

    private BlockedRoute(String label) {
        this.label = label == null || label.trim().isEmpty()
                ? "Protected route" : label.trim();
    }

    /** A blocker whose message names the route that is being prepared. */
    public static BlockedRoute forRoute(NetworkRoute route) {
        return new BlockedRoute(route == null ? null : route.label());
    }

    @Override public String id() { return ID; }

    @Override public String label() { return label + " is being prepared"; }

    @Override public Proxy proxy() { return Proxy.NO_PROXY; }

    @Override public boolean failClosed() { return true; }

    @Override public boolean isAvailable() { return false; }

    @Override public void refresh() { }

    @Override public String webViewProxyRule() { return null; }
}
