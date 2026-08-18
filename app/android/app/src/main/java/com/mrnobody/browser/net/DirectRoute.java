package com.mrnobody.browser.net;

import java.net.Proxy;

/**
 * No proxy. The ordinary connection, and the default.
 *
 * <p>Always available and never fail-closed: there is nothing here that can
 * break, and refusing to connect would just mean refusing to work.
 */
public final class DirectRoute implements NetworkRoute {

    public static final String ID = "direct";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String label() {
        return "Direct";
    }

    @Override
    public Proxy proxy() {
        return Proxy.NO_PROXY;
    }

    @Override
    public boolean failClosed() {
        return false;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public void refresh() {
        // Nothing to probe.
    }

    @Override
    public String webViewProxyRule() {
        return null;
    }
}
