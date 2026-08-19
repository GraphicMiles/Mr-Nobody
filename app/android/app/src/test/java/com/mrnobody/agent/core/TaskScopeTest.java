package com.mrnobody.agent.core;

import static org.junit.Assert.assertEquals;

import org.junit.After;
import org.junit.Test;

public class TaskScopeTest {

    @After
    public void clearScope() {
        TaskScope.clear();
    }

    @Test
    public void outsideWorkHasNoTask() {
        assertEquals(TaskScope.NO_TASK, TaskScope.currentTask());
    }

    @Test
    public void callAsRestoresThePreviousBinding() throws Exception {
        TaskScope.bind(7L);

        long nested = TaskScope.callAs(9L, TaskScope::currentTask);

        assertEquals(9L, nested);
        assertEquals(7L, TaskScope.currentTask());
    }

    @Test
    public void callAsClearsAfterAnException() {
        try {
            TaskScope.callAs(11L, () -> {
                throw new IllegalStateException("boom");
            });
        } catch (Exception expected) {
            assertEquals("boom", expected.getMessage());
        }

        assertEquals(TaskScope.NO_TASK, TaskScope.currentTask());
    }
}
