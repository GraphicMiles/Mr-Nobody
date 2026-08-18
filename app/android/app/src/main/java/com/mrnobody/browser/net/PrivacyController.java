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
        if (settings == null) return new OrbotTorRoute();
        return routeFor(settings.routeId(), settings.proxyKind(),
                settings.proxyHost(), settings.proxyPort());
    }

    /**
     * Pick a route from persisted ids. Package-visible so a test can pin
     * "direct means direct" without standing up SharedPreferences.
     */
    static NetworkRoute routeFor(String id, String kind, String host, int port) {
        if (id != null && DirectRoute.ID.equalsIgnoreCase(id.trim())) {
            return new DirectRoute();
        }
        if (id != null && ProxyRoute.ID.equalsIgnoreCase(id.trim())) {
            return new ProxyRoute(ProxyRoute.Kind.fromName(kind), host, port);
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
            restoreFingerprint(settings);
            current = mode;
            return new Result(mode, mode, true, true, null);
        }

        NetworkRoute route = configuredRoute(settings);
        if (route instanceof DirectRoute) {
            return refuse(mode, "Nobody needs Orbot or a proxy. Pick one in Settings → Proxy.",
                    settings);
        }
        route.refresh();

        if (!route.isAvailable()) {
            // Fail closed at the point of entry, rather than letting the user
            // browse and discover it later.
            return refuse(mode, route.label() + " is not reachable. "
                    + (route instanceof OrbotTorRoute
                        ? "Start Orbot and try again."
                        : "Check the proxy settings."), settings);
        }

        // Order matters: gate first. If applying to the engine fails we roll
        // back, and a moment of over-restriction is safe where the reverse is
        // not.
        NetworkGate.setRoute(route);

        if (!WebViewRouter.apply(route)) {
            NetworkGate.setRoute(new DirectRoute());
            return refuse(mode, "This device's WebView cannot route through a proxy, "
                    + "so browsing could not be protected. Nothing was changed.",
                    settings);
        }

        // The mode's own description promises reduced identification. The
        // flag exists for this; leaving it unused meant Nobody hid the IP
        // and left the fingerprint patches off.
        enableFingerprintForNobody(settings);
        current = mode;
        return new Result(mode, mode, true, true, null);
    }

    /**
     * Turn fingerprint defence on for a live Nobody session, remembering the
     * previous value so leaving the mode does not leave a surprise toggle.
     */
    static void enableFingerprintForNobody(Settings settings) {
        if (settings == null) return;
        if (!settings.isFingerprintForcedByNobody()) {
            settings.setFingerprintBeforeNobody(settings.isFingerprintProtection());
            settings.setFingerprintForcedByNobody(true);
        }
        settings.setFingerprintProtection(true);
    }

    /** Undo a Nobody-forced fingerprint change. No-op if Nobody never forced it. */
    static void restoreFingerprint(Settings settings) {
        if (settings == null || !settings.isFingerprintForcedByNobody()) return;
        settings.setFingerprintProtection(settings.fingerprintBeforeNobody());
        settings.setFingerprintForcedByNobody(false);
    }

    private static Result refuse(PrivacyMode requested, String problem, Settings settings) {
        ErrorLog.record("privacy mode " + requested + " refused: " + problem);
        NetworkGate.setRoute(new DirectRoute());
        WebViewRouter.clear();
        restoreFingerprint(settings);
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
        refuse(current, label + " stopped responding.", settings);
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
