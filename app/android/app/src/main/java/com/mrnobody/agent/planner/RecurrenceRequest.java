package com.mrnobody.agent.planner;

import com.mrnobody.agent.tasks.Schedule;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts an explicit interval from an instruction.
 *
 * <p>Whether a task should repeat is an intent question and lives on
 * {@link IntentClassifier}. This class only reads structure: every 6 hours,
 * daily. Those are quantities and units — not a vocabulary of tracking verbs.
 */
public final class RecurrenceRequest {

    private static final Pattern EXPLICIT_INTERVAL = Pattern.compile(
            "\\b(?:every|each)\\s+(?:(\\d{1,3})\\s*)?"
                    + "(minute|minutes|min|mins|hour|hours|hr|hrs|day|days|week|weeks)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern ADVERB_INTERVAL = Pattern.compile(
            "\\b(hourly|daily|weekly)\\b", Pattern.CASE_INSENSITIVE);

    private static final Schedule.Repeat DEFAULT_TRACKING = Schedule.Repeat.HOURLY;

    public static final class Request {
        public final Schedule.Repeat repeat;
        public final boolean explicit;

        Request(Schedule.Repeat repeat, boolean explicit) {
            this.repeat = repeat;
            this.explicit = explicit;
        }

        public boolean isRecurring() {
            return repeat != null && repeat.isRecurring();
        }

        public String describe() {
            if (!isRecurring()) return "";
            String base = "Checking " + repeat.label().toLowerCase(Locale.ROOT)
                    + " from now on.";
            return explicit
                    ? base + " Say you want it stopped to end it."
                    : base + " No interval was given, so this is the default —"
                            + " ask for a different one if you need it."
                            + " Say you want it stopped to end it.";
        }
    }

    private static final Request ONCE = new Request(Schedule.Repeat.NEVER, false);

    private RecurrenceRequest() {
    }

    public static Request once() {
        return ONCE;
    }

    public static Request parse(String instruction) {
        if (instruction == null || instruction.trim().isEmpty()) return ONCE;
        String text = instruction.toLowerCase(Locale.ROOT);

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

        return ONCE;
    }

    public static Request forMonitor(String instruction) {
        Request named = parse(instruction);
        if (named.isRecurring()) return named;
        return new Request(DEFAULT_TRACKING, false);
    }

    private static int parseCount(String digits) {
        try {
            int n = Integer.parseInt(digits);
            return n < 1 ? 1 : n;
        } catch (NumberFormatException e) {
            return 1;
        }
    }

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
