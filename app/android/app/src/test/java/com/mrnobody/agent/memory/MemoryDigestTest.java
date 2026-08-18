package com.mrnobody.agent.memory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.mrnobody.agent.core.Task;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The auto-injected memory digest: a few lines of recent work, bounded, that
 * give a new task continuity without the user repeating themselves.
 */
public class MemoryDigestTest {

    private static Task done(long id, String instruction, String result) {
        Task t = new Task(id, instruction);
        t.setStatus(Task.Status.COMPLETED);
        t.setResult(result);
        return t;
    }

    @Test
    public void summarisesRecentCompletedTasks() {
        List<Task> tasks = Arrays.asList(
                done(2, "find laptops", "I found three laptops under 500000"),
                done(1, "track bitcoin", "Bitcoin is 64282"));
        String d = MemoryDigest.digest(tasks, null);

        assertTrue(d.contains("Recent tasks:"));
        assertTrue(d.contains("find laptops"));
        assertTrue(d.contains("track bitcoin"));
        assertTrue(d.contains("I found three laptops"));
    }

    @Test
    public void excludesTheCurrentInstruction() {
        List<Task> tasks = Arrays.asList(
                done(1, "find laptops", "found"),
                done(2, "track bitcoin", "64282"));
        String d = MemoryDigest.digest(tasks, "find laptops");

        assertFalse(d.contains("find laptops"));
        assertTrue(d.contains("track bitcoin"));
    }

    @Test
    public void skipsNonCompletedTasks() {
        Task running = new Task(3, "still going");
        running.setStatus(Task.Status.RUNNING);
        List<Task> tasks = new ArrayList<>();
        tasks.add(running);
        tasks.add(done(1, "find laptops", "found"));
        String d = MemoryDigest.digest(tasks, null);

        assertFalse(d.contains("still going"));
        assertTrue(d.contains("find laptops"));
    }

    @Test
    public void isBoundedToMaxItems() {
        List<Task> tasks = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            tasks.add(done(i, "task number " + i, "result " + i));
        }
        String d = MemoryDigest.digest(tasks, null);
        // Only MAX_ITEMS numbered lines, and the result snippet is capped.
        assertFalse(d.contains("task number 10"));
        assertTrue(d.contains("task number 0"));
    }

    @Test
    public void emptyWhenNothingToSay() {
        assertEquals("", MemoryDigest.digest(null, null));
        assertEquals("", MemoryDigest.digest(new ArrayList<>(), null));
    }
}
