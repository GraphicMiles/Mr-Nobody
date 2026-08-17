package com.mrnobody.agent.core;

/**
 * A cancellation signal handed to long-running work.
 *
 * <p>Cancellation is a <em>request</em>, not an interrupt: it is persisted by
 * whoever asked, and the executor observes it at a safe boundary — between plan
 * steps, or while waiting on a provider — so a cancelled task stops in a state
 * we can describe rather than halfway through a write.
 */
public interface Cancellation {

    /** Never cancelled. For callers that cannot be interrupted. */
    Cancellation NONE = () -> false;

    /**
     * True once the user (or the platform) has asked for this work to stop.
     * Implementations may be polled frequently and should be cheap.
     */
    boolean isCancelled();
}
