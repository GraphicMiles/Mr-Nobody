package com.mrnobody.agent.core;

/**
 * What a tool is capable of doing, which is what its permission is judged on.
 *
 * <p>Three tiers, ordered by consequence. The tier is a property of the
 * capability, not of the caller: it says what could happen if this call runs,
 * so an approval mode can decide without knowing anything about the tool.
 */
public enum Tier {

    /** Observes. Fetches a page, parses results, reads state. Reversible by doing nothing. */
    READ,

    /** Changes local state: writes a file, enqueues a download, stores data. */
    WRITE,

    /** Acts beyond this device or runs commands: side effects we cannot take back. */
    EXEC;

    /** True if this tier is at least as consequential as {@code other}. */
    public boolean atLeast(Tier other) {
        return ordinal() >= other.ordinal();
    }
}
