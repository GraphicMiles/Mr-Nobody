package com.mrnobody.agent.browser;

import java.util.Locale;

/**
 * Which browsing session a piece of agent work belongs to.
 *
 * <p>One {@code HeadlessWebViewEngine} serves every task today, so two tasks
 * share cookies. A task that logs into a site leaves that login sitting there
 * for the next task, and a task reading an attacker's page shares a jar with
 * one reading the user's mail. Neither is a hypothetical: they are the same
 * shared-jar problem that private tabs had, one layer down.
 *
 * <p>A scope names the profile a task's browsing belongs in, so the isolation
 * decision is made once and in the open rather than implied by whichever
 * engine instance happened to be reused. The profile name is deterministic
 * from the scope, which is what lets a resumed task rejoin its own session
 * after process death instead of starting a fresh one.
 *
 * <p>Naming only. Applying a profile needs {@code MULTI_PROFILE} and belongs
 * with the WebView, which is why this stays pure Java and testable.
 */
public final class SessionScope {

    /** Everything the agent does outside a task. */
    public static final String SHARED = "agent-shared";

    private final String profileName;
    private final boolean ephemeral;

    private SessionScope(String profileName, boolean ephemeral) {
        this.profileName = profileName;
        this.ephemeral = ephemeral;
    }

    /**
     * A session private to one task, destroyed when the task ends.
     *
     * <p>The default for agent work: a task should not inherit a login it did
     * not create, and should not leave one behind.
     */
    public static SessionScope forTask(long taskId) {
        return new SessionScope("agent-task-" + taskId, true);
    }

    /**
     * The shared agent session.
     *
     * <p>Persistent on purpose — some work genuinely wants continuity across
     * tasks — so it is never the default and has to be asked for.
     */
    public static SessionScope shared() {
        return new SessionScope(SHARED, false);
    }

    public String profileName() {
        return profileName;
    }

    /** True when the profile should be deleted once the task finishes. */
    public boolean isEphemeral() {
        return ephemeral;
    }

    /** True when this scope owns no state that outlives the task. */
    public boolean isIsolated() {
        return ephemeral;
    }

    /** Recover the task id from a profile name, or -1 when it is not one. */
    public static long taskIdOf(String profileName) {
        if (profileName == null) return -1L;
        String p = profileName.trim().toLowerCase(Locale.ROOT);
        if (!p.startsWith("agent-task-")) return -1L;
        try {
            return Long.parseLong(p.substring("agent-task-".length()));
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    /** True when a profile is one of ours, and therefore ours to delete. */
    public static boolean isAgentProfile(String profileName) {
        if (profileName == null) return false;
        String p = profileName.trim().toLowerCase(Locale.ROOT);
        return p.equals(SHARED) || p.startsWith("agent-task-");
    }

    @Override
    public String toString() {
        return profileName + (ephemeral ? " (ephemeral)" : " (persistent)");
    }
}
