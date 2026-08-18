package com.mrnobody.agent.core;

/**
 * Decides when a tool's output is too large to hand to a model.
 *
 * <p>A page can be a megabyte of text. Passing that straight into a prompt
 * costs money, crowds out the instruction, and on a small context window
 * silently truncates the middle — which is worse than refusing, because the
 * model answers confidently from half a document without knowing the other
 * half existed.
 *
 * <p>So oversized output is kept and a locator is handed over instead: the
 * agent is told what it has and where, rather than being given a fragment
 * dressed as the whole.
 *
 * <p>Pure decision logic, no storage of its own, so the thresholds are
 * testable without a filesystem.
 */
public final class OutputSpill {

    /** Above this many characters, output is spilled rather than inlined. */
    public static final int INLINE_LIMIT = 8_000;

    /** How much of a spilled value is still shown, as a preview. */
    public static final int PREVIEW_CHARS = 600;

    /** What to do with one output value. */
    public static final class Decision {
        public final boolean spill;
        public final String inline;
        public final int originalLength;

        Decision(boolean spill, String inline, int originalLength) {
            this.spill = spill;
            this.inline = inline;
            this.originalLength = originalLength;
        }
    }

    private OutputSpill() {
    }

    public static boolean shouldSpill(String value) {
        return value != null && value.length() > INLINE_LIMIT;
    }

    /**
     * Decide what the model sees.
     *
     * @param locator where the full value was kept, shown to the agent so it
     *                can ask for more rather than assuming it has everything
     */
    public static Decision decide(String value, String locator) {
        if (value == null) return new Decision(false, "", 0);
        if (value.length() <= INLINE_LIMIT) {
            return new Decision(false, value, value.length());
        }

        String preview = value.substring(0, Math.min(PREVIEW_CHARS, value.length()));

        // The count and the locator both matter: "truncated" alone invites the
        // model to answer anyway, while saying how much is missing and where it
        // is makes the gap something it can act on.
        String inline = preview
                + "\n\n[... " + (value.length() - preview.length())
                + " more characters not shown. The full output is kept at "
                + locator + ". This is a PREVIEW: do not treat it as the "
                + "complete document.]";

        return new Decision(true, inline, value.length());
    }

    /** A stable locator for a spilled value. */
    public static String locatorFor(String tool, long taskId, long seq) {
        return "spill://" + tool + "/" + taskId + "/" + seq;
    }
}
