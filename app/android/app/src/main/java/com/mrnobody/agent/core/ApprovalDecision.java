package com.mrnobody.agent.core;

/**
 * A permission decision, and — just as importantly — where it came from.
 *
 * <p>"Denied" with no reason is a bug report waiting to happen. Every decision
 * carries the rule that produced it so the UI can explain itself and a log can
 * be audited.
 */
public final class ApprovalDecision {

    public enum Outcome { ALLOW, CONFIRM, DENY }

    /** Which rule decided. Ordered from most specific to least. */
    public enum Source { USER_OVERRIDE, GUARD, MODE, TIER, DEFAULT }

    private final Outcome outcome;
    private final Source source;
    private final String reason;

    private ApprovalDecision(Outcome outcome, Source source, String reason) {
        this.outcome = outcome;
        this.source = source;
        this.reason = reason == null ? "" : reason;
    }

    public static ApprovalDecision allow(Source source, String reason) {
        return new ApprovalDecision(Outcome.ALLOW, source, reason);
    }

    public static ApprovalDecision confirm(Source source, String reason) {
        return new ApprovalDecision(Outcome.CONFIRM, source, reason);
    }

    public static ApprovalDecision deny(Source source, String reason) {
        return new ApprovalDecision(Outcome.DENY, source, reason);
    }

    public Outcome outcome() { return outcome; }
    public Source source() { return source; }
    public String reason() { return reason; }

    public boolean isAllow() { return outcome == Outcome.ALLOW; }
    public boolean isDeny() { return outcome == Outcome.DENY; }
    public boolean needsConfirmation() { return outcome == Outcome.CONFIRM; }

    @Override
    public String toString() {
        return outcome + " (" + source + (reason.isEmpty() ? "" : ": " + reason) + ")";
    }
}
