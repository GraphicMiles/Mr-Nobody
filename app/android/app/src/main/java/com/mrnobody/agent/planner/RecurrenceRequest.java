package com.mrnobody.agent.planner;

import com.mrnobody.agent.tasks.Schedule;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recognises when an instruction asks for something to be watched over time.
 *
 * <p>"Track the Bitcoin price" was answered once, with a figure, and finished.
 * That is not tracking — it is a reading. The task went to COMPLETED, the
 * schedule machinery that already exists was never involved, and the number
 * the user was given began going stale the moment they read it.
 *
 * <p>Everything needed to do this properly was already built and idle:
 * {@link Schedule} clamps to WorkManager's floor, {@code TaskStore} persists
 * {@code repeat_every}, and {@code scheduleRepeating} enqueues periodic work.
 * The one missing piece was noticing that the user had asked for it. This is
 * that piece, and it is deliberately a small pure function rather than a
 * model call: whether a task repeats decides whether the device wakes up on a
 * timer, and that should not depend on a language model's mood.
 *
 * <p><b>Conservative on purpose.</b> A false positive schedules background
 * work nobody asked for and spends battery; a false negative gives a one-off
 * answer, which is what happens today and is merely disappointing. So an
 * explicit interval always wins, a tracking verb yields a deliberately coarse
 * default, and anything ambiguous stays a single run.
 */
public final class RecurrenceRequest {

    /**
     * Verbs that mean "keep looking at this", as distinct from "tell me".
     *
     * <p>"Watch" is included and "follow" is not: "follow the link" is an
     * action on a page, and reading it as a recurring request would schedule
     * work for an instruction that has nothing to do with time.
     */
    private static final String[] TRACKING_VERBS = {
            "track", "monitor", "keep an eye on", "watch for", "watch the",
            "alert me", "notify me", "let me know when", "tell me when",
            "keep checking", "check periodically",
    };

    /** "every 30 minutes", "each 2 hours", "every day". */
    private static final Pattern EXPLICIT_INTERVAL = Pattern.compile(
            "\\b(?:every|each)\\s+(?:(\\d{1,3})\\s*)?"
                    + "(minute|minutes|min|mins|hour|hours|hr|hrs|day|days|week|weeks)\\b",
            Pattern.CASE_INSENSITIVE);

    /** "hourly", "daily", "weekly". */
    private static final Pattern ADVERB_INTERVAL = Pattern.compile(
            "\\b(hourly|daily|weekly)\\b", Pattern.CASE_INSENSITIVE);

    /**
     * The default for a bare tracking verb.
     *
     * <p>Hourly, not every fifteen minutes. A user who says "track" without a
     * number has expressed no urgency, and the cost of guessing too fast is
     * paid by their battery every hour of every day. They can ask for faster;
     * they cannot easily discover why the phone is warm.
     */
    private static final Schedule.Repeat DEFAULT_TRACKING = Schedule.Repeat.HOURLY;

    /** What was asked for, and how to say so. */
    public static final class Request {
        public final Schedule.Repeat repeat;
        /** True when the user named an interval rather than us assuming one. */
        public final boolean explicit;

        Request(Schedule.Repeat repeat, boolean explicit) {
            this.repeat = repeat;
            this.explicit = explicit;
        }

        public boolean isRecurring() {
            return repeat != null && repeat.isRecurring();
        }

        /**
         * A line telling the user what was set up.
         *
         * <p>Says it plainly, including when the interval was assumed and when
         * it was rounded. Background work the user did not knowingly agree to
         * is the kind of thing that gets an app uninstalled.
         */
        public String describe() {
            if (!isRecurring()) return "";
            String base = "Checking " + repeat.label().toLowerCase(Locale.ROOT)
                    + " from now on.";
            return explicit
                    ? base + " Say \"stop tracking\" to end it."
                    : base + " No interval was given, so this is the default —"
                            + " ask for a different one if you need it."
                            + " Say \"stop tracking\" to end it.";
        }
    }

    private static final Request ONCE = new Request(Schedule.Repeat.NEVER, false);

    private RecurrenceRequest() {
    }

    /** Whether {@code instruction} asks for repeated work, and how often. */
    public static Request parse(String instruction) {
        if (instruction == null || instruction.trim().isEmpty()) return ONCE;
        String text = instruction.toLowerCase(Locale.ROOT);

        // An explicit interval is honoured whether or not a tracking verb is
        // present: "check the price every hour" is unambiguous.
        Matcher m = EXPLICIT_INTERVAL.matcher(text);
        if (m.find()) {
            int count = m.group(1) == null ? 1 : parseCount(m.group(1));
            Schedule.Repeat repeat = fromUnit(m.group(2), count);
            if (repeat.isRecurring()) return new Request(repeat, true);
        }

        Matcher adverb = ADVERB_INTERVAL.matcher(text);
        if (adverb.find()) {
            switch (adverb.group(1).toLowerCase(Locale.ROOT)) {
                case "hourly": return new Request(Schedule.Repeat.HOURLY, true);
                case "daily":  return new Request(Schedule.Repeat.DAILY, true);
                case "weekly": return new Request(Schedule.Repeat.WEEKLY, true);
                default: break;
            }
        }

        for (String verb : TRACKING_VERBS) {
            if (text.contains(verb)) return new Request(DEFAULT_TRACKING, false);
        }
        return ONCE;
    }

    /** Whether the user is asking to stop an existing schedule. */
    public static boolean isStopRequest(String instruction) {
        if (instruction == null) return false;
        String text = instruction.toLowerCase(Locale.ROOT);
        return text.contains("stop tracking")
                || text.contains("stop monitoring")
                || text.contains("stop watching")
                || text.contains("stop checking");
    }

    private static int parseCount(String digits) {
        try {
            int n = Integer.parseInt(digits);
            return n < 1 ? 1 : n;
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /**
     * Map a unit and a count onto the coarsest schedule that is not faster
     * than asked.
     *
     * <p>The buckets are coarse because Android's are: WorkManager will not go
     * below fifteen minutes and coalesces wakeups regardless, so offering
     * "every 5 minutes" would be a promise the platform does not keep.
     * Rounding <em>down</em> in frequency — 90 minutes becomes six-hourly
     * rather than hourly — would silently do less than asked, so the nearest
     * bucket at or above the requested rate is chosen instead.
     */
    private static Schedule.Repeat fromUnit(String unit, int count) {
        String u = unit.toLowerCase(Locale.ROOT);
        long minutes;
        if (u.startsWith("min")) {
            minutes = count;
        } else if (u.startsWith("h")) {
            minutes = count * 60L;
        } else if (u.startsWith("d")) {
            minutes = count * 60L * 24L;
        } else if (u.startsWith("w")) {
            minutes = count * 60L * 24L * 7L;
        } else {
            return Schedule.Repeat.NEVER;
        }

        if (minutes <= 60) return Schedule.Repeat.HOURLY;
        if (minutes <= 6 * 60) return Schedule.Repeat.SIX_HOURLY;
        if (minutes <= 24 * 60) return Schedule.Repeat.DAILY;
        return Schedule.Repeat.WEEKLY;
    }
}
