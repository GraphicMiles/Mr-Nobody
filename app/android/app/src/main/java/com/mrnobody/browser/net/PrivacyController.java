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
        /**
         * True while the bundled Tor is still bootstrapping: the mode is not
         * applied yet (fail-closed), but it will apply itself the moment the
         * first circuit is up. The UI shows progress instead of a refusal.
         */
        public final boolean pending;

        Result(PrivacyMode requested, PrivacyMode effective,
               boolean routeApplied, boolean engineProxied, String problem) {
            this(requested, effective, routeApplied, engineProxied, problem, false);
        }

        Result(PrivacyMode requested, PrivacyMode effective,
               boolean routeApplied, boolean engineProxied, String problem,
               boolean pending) {
            this.requested = requested;
            this.effective = effective;
            this.routeApplied = routeApplied;
            this.engineProxied = engineProxied;
            this.problem = problem;
            this.pending = pending;
        }

        /** True when the mode the user asked for is the mode they got. */
        public boolean isFullyApplied() {
            return requested == effective && problem == null;
        }
    }

    private static volatile PrivacyMode current = PrivacyMode.NORMAL;

    // ---- bundled-Tor auto-apply state (UX rule: never demand a manual retry) ----

    /** True while a background waiter is holding a NOBODY request open. */
    private static volatile boolean torPending;

    /** Why the last pending NOBODY did not apply, until the UI collects it. */
    private static volatile String pendingProblem;

    /**
     * Every public apply() invalidates outstanding waiters: a user who
     * switched to NORMAL mid-bootstrap must not be yanked into NOBODY later.
     */
    private static final java.util.concurrent.atomic.AtomicLong PENDING_GENERATION =
            new java.util.concurrent.atomic.AtomicLong();

    /** For the UI's status poll. */
    public static boolean isTorPending() {
        return torPending;
    }

    /** The last async failure, delivered once, then cleared. */
    public static String consumePendingProblem() {
        String p = pendingProblem;
        pendingProblem = null;
        return p;
    }

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
        return apply(mode, settings, null);
    }

    /**
     * Apply {@code mode}, with a Context so the bundled Tor can be started
     * when the Tor route is chosen and Orbot is absent. The context-less
     * overload behaves exactly as before: Orbot or nothing.
     */
    public static Result apply(PrivacyMode mode, Settings settings, android.content.Context context) {
        // A fresh user decision cancels any waiter from an earlier one: whoever
        // holds an older generation must not apply a stale NOBODY later.
        long generation = PENDING_GENERATION.incrementAndGet();
        torPending = false;
        return applyInternal(mode, settings, context, true, generation);
    }

    private static Result applyInternal(PrivacyMode mode, Settings settings,
                                        android.content.Context context,
                                        boolean allowTorWait, long generation) {
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

        // Orbot takes priority: if the SOCKS port is already served by a
        // WORKING Tor, that is the user's own. But TorService binds the port
        // before its first circuit, so a listener while our bundled Tor says
        // STARTING is ours-and-not-ready — a device test proved the port
        // probe alone applies Nobody against a Tor that cannot carry traffic.
        boolean portUp = route.isAvailable();
        boolean oursStarting = route instanceof OrbotTorRoute && EmbeddedTor.isStarting();
        if (route instanceof OrbotTorRoute && context != null
                && (!portUp || oursStarting)
                && EmbeddedTorPolicy.shouldStart(true, portUp && !oursStarting,
                        EmbeddedTor.isBundled())) {
            boolean up = EmbeddedTor.startAndAwait(context, EmbeddedTorPolicy.APPLY_WAIT_MS);
            route.refresh();
            if (!up) {
                if (!allowTorWait) {
                    // The background waiter already spent the full bootstrap
                    // budget; this is a real failure, not a "try again".
                    return refuse(mode, EmbeddedTorPolicy.unavailableMessage(true), settings);
                }
                // Auto-apply UX: report pending, keep waiting off the UI
                // thread, and apply the mode ourselves at the first circuit.
                // Fail-closed meanwhile — nothing changed yet.
                awaitTorThenApply(mode, settings, context, generation);
                return new Result(mode, current, false, false, null, true);
            }
        }

        // Final readiness gate: the port must answer AND, when the listener
        // is our own bundled Tor, its status must be past bootstrap.
        if (route instanceof OrbotTorRoute && EmbeddedTor.isStarting()) {
            return refuse(mode, EmbeddedTorPolicy.unavailableMessage(true), settings);
        }

        if (!route.isAvailable()) {
            // Fail closed at the point of entry, rather than letting the user
            // browse and discover it later.
            return refuse(mode, route.label() + " is not reachable. "
                    + (route instanceof OrbotTorRoute
                        ? EmbeddedTorPolicy.unavailableMessage(EmbeddedTor.isBundled())
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
     * The auto-apply waiter: wait out the bootstrap on a
     * background thread, then re-run the apply on the main thread. A newer
     * user decision (any public {@code apply}) advances the generation and
     * turns this waiter into a no-op — a user who chose NORMAL mid-bootstrap
     * stays in NORMAL.
     */
    private static void awaitTorThenApply(PrivacyMode mode, Settings settings,
                                          android.content.Context context, long generation) {
        torPending = true;
        pendingProblem = null;
        Thread waiter = new Thread(() -> {
            boolean up = EmbeddedTor.startAndAwait(context,
                    EmbeddedTorPolicy.BOOTSTRAP_WAIT_MS);
            if (generation != PENDING_GENERATION.get()) return; // superseded
            android.os.Handler main =
                    new android.os.Handler(android.os.Looper.getMainLooper());
            main.post(() -> {
                if (generation != PENDING_GENERATION.get()) return;
                torPending = false;
                Result result = applyInternal(mode, settings, context, false, generation);
                // Persist what was achieved, exactly as the toggle path does.
                try {
                    if (settings != null) settings.setPrivacyMode(result.effective.name());
                } catch (Throwable ignored) {
                    // Persistence is best-effort; the live state is applied.
                }
                if (!result.isFullyApplied()) {
                    pendingProblem = result.problem != null
                            ? result.problem
                            : EmbeddedTorPolicy.unavailableMessage(true);
                }
            });
        }, "embedded-tor-wait");
        waiter.setDaemon(true);
        waiter.start();
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
