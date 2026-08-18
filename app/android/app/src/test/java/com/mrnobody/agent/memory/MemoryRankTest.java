package com.mrnobody.agent.memory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.mrnobody.agent.core.Task;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The relevance ranking behind memory.search. A wrong ranking surfaces the
 * wrong history, which is how an agent "remembers" something irrelevant and
 * answers off it.
 */
public class MemoryRankTest {

    private static Task completed(long id, String instruction, String result) {
        Task t = new Task(id, instruction);
        t.setStatus(Task.Status.COMPLETED);
        t.setResult(result);
        return t;
    }

    private static Task failed(long id, String instruction) {
        Task t = new Task(id, instruction);
        t.setStatus(Task.Status.FAILED);
        return t;
    }

    @Test
    public void ranksTheMostRelevantFirst() {
        List<Task> tasks = Arrays.asList(
                completed(1, "find laptops under 500000", "I found three laptops"),
                completed(2, "what is the capital of ghana", "Accra"),
                completed(3, "compare laptop prices", "cheapest laptop is X"));
        // Two query words: task 3 matches both, task 1 only one.
        List<MemoryRank.Hit> hits = MemoryRank.search("laptop price", tasks, 5);

        assertEquals(2, hits.size());
        assertEquals(3, hits.get(0).id);
        assertEquals(1, hits.get(1).id);
        assertTrue(hits.get(0).score > hits.get(1).score);
    }

    @Test
    public void instructionWordsOutweighResultWords() {
        List<Task> tasks = Arrays.asList(
                completed(1, "what is the capital of ghana", "Accra is the capital"),
                completed(2, "plan a trip", "visit ghana and its capital accra"));
        // Task 2 mentions "ghana" only in the result; task 1 in the instruction.
        List<MemoryRank.Hit> hits = MemoryRank.search("capital of ghana", tasks, 5);

        assertEquals(2, hits.size());
        assertEquals(1, hits.get(0).id);
    }

    @Test
    public void failedTasksAreNotRemembered() {
        List<Task> tasks = Arrays.asList(
                failed(1, "find laptops"),
                completed(2, "find laptops", "found them"));
        List<MemoryRank.Hit> hits = MemoryRank.search("laptops", tasks, 5);

        assertEquals(1, hits.size());
        assertEquals(2, hits.get(0).id);
    }

    @Test
    public void aBlankQueryReturnsNothing() {
        List<Task> tasks = Arrays.asList(completed(1, "a", "b"));
        assertTrue(MemoryRank.search("", tasks, 5).isEmpty());
        assertTrue(MemoryRank.search("   ", tasks, 5).isEmpty());
        assertTrue(MemoryRank.search(null, tasks, 5).isEmpty());
        assertTrue(MemoryRank.search("x", null, 5).isEmpty());
    }

    @Test
    public void noMatchesReturnsEmpty() {
        List<Task> tasks = Arrays.asList(completed(1, "find laptops", "found"));
        assertTrue(MemoryRank.search("banana", tasks, 5).isEmpty());
    }

    @Test
    public void theLimitIsHonoured() {
        List<Task> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            tasks.add(completed(i, "laptop deal number " + i, "laptop " + i));
        }
        assertEquals(3, MemoryRank.search("laptop", tasks, 3).size());
    }
}
