package com.mrnobody.browser.net;

import com.mrnobody.browser.core.Settings;
import com.mrnobody.debug.ErrorLog;

/**
 * Turns a {@link PrivacyMode} into an applied state, and reports honestly
 * about what was actually achieved.
 *
 * <p>Its own class because applying a mode is three separate mechanisms —
 * the socket gate, the WebView proxy, and profile isolation — each of which
 * can fail independently on a given device. Scattering that across callers is
 * how a UI ends up claiming protection that was never installed.
 *
 * <p>The rule this enforces: <b>a mode either applies fully or is refused.</b>
 * If NOBODY cannot proxy the engine, we do not enter NOBODY with the browser
 * running in the clear.
 */
public final class PrivacyController {

    /** What actually happened when a mode was applied. */
    public static final class Result {
        public final PrivacyMode requested;
        public final PrivacyMode effective;
        public final boolean routeApplied;
        public final boolean engineProxied;
        public final String problem;

        Result(PrivacyMode requested, PrivacyMode effective,
               boolean routeApplied, boolean engineProxied, String problem) {
            this.requested = requested;
            this.effective = effective;
            this.routeApplied = routeApplied;
            this.engineProxied = engineProxied;
            this.problem = problem;
        }

        /** True when the mode the user asked for is the mode they got. */
        public boolean isFullyApplied() {
            return requested == effective && problem == null;
        }
    }

    private static volatile PrivacyMode current = PrivacyMode.NORMAL;

    private PrivacyController() {
    }

    public static PrivacyMode current() {
        return current;
    }

    /** Build the route the user configured, without applying it. */
    public static NetworkRoute configuredRoute(Settings settings) {
        String id = settings == null ? OrbotTorRoute.ID : settings.routeId();
        if (ProxyRoute.ID.equals(id)) {
            return new ProxyRoute(
                    ProxyRoute.Kind.fromName(settings.proxyKind()),
                    settings.proxyHost(),
                    settings.proxyPort());
        }
        return new OrbotTorRoute();
    }

    /**
     * Apply {@code mode}.
     *
     * <p>NORMAL and PRIVATE always succeed: they make no network claim, so
     * there is nothing that can fail to be true. NOBODY is refused unless both
     * the route is up and the engine could be pointed at it.
     */
    public static Result apply(PrivacyMode mode, Settings settings) {
        if (mode == null) mode = PrivacyMode.NORMAL;

        if (!mode.needsPrivacyRoute()) {
            NetworkGate.setRoute(new DirectRoute());
            WebViewRouter.clear();
            current = mode;
            return new Result(mode, mode, true, true, null);
        }

        NetworkRoute route = configuredRoute(settings);
        route.refresh();

        if (!route.isAvailable()) {
            // Fail closed at the point of entry, rather than letting the user
            // browse and discover it later.
            return refuse(mode, route.label() + " is not reachable. "
                    + (route instanceof OrbotTorRoute
                        ? "Start Orbot and try again."
                        : "Check the proxy settings."));
        }

        // Order matters: gate first. If applying to the engine fails we roll
        // back, and a moment of over-restriction is safe where the reverse is
        // not.
        NetworkGate.setRoute(route);

        if (!WebViewRouter.apply(route)) {
            NetworkGate.setRoute(new DirectRoute());
            return refuse(mode, "This device's WebView cannot route through a proxy, "
                    + "so browsing could not be protected. Nothing was changed.");
        }

        current = mode;
        return new Result(mode, mode, true, true, null);
    }

    private static Result refuse(PrivacyMode requested, String problem) {
        ErrorLog.record("privacy mode " + requested + " refused: " + problem);
        NetworkGate.setRoute(new DirectRoute());
        WebViewRouter.clear();
        current = PrivacyMode.NORMAL;
        return new Result(requested, PrivacyMode.NORMAL, false, false, problem);
    }

    /**
     * Re-check a live NOBODY session.
     *
     * <p>Orbot can stop while the user is browsing. The socket gate already
     * fails closed on its own, but the engine's proxy override would keep
     * pointing at a dead port, so this drops the mode and tells the caller.
     *
     * @return null while healthy, or a message to show the user
     */
    public static String revalidate(Settings settings) {
        if (!current.needsPrivacyRoute()) return null;
        NetworkRoute route = NetworkGate.route();
        route.refresh();
        if (route.isAvailable()) return null;
        String label = route.label();
        refuse(current, label + " stopped responding.");
        return label + " stopped responding, so protected browsing was turned off. "
                + "Nothing was sent over your normal connection.";
    }

    /** Diagnostics for the privacy dashboard: what this device can actually do. */
    public static String capabilities() {
        return "profiles=" + ProfileManager.isSupported()
                + " proxy=" + WebViewRouter.isSupported()
                + " fingerprint=" + FingerprintDefence.isSupported();
    }
}
