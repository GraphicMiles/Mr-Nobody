package com.mrnobody.agent.tasks;

import java.util.Locale;

/**
 * When a task should run again.
 *
 * <p>{@link TaskScheduler} only ever offered one-shot {@code schedule} and
 * {@code cancel}, so "check this every morning" had nowhere to live — the
 * interface itself had to widen, not just its implementation.
 *
 * <p>Deliberately not cron. A phone is not a server: Android will coalesce and
 * defer background work regardless of what we ask for, so minute-level
 * precision would be a promise the platform does not keep. Coarse intervals
 * describe what can actually be delivered.
 *
 * <p>Pure arithmetic so the next-run decision is testable without waiting for
 * a day to pass.
 */
public final class Schedule {

    /** WorkManager's own floor for periodic work. Anything under it is ignored. */
    public static final long MIN_INTERVAL_MS = 15 * 60 * 1000L;

    public enum Repeat {
        NEVER("Once", 0L),
        HOURLY("Every hour", 60 * 60 * 1000L),
        SIX_HOURLY("Every 6 hours", 6 * 60 * 60 * 1000L),
        DAILY("Every day", 24 * 60 * 60 * 1000L),
        WEEKLY("Every week", 7 * 24 * 60 * 60 * 1000L);

        private final String label;
        private final long intervalMs;

        Repeat(String label, long intervalMs) {
            this.label = label;
            this.intervalMs = intervalMs;
        }

        public String label() {
            return label;
        }

        public long intervalMs() {
            return intervalMs;
        }

        public boolean isRecurring() {
            return intervalMs > 0;
        }

        public static Repeat fromName(String name) {
            if (name != null) {
                for (Repeat r : values()) {
                    if (r.name().equalsIgnoreCase(name.trim())) return r;
                }
            }
            return NEVER;
        }
    }

    private final Repeat repeat;
    private final long firstRunAt;

    public Schedule(Repeat repeat, long firstRunAt) {
        this.repeat = repeat == null ? Repeat.NEVER : repeat;
        this.firstRunAt = firstRunAt;
    }

    public static Schedule once() {
        return new Schedule(Repeat.NEVER, 0L);
    }

    public Repeat repeat() {
        return repeat;
    }

    public long firstRunAt() {
        return firstRunAt;
    }

    public boolean isRecurring() {
        return repeat.isRecurring();
    }

    /**
     * The effective interval, raised to the platform floor.
     *
     * <p>Asking for less than WorkManager will honour produces a schedule that
     * silently does not do what it says, so the value is clamped here where it
     * can be seen rather than absorbed by the platform.
     */
    public long effectiveIntervalMs() {
        if (!repeat.isRecurring()) return 0L;
        return Math.max(repeat.intervalMs(), MIN_INTERVAL_MS);
    }

    /**
     * When this should next run, or 0 for never again.
     *
     * @param lastRunAt 0 if it has never run
     */
    public long nextRunAt(long lastRunAt, long now) {
        if (!repeat.isRecurring()) {
            // A one-shot that has already run is done.
            return lastRunAt > 0 ? 0L : Math.max(firstRunAt, now);
        }
        if (lastRunAt <= 0) {
            return firstRunAt > now ? firstRunAt : now;
        }

        long next = lastRunAt + effectiveIntervalMs();

        // A phone that was off for a week should run once on waking, not
        // catch up on seven missed runs.
        return Math.max(next, now);
    }

    public boolean isDue(long lastRunAt, long now) {
        long next = nextRunAt(lastRunAt, now);
        return next > 0 && next <= now;
    }

    public String describe() {
        return repeat.isRecurring()
                ? repeat.label().toLowerCase(Locale.ROOT)
                : "once";
    }
}
