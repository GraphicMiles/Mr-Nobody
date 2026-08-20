package com.mrnobody.agent.planner;

import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Time, date and day questions are answered from the device clock — no
 * search, no fetch, no network at all.
 *
 * <p>Device evidence made the case: "whats the time" ran a five-page,
 * 53-second research task to learn something the phone already knew. This is
 * the owner's rule 3 — the cheapest sufficient action for a clock question is
 * reading the clock.
 *
 * <p>Conservative on purpose. Only instructions that <em>are</em> a clock
 * question match; an instruction that merely contains time-ish words ("screen
 * time settings", "how old is messi") falls through to research. A question
 * naming a place ("what time is it in london") answers from the device clock
 * shifted to that zone when the place resolves to an IANA zone, and falls
 * through to research when it does not — a wrong timezone stated confidently
 * is worse than a search.
 */
public final class ClockSkill {

    private enum Kind { TIME, TODAY }

    /** Full normalized phrases that mean "what time is it". */
    private static final Set<String> TIME_PHRASES = new HashSet<>(Arrays.asList(
            "time", "the time", "current time", "time now", "the time now",
            "what time is it", "whats the time", "what is the time",
            "what time it is", "tell me the time", "what is time now",
            "whats time", "what hour is it"));

    /** Full normalized phrases that mean "what is today's date / day". */
    private static final Set<String> TODAY_PHRASES = new HashSet<>(Arrays.asList(
            "date", "the date", "todays date", "current date", "date today",
            "the date today", "what is the date", "whats the date",
            "what date is it", "what is todays date", "whats todays date",
            "what is the date today", "what date is it today", "what date is today",
            "what day is it", "what day is today", "what day is it today",
            "which day is it", "which day is today", "what day of the week is it",
            "day of the week", "what day of the week is today", "whats today",
            "what is today"));

    /** Trailing filler that never changes the question. */
    private static final Set<String> TRAILING_FILLER = new HashSet<>(Arrays.asList(
            "please", "now", "right now", "currently", "over there"));

    /** Leading filler that never changes the question. */
    private static final String[] LEADING_FILLER = {
            "hey", "hi", "please", "can you tell me", "could you tell me",
            "tell me", "can you", "could you"};

    private static final DateTimeFormatter TIME_24 =
            DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);
    private static final DateTimeFormatter TIME_12 =
            DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH);
    private static final DateTimeFormatter DAY_DATE =
            DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.ENGLISH);

    private ClockSkill() {
    }

    /** The answer for a clock question, or null when this is not one. */
    public static String answer(String instruction) {
        return answer(instruction, Clock.systemDefaultZone());
    }

    /** Injectable-clock variant — how the skill is unit-tested. */
    static String answer(String instruction, Clock clock) {
        if (instruction == null || clock == null) return null;
        String normalized = normalize(instruction);
        if (normalized.isEmpty() || normalized.length() > 80) return null;

        String place = placeOf(normalized);
        String base = stripFiller(place.isEmpty()
                ? normalized
                : normalized.substring(0, normalized.length() - place.length() - 4).trim());

        Kind kind = kindOf(base);
        if (kind == null) return null;

        ZoneId zone = place.isEmpty() ? clock.getZone() : resolveZone(place);
        if (zone == null) return null;

        ZonedDateTime now = ZonedDateTime.now(clock).withZoneSameInstant(zone);
        String suffix = " Read from this device's clock; no network was used.";
        if (kind == Kind.TIME) {
            return "It is " + TIME_24.format(now) + " ("
                    + TIME_12.format(now).toLowerCase(Locale.ENGLISH) + ") on "
                    + DAY_DATE.format(now) + " — " + zone.getId() + "." + suffix;
        }
        return "Today is " + DAY_DATE.format(now) + " — " + zone.getId() + "." + suffix;
    }

    /** Lower-case, apostrophes removed, punctuation to spaces, collapsed. */
    static String normalize(String instruction) {
        String s = instruction.toLowerCase(Locale.ROOT).replace("'", "").replace("’", "");
        s = s.replaceAll("[^a-z0-9]+", " ").trim().replaceAll("\\s+", " ");
        return s;
    }

    private static Kind kindOf(String base) {
        if (TIME_PHRASES.contains(base)) return Kind.TIME;
        if (TODAY_PHRASES.contains(base)) return Kind.TODAY;
        return null;
    }

    /** The place named by a trailing "in <place>", or empty. */
    static String placeOf(String normalized) {
        int at = normalized.lastIndexOf(" in ");
        if (at < 0) return "";
        String place = normalized.substring(at + 4).trim();
        // "what time is it in 30 minutes" is arithmetic, not a place.
        if (place.isEmpty() || place.matches(".*\\d.*")) return "";
        return place;
    }

    private static String stripFiller(String base) {
        String s = base;
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String lead : LEADING_FILLER) {
                if (s.startsWith(lead + " ")) {
                    s = s.substring(lead.length() + 1).trim();
                    changed = true;
                }
            }
            for (String tail : TRAILING_FILLER) {
                if (s.endsWith(" " + tail)) {
                    s = s.substring(0, s.length() - tail.length() - 1).trim();
                    changed = true;
                }
            }
        }
        return s;
    }

    /**
     * The IANA zone for a spoken place name, or null when unknown. Matching
     * is against the city segment of every available zone id ("New_York" →
     * "new york"), sorted for determinism, plus a few spoken aliases.
     */
    static ZoneId resolveZone(String place) {
        String p = place.trim();
        if (p.equals("utc")) return ZoneId.of("UTC");
        if (p.equals("gmt")) return ZoneId.of("GMT");
        for (String id : new TreeSet<>(ZoneId.getAvailableZoneIds())) {
            int slash = id.lastIndexOf('/');
            if (slash < 0) continue;
            String city = id.substring(slash + 1).replace('_', ' ')
                    .toLowerCase(Locale.ROOT);
            if (city.equals(p)) return ZoneId.of(id);
        }
        return null;
    }
}
