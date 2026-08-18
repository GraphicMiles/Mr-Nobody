package com.mrnobody.browser.net;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.Locale;

/**
 * A user-configured HTTP or SOCKS proxy.
 *
 * <p>Availability is not probed. A proxy the user typed in is assumed to be
 * what they want; if it is wrong, the connection fails loudly rather than
 * silently going direct, which is the behaviour a privacy setting has to have.
 *
 * <p>Two limits worth stating where they are enforced rather than in a UI
 * string somewhere else:
 *
 * <ul>
 *   <li><b>No SOCKS authentication.</b> Chromium has never implemented SOCKS5
 *       username/password auth and closed the request as won't-fix, so a
 *       WebView cannot use an authenticated SOCKS proxy however we configure
 *       it. Credentials are therefore not accepted here at all — offering a
 *       field that silently does nothing would be worse than not offering one.
 *   <li><b>Process-wide.</b> WebView's proxy override applies to every WebView
 *       in the process. This class describes one route for the whole app.
 * </ul>
 */
public final class ProxyRoute implements NetworkRoute {

    public static final String ID = "proxy";

    /** What kind of proxy this is. SOCKS here always means SOCKS5. */
    public enum Kind {
        HTTP("http", 8080),
        SOCKS("socks", 1080);

        private final String scheme;
        private final int defaultPort;

        Kind(String scheme, int defaultPort) {
            this.scheme = scheme;
            this.defaultPort = defaultPort;
        }

        public String scheme() {
            return scheme;
        }

        public int defaultPort() {
            return defaultPort;
        }

        public static Kind fromName(String name) {
            if (name != null && name.trim().equalsIgnoreCase("socks")) return SOCKS;
            return HTTP;
        }
    }

    private final Kind kind;
    private final String host;
    private final int port;

    public ProxyRoute(Kind kind, String host, int port) {
        this.kind = kind == null ? Kind.HTTP : kind;
        this.host = host == null ? "" : host.trim();
        this.port = port > 0 && port <= 65535 ? port : this.kind.defaultPort();
    }

    /** True when this route has enough information to connect. */
    public boolean isConfigured() {
        return !host.isEmpty();
    }

    public Kind kind() {
        return kind;
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String label() {
        if (!isConfigured()) return "Proxy (not configured)";
        return kind.scheme().toUpperCase(Locale.ROOT) + " " + host + ":" + port;
    }

    @Override
    public Proxy proxy() {
        if (!isConfigured()) return Proxy.NO_PROXY;
        Proxy.Type type = kind == Kind.SOCKS ? Proxy.Type.SOCKS : Proxy.Type.HTTP;
        return new Proxy(type, InetSocketAddress.createUnresolved(host, port));
    }

    @Override
    public boolean failClosed() {
        // A proxy the user asked for is a requirement, not a preference.
        return true;
    }

    @Override
    public boolean isAvailable() {
        return isConfigured();
    }

    @Override
    public void refresh() {
        // Nothing cached.
    }

    @Override
    public String webViewProxyRule() {
        if (!isConfigured()) return null;
        return kind.scheme() + "://" + host + ":" + port;
    }
}
