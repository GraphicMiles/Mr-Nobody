package com.mrnobody.agent.core;

/**
 * What happened when we asked a human about a tool call.
 *
 * <p>Three outcomes, not two: "the user said no" and "nobody was there to
 * ask" are different, and collapsing them is how a background task became a
 * mysterious failure instead of a waiting one. The call must not run in
 * either case (fail-closed). Only UNAVAILABLE parks the task.
 */
public enum Confirmation {
    /** The user allowed this call. */
    ALLOWED,
    /** The user refused this call. The task should fail. */
    DENIED,
    /** No one could answer (screen off, timed out, no UI). Park as WAITING. */
    UNAVAILABLE
}
