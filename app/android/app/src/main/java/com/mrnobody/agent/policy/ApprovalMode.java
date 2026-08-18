package com.mrnobody.agent.policy;

import com.mrnobody.agent.core.Tier;

/**
 * How much the agent may do without asking.
 *
 * <p>One axis, deliberately: the question "when should this stop and ask me?"
 * has a single sensible answer per user, and expressing it as a per-tool matrix
 * would be a settings screen nobody finishes reading.
 *
 * <p>The mode sets the floor. A per-tool override can raise it for one tool
 * (see {@link ApprovalPolicy}), and a {@code Guard} can still refuse
 * afterwards — nothing here can grant a permission that a guard withholds.
 */
public enum ApprovalMode {

    /**
     * Ask before anything that is not purely observational.
     *
     * <p>The safe default for a browser that can run downloads and shell
     * commands on someone's phone.
     */
    CAUTIOUS("Ask before acting", Tier.WRITE),

    /**
     * Local changes go ahead; anything reaching beyond this device, or running
     * a command, asks first.
     */
    BALANCED("Ask before commands", Tier.EXEC),

    /**
     * Never ask.
     *
     * <p>Not "unrestricted": DENY still denies, guards still guard, and the
     * terminal is still off unless the user enabled it. This removes the
     * prompt, not the policy.
     */
    TRUSTING("Don't ask", null);

    private final String label;
    private final Tier confirmFrom;

    ApprovalMode(String label, Tier confirmFrom) {
        this.label = label;
        this.confirmFrom = confirmFrom;
    }

    public String label() {
        return label;
    }

    /** The lowest tier that needs confirming, or null when nothing does. */
    public Tier confirmFrom() {
        return confirmFrom;
    }

    /** True when a call at {@code tier} must be confirmed under this mode. */
    public boolean requiresConfirmation(Tier tier) {
        if (confirmFrom == null || tier == null) return false;
        return tier.atLeast(confirmFrom);
    }

    public static ApprovalMode fromName(String name) {
        if (name != null) {
            for (ApprovalMode m : values()) {
                if (m.name().equalsIgnoreCase(name.trim())) return m;
            }
        }
        return CAUTIOUS;
    }
}
