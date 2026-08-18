package com.mrnobody.agent.tasks;

/**
 * Whether a running task is still alive.
 *
 * <p>{@link TaskReconciler} already parks tasks that were RUNNING when the
 * process died, using the time since the row was last written. That works
 * because a task writes on every step — and it fails exactly where it matters
 * most: a task stuck inside one long step writes nothing, so it looks
 * identical to a task that died, and a task that died during a slow step looks
 * alive until the stale window expires.
 *
 * <p>A heartbeat separates the two. The worker touches it far more often than
 * a step boundary, so silence means the worker is gone rather than merely
 * busy, and the stale window can be short without killing slow-but-healthy
 * work.
 *
 * <p>Pure arithmetic, no clock of its own, so the decision is testable without
 * waiting.
 */
public final class Heartbeat {

    /** How often a running worker should beat. */
    public static final long INTERVAL_MS = 10_000L;

    /**
     * Missed beats before a task is presumed dead.
     *
     * <p>Three rather than one: a phone that suspends an app for a few seconds
     * is ordinary, and declaring a healthy task dead loses real work. The cost
     * of waiting is a Resume button appearing slightly later.
     */
    public static final int MISSES_ALLOWED = 3;

    /** Silence after which a task is presumed dead. */
    public static final long DEAD_AFTER_MS = INTERVAL_MS * MISSES_ALLOWED;

    private Heartbeat() {
    }

    /**
     * True when a task that claims to be running has gone quiet for too long.
     *
     * @param lastBeatAt when the worker last reported, 0 if never
     */
    public static boolean isDead(long lastBeatAt, long now) {
        return isDead(lastBeatAt, now, DEAD_AFTER_MS);
    }

    public static boolean isDead(long lastBeatAt, long now, long deadAfterMs) {
        // Never beaten: not evidence of death. A task that has only just been
        // picked up has not had the chance, and killing it would be a race
        // rather than a recovery.
        if (lastBeatAt <= 0) return false;

        long silence = now - lastBeatAt;

        // A clock that moved backwards means the answer is unknown, and
        // "unknown" must not read as "dead" or a correction to system time
        // would kill every running task at once.
        if (silence < 0) return false;

        return silence > deadAfterMs;
    }

    /** True when a worker should send another beat. */
    public static boolean isDue(long lastBeatAt, long now) {
        if (lastBeatAt <= 0) return true;
        long since = now - lastBeatAt;
        return since < 0 || since >= INTERVAL_MS;
    }

    /** What to tell the user about a task recovered this way. */
    public static String recoveredReason() {
        return "This task stopped responding and was interrupted. "
                + "Nothing was left half-finished that we can see — you can run it again.";
    }
}
