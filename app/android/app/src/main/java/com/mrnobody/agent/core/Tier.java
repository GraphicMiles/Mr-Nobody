package com.mrnobody.agent.core;

/**
 * What a tool is capable of doing, which is what its permission is judged on.
 *
 * <p>Four tiers, ordered by <em>risk</em> rather than by I/O direction.
 * A download into the app's own folder is not the same class of act as
 * clicking submit on a live page, and treating both as WRITE made every
 * ordinary fetch stop for a human.
 */
public enum Tier {

    /** Observes. Fetches a page, parses results, reads state. Reversible by doing nothing. */
    READ,

    /**
     * Changes confined to this app's sandbox: enqueue a download, write
     * inside the task workspace. Reversible by deleting the file. Does
     * not overwrite the user's own documents and does not talk to the
     * open web as the user.
     */
    SANDBOX,

    /**
     * Mutates user-visible or off-sandbox state: click, type, submit on
     * a live page. Can post, purchase, or change an account. Confirm
     * under the cautious mode.
     */
    WRITE,

    /** Acts beyond this device or runs a command we cannot take back. */
    EXEC;

    /** True if this tier is at least as consequential as {@code other}. */
    public boolean atLeast(Tier other) {
        return ordinal() >= other.ordinal();
    }
}
