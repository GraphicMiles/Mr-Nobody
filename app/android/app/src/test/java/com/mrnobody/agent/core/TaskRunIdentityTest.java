package com.mrnobody.agent.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

public class TaskRunIdentityTest {

    @Test
    public void retryKeepsRunButExplicitNewCycleChangesIt() {
        Task task = new Task(1L, "monitor this");
        String first = task.runId();

        task.bumpRetry();
        assertEquals(first, task.runId());

        task.startNewRun();
        assertFalse(first.equals(task.runId()));
    }

    @Test
    public void persistedRunIdRestoresExactly() {
        Task task = new Task(2L, "x", 10L, 20L, "run-persisted");
        assertEquals("run-persisted", task.runId());
    }
}
