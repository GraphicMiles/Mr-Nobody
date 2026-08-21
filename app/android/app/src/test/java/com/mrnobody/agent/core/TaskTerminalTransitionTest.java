package com.mrnobody.agent.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TaskTerminalTransitionTest {

    @Test
    public void lateFailureCannotOverwriteCompletion() {
        Task task = new Task(1, "test");
        task.setStatus(Task.Status.RUNNING);
        assertTrue(task.completeIf(Task.Status.RUNNING, "answer"));
        assertFalse(task.failIf(Task.Status.RUNNING, "late transport error"));
        assertEquals(Task.Status.COMPLETED, task.status());
        assertEquals("answer", task.result());
    }

    @Test
    public void lateCompletionCannotOverwriteFailure() {
        Task task = new Task(2, "test");
        task.setStatus(Task.Status.RUNNING);
        assertTrue(task.failIf(Task.Status.RUNNING, "server error"));
        assertFalse(task.completeIf(Task.Status.RUNNING, "late answer"));
        assertEquals(Task.Status.FAILED, task.status());
        assertEquals("server error", task.error());
    }
}
