package com.mrnobody.browser.net;

/**
 * The decisions around the bundled Tor, separated from the Android calls so
 * they can be tested on a JVM.
 *
 * <p>The rules, in priority order:
 * <ol>
 *   <li><b>Orbot wins.</b> If anything is already listening on the SOCKS
 *       port, it is the user's own Tor and the bundled one must not race
 *       it.</li>
 *   <li><b>Bundled Tor is a fallback, not a default.</b> It starts only when
 *       the user chose the Tor route and Orbot is absent.</li>
 *   <li><b>Fail closed, explain honestly.</b> A Tor that is still
 *       bootstrapping is a refusal with a "try again shortly", never a quiet
 *       downgrade to a direct connection.</li>
 * </ol>
 */
public final class EmbeddedTorPolicy {

    /**
     * How long {@code PrivacyController.apply} may block waiting for the
     * bundled Tor. Applying a mode runs on the platform channel thread, so
     * this must stay well under the ANR threshold; a first bootstrap that
     * needs longer keeps running in the background and the user's retry
     * finds it up.
     */
    public static final long APPLY_WAIT_MS = 2_500L;

    /** How often the waiter re-probes the SOCKS port while waiting. */
    public static final long PROBE_INTERVAL_MS = 400L;

    private EmbeddedTorPolicy() {
    }

    /** Should the bundled Tor be started? (Rule 1 and 2.) */
    public static boolean shouldStart(boolean torRouteChosen, boolean socksAlreadyListening,
                                      boolean bundled) {
        return torRouteChosen && !socksAlreadyListening && bundled;
    }

    /** True when a TorService status extra means circuits are up. */
    public static boolean statusMeansReady(String status) {
        return "ON".equals(status);
    }

    /** True when a TorService status extra means it gave up. */
    public static boolean statusMeansStopped(String status) {
        return "OFF".equals(status) || "STOPPING".equals(status);
    }

    /** The user-facing refusal while the bundled Tor is still bootstrapping. */
    public static String stillStartingMessage() {
        return "Built-in Tor is starting — the first bootstrap can take a minute "
                + "on mobile data. Try Nobody again in a moment.";
    }

    /** The user-facing refusal when no Tor of any kind is available. */
    public static String unavailableMessage(boolean bundled) {
        return bundled
                ? "Tor could not be started on this device. Nothing was sent "
                        + "over your normal connection."
                : "Nobody needs Tor. Install Orbot, or use a build of Mr Nobody "
                        + "with built-in Tor.";
    }
}
