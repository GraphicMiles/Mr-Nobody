package com.mrnobody.agent.core;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * What the agent is allowed to remember between tasks.
 *
 * <p>Long-term memory was deliberately the last item on the roadmap, because
 * it is the feature most able to turn a local-first browser into a profile of
 * its user. A browser that quietly accumulates "user searched for X, visited
 * Y, asked about Z" has rebuilt the thing this product exists to avoid — and
 * it would do it with the user's own data, on their own device, which makes it
 * feel harmless right up until the phone is lost or seized.
 *
 * <p>So memory is opt-in, and what may be kept is decided here rather than by
 * whatever a caller happens to pass. The rules are deliberately narrow:
 *
 * <ul>
 *   <li><b>Off by default.</b> History is already off by default; memory that
 *       defaulted on would be history under another name.
 *   <li><b>Nothing that looks like a secret.</b> Keys, tokens, passwords and
 *       card numbers are refused even when memory is on, because the user
 *       enabling memory was not consenting to that.
 *   <li><b>Bounded.</b> A fixed number of short entries, oldest dropped, so it
 *       cannot grow into a corpus.
 * </ul>
 *
 * <p>Pure policy, no storage, so what is refusable is testable on its own.
 */
public final class MemoryPolicy {

    /** How many entries are kept at most. */
    public static final int MAX_ENTRIES = 50;

    /** How long one entry may be. */
    public static final int MAX_ENTRY_CHARS = 240;

    /**
     * Shapes that must never be written down, whatever the setting.
     *
     * <p>Conservative and few: this is a floor under the user's decision, not
     * a general secret scanner, and a rule that fired constantly would push
     * people to turn memory off rather than making it safer.
     */
    private static final Pattern[] NEVER_REMEMBER = {
            Pattern.compile("\\b[A-Za-z0-9_-]{20,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\b"), // JWT
            // The body allows _ and - : real keys look like sk_live_abc..., and
            // a class of [A-Za-z0-9] only stopped at the first underscore, so
            // the most common shape of all was slipping through.
            Pattern.compile("\\b(sk|pk|api|key|token|secret|bearer)[-_ ]?[A-Za-z0-9_-]{16,}",
                    Pattern.CASE_INSENSITIVE),
            // Vendor shapes whose prefix is not a word like "key" or "token".
            // The list above could only ever catch credentials that announce
            // themselves; a GitHub token says "ghp_" and sailed straight
            // through it.
            Pattern.compile("\\b(?:gh[pousr]_|github_pat_)[A-Za-z0-9_]{20,}"),
            Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b"),                         // AWS access key
            // No trailing \\b and a range, not a fixed 35: the class ends in
            // - and _, and a word boundary after a non-word character never
            // matches. That is the exact bug that let sk_live_ through once.
            Pattern.compile("\\bAIza[A-Za-z0-9_-]{30,}"),                      // Google API key
            Pattern.compile("\\bxox[baprs]-[A-Za-z0-9-]{10,}"),                // Slack
            // Generic backstop: a short prefix, an underscore, then a long
            // run that mixes in at least one digit. The digit requirement is
            // what separates a credential from an ordinary long_identifier,
            // so this stays a floor rather than a nuisance that gets memory
            // switched off entirely.
            Pattern.compile("\\b[A-Za-z]{2,12}_(?=[A-Za-z0-9]*\\d)[A-Za-z0-9]{16,}\\b"),
            Pattern.compile("\\b(?:\\d[ -]?){13,19}\\b"),                       // card-like
            Pattern.compile("\\bpassword\\s*[:=]\\s*\\S+", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b"), // email
    };

    /** The outcome of asking whether something may be remembered. */
    public static final class Verdict {
        public final boolean allowed;
        public final String reason;
        public final String value;

        Verdict(boolean allowed, String reason, String value) {
            this.allowed = allowed;
            this.reason = reason;
            this.value = value;
        }
    }

    private MemoryPolicy() {
    }

    /**
     * Decide whether {@code text} may be kept.
     *
     * @param enabled whether the user has turned memory on at all
     */
    public static Verdict consider(String text, boolean enabled) {
        if (!enabled) {
            return new Verdict(false, "memory is off", null);
        }
        if (text == null || text.trim().isEmpty()) {
            return new Verdict(false, "nothing to remember", null);
        }

        String value = text.trim().replaceAll("\\s+", " ");

        for (Pattern p : NEVER_REMEMBER) {
            if (p.matcher(value).find()) {
                // Named without quoting it back: an error message that echoes
                // the secret has written it somewhere too.
                return new Verdict(false,
                        "this looks like a credential or personal detail", null);
            }
        }

        if (value.length() > MAX_ENTRY_CHARS) {
            value = value.substring(0, MAX_ENTRY_CHARS);
        }
        return new Verdict(true, null, value);
    }

    /** True when an entry count is at the ceiling and the oldest must go. */
    public static boolean isFull(int currentCount) {
        return currentCount >= MAX_ENTRIES;
    }

    /** What the settings screen should say, so the trade-off is visible. */
    public static String description() {
        return "Lets the agent remember a little between tasks. Off by default. "
                + "Credentials and personal details are never stored, and nothing "
                + "leaves this device.";
    }

    /** Normalise a key so lookups are stable. */
    public static String normaliseKey(String key) {
        if (key == null) return "";
        return key.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "-");
    }
}
