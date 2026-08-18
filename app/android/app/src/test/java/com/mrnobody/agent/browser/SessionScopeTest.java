package com.mrnobody.agent.browser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Which browsing session a task's work belongs to.
 *
 * <p>One headless engine served every task, so two tasks shared cookies: a
 * task that logged into a site left that login for the next one, and a task
 * reading an attacker's page shared a jar with one reading the user's mail.
 * The same shared-jar problem private tabs had, one layer down.
 */
public class SessionScopeTest {

    @Test
    public void eachTaskGetsItsOwnProfile() {
        assertFalse(SessionScope.forTask(1).profileName()
                .equals(SessionScope.forTask(2).profileName()));
    }

    @Test
    public void aTaskProfileIsThrownAwayAfterwards() {
        SessionScope s = SessionScope.forTask(7);
        assertTrue(s.isEphemeral());
        assertTrue(s.isIsolated());
    }

    @Test
    public void theSharedSessionPersistsAndIsNotIsolated() {
        SessionScope s = SessionScope.shared();
        assertFalse(s.isEphemeral());
        assertFalse("shared means shared; saying otherwise would be the old bug",
                s.isIsolated());
    }

    @Test
    public void theProfileNameIsStableSoAResumedTaskRejoinsItsSession() {
        // Deterministic from the id: after process death the task must return
        // to its own session rather than silently starting a fresh one.
        assertEquals(SessionScope.forTask(42).profileName(),
                SessionScope.forTask(42).profileName());
    }

    @Test
    public void aTaskIdCanBeRecoveredFromItsProfileName() {
        String name = SessionScope.forTask(99).profileName();
        assertEquals(99L, SessionScope.taskIdOf(name));
    }

    @Test
    public void foreignProfilesAreNotOursToDelete() {
        assertFalse(SessionScope.isAgentProfile("Default"));
        assertFalse(SessionScope.isAgentProfile("mrnobody-private"));
        assertFalse(SessionScope.isAgentProfile(null));

        assertTrue(SessionScope.isAgentProfile(SessionScope.SHARED));
        assertTrue(SessionScope.isAgentProfile(SessionScope.forTask(3).profileName()));
    }

    @Test
    public void anUnrecognisedNameYieldsNoTaskId() {
        assertEquals(-1L, SessionScope.taskIdOf("Default"));
        assertEquals(-1L, SessionScope.taskIdOf("agent-task-not-a-number"));
        assertEquals(-1L, SessionScope.taskIdOf(null));
    }
}
