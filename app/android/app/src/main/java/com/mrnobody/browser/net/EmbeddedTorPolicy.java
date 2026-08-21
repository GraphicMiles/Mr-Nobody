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

    /** How often the waiter re-probes the SOCKS port while waiting. */
    public static final long PROBE_INTERVAL_MS = 400L;

    /**
     * The background waiter's full bootstrap budget (the auto-apply UX).
     * Device evidence (2026-08-21, 4G): 90s was not enough for a first
     * bootstrap — the consensus download on mobile data can take minutes.
     * The waiter is asynchronous, so a generous ceiling costs nothing.
     */
    public static final long BOOTSTRAP_WAIT_MS = 180_000L;

    /**
     * The absolute ceiling on waiting for a bootstrap that keeps reporting
     * STARTING. Device runs 4 and 5 proved fixed deadlines are guesses: the
     * waiter refused at 180s and Tor reached ON moments later. While Tor is
     * actively bootstrapping the waiter now keeps waiting; this cap only
     * ends a bootstrap that will clearly never finish.
     */
    public static final long BOOTSTRAP_HARD_CAP_MS = 600_000L;

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

    /** The refusal when Tor was still bootstrapping at the hard cap. */
    public static String slowNetworkMessage() {
        return "Tor has been trying to connect for 10 minutes on this network "
                + "and still has no circuit. Nothing was sent over your normal "
                + "connection. Try again on another network, or keep Nobody "
                + "off for now.";
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
