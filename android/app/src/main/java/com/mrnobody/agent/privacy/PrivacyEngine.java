package com.mrnobody.agent.privacy;

/**
 * Interface over the privacy/filter core. V1 wraps the existing local
 * {@code FilterEngine}; the agent and tools depend on this interface so a Rust
 * core (V2, benchmark-justified) can replace it without touching callers.
 */
public interface PrivacyEngine {

    /** Decide whether a request URL should be blocked. */
    boolean shouldBlock(String url);

    boolean isEnabled();

    void setEnabled(boolean enabled);
}
