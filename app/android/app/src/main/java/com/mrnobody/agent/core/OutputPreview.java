package com.mrnobody.agent.core;

/**
 * Limits oversized tool output before it enters a model context.
 *
 * <p>There is deliberately no synthetic locator. Unless bytes are actually
 * persisted behind a retrievable handle, saying "the full output is kept"
 * is false. Oversized values are now described honestly as non-retrievable
 * previews and the planner is told to narrow or repeat the request.
 */
public final class OutputPreview {

    /** Above this many characters, only a bounded preview is returned. */
    public static final int INLINE_LIMIT = 8_000;

    /** How much of an oversized value is shown. */
    public static final int PREVIEW_CHARS = 600;

    public static final class Decision {
        public final boolean truncated;
        public final String inline;
        public final int originalLength;

        Decision(boolean truncated, String inline, int originalLength) {
            this.truncated = truncated;
            this.inline = inline;
            this.originalLength = originalLength;
        }
    }

    private OutputPreview() {
    }

    public static boolean shouldTruncate(String value) {
        return value != null && value.length() > INLINE_LIMIT;
    }

    public static Decision decide(String value) {
        if (value == null) return new Decision(false, "", 0);
        if (!shouldTruncate(value)) {
            return new Decision(false, value, value.length());
        }

        String preview = value.substring(0, Math.min(PREVIEW_CHARS, value.length()));
        int omitted = value.length() - preview.length();
        String inline = preview
                + "\n\n[... " + omitted + " characters omitted. The full output was NOT "
                + "retained and cannot be retrieved from this preview. Treat this as "
                + "incomplete; narrow or repeat the request before answering.]";
        return new Decision(true, inline, value.length());
    }
}
