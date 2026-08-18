package com.mrnobody.browser.net;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.net.URLConnection;

/**
 * The one place this app opens an outbound connection.
 *
 * <p>Before this class there were five: the download engine, the HTTP tool,
 * the search tool, and two AI providers, each calling
 * {@code url.openConnection()} with no proxy argument. That is fine while
 * every route is direct, and becomes a privacy failure the moment one is not —
 * the WebView would ride the privacy route while the agent's searches, page
 * fetches, downloads and AI calls carried on in the clear, under a UI telling
 * the user they were protected.
 *
 * <p>So the rule is: <b>nothing in this app calls {@code openConnection()}
 * directly.</b> Everything comes here, and here is where fail-closed is
 * enforced. A checkstyle-style grep for {@code openConnection} outside this
 * package is the cheapest way to keep that true.
 *
 * <p>This class does not decide policy; it applies whatever route is current.
 */
public final class NetworkGate {

    /**
     * Raised instead of connecting when a fail-closed route is unavailable.
     *
     * <p>An {@link IOException} on purpose: every caller already handles those,
     * so the failure surfaces as an ordinary network error and reports rather
     * than crashing. What it must never do is get swallowed into a retry that
     * connects directly.
     */
    public static final class RouteUnavailableException extends IOException {
        public RouteUnavailableException(String message) {
            super(message);
        }
    }

    private static volatile NetworkRoute route = new DirectRoute();

    private NetworkGate() {
    }

    /** The route currently in force. Never null. */
    public static NetworkRoute route() {
        return route;
    }

    /**
     * Replace the active route. The caller is responsible for also applying it
     * to the WebView — see {@code WebViewRouter} — because the two mechanisms
     * are genuinely separate and pretending otherwise hides the seam.
     */
    public static void setRoute(NetworkRoute next) {
        route = next == null ? new DirectRoute() : next;
    }

    /**
     * True when a connection may be attempted right now.
     *
     * <p>False only for a fail-closed route that is unavailable — that is the
     * whole condition, stated once, so no caller has to reason about it.
     */
    public static boolean canConnect() {
        NetworkRoute current = route;
        return !current.failClosed() || current.isAvailable();
    }

    /**
     * Why a connection would be refused, for showing a user, or null when it
     * would be allowed.
     */
    public static String blockedReason() {
        NetworkRoute current = route;
        if (!current.failClosed() || current.isAvailable()) return null;
        return current.label() + " is not reachable. Nothing was sent, because "
                + "this route is set to fail closed rather than connect directly.";
    }

    /**
     * Open a connection over the active route.
     *
     * @throws RouteUnavailableException if the route is fail-closed and down.
     *         No connection is attempted in that case — not a direct one, and
     *         not a retry.
     */
    public static URLConnection open(URL url) throws IOException {
        NetworkRoute current = route;
        if (current.failClosed() && !current.isAvailable()) {
            throw new RouteUnavailableException(blockedReason());
        }
        Proxy proxy = current.proxy();
        return proxy == null || proxy == Proxy.NO_PROXY
                ? url.openConnection()
                : url.openConnection(proxy);
    }

    /** {@link #open(URL)} for the common HTTP case. */
    public static HttpURLConnection openHttp(URL url) throws IOException {
        URLConnection conn = open(url);
        if (!(conn instanceof HttpURLConnection)) {
            throw new IOException("not an HTTP(S) URL: " + url);
        }
        return (HttpURLConnection) conn;
    }

    /** {@link #openHttp(URL)} from a string. */
    public static HttpURLConnection openHttp(String url) throws IOException {
        return openHttp(new URL(url));
    }
}
