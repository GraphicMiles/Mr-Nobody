package com.mrnobody.agent.planner;

/**
 * What to do with a remote answer that failed verification.
 *
 * <p>Before this existed, {@code AnswerVerifier} and {@code FigureCheck}
 * findings were appended as warnings and the answer shipped anyway — the
 * agent knew a claim was unsupported and delivered it regardless. The gate
 * makes verification blocking, in two steps:
 *
 * <ol>
 *   <li><b>RETRY</b> — the model gets exactly one corrective re-ask, with
 *       the verifier's findings quoted at it.</li>
 *   <li><b>FALLBACK</b> — a second failure discards the draft and answers
 *       extractively from the pages actually read, which cannot hallucinate
 *       by construction, with a note saying exactly what happened.</li>
 * </ol>
 */
public final class AnswerGate {

    public enum Action { PASS, RETRY, FALLBACK }

    /** The single corrective re-ask allowed per run. */
    public static final int MAX_RETRIES = 1;

    private AnswerGate() {
    }

    /**
     * @param hasProblems verifier or figure check flagged the draft
     * @param retriesUsed corrective re-asks already spent this run
     */
    public static Action decide(boolean hasProblems, int retriesUsed) {
        if (!hasProblems) return Action.PASS;
        return retriesUsed < MAX_RETRIES ? Action.RETRY : Action.FALLBACK;
    }

    /** The corrective instruction appended to the original prompt. */
    public static String correction(String verifierNote, String figureNote) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n\nYour previous draft failed verification and was rejected:");
        if (verifierNote != null && !verifierNote.isEmpty()) {
            sb.append("\n- ").append(verifierNote);
        }
        if (figureNote != null && !figureNote.isEmpty()) {
            sb.append("\n- ").append(figureNote);
        }
        sb.append("\nRewrite the answer. Every claim must cite a numbered source "
                + "given above, and every figure must appear verbatim in those "
                + "sources. If the sources do not contain the answer, say so "
                + "plainly instead.");
        return sb.toString();
    }

    /** The note attached when the extractive fallback replaces the draft. */
    public static String fallbackNote() {
        return "The model's draft could not be verified against the sources read, "
                + "so it was discarded. The text above is extracted directly from "
                + "those sources instead.";
    }
}
