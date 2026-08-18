package com.mrnobody.agent.memory;

import com.mrnobody.agent.core.Task;

import java.util.List;

/**
 * A short, injectable summary of the agent's recent completed work, so a new
 * task has continuity without the user repeating themselves.
 *
 * <p>This is the "auto-injection" half of memory: before answering, the engine
 * hands the model a few lines of what it already did, so "remind me what I
 * downloaded earlier" has something to ground on. Pure and bounded — a digest
 * is a handful of short lines, never the full result text — so it cannot
 * balloon the prompt or leak more than the user would already see in the task
 * list. The current task's own instruction is excluded, and only completed
 * tasks are summarised.
 */
public final class MemoryDigest {

    /** How many past tasks to mention. Small on purpose. */
    public static final int MAX_ITEMS = 5;

    /** How much of a result to quote. Enough to be useful, not the whole text. */
    private static final int RESULT_SNIPPET = 120;

    private MemoryDigest() {
    }

    /**
     * A digest of recent completed tasks, newest first, excluding
     * {@code currentInstruction}. Returns "" when there is nothing to say.
     */
    public static String digest(List<Task> tasks, String currentInstruction) {
        if (tasks == null) return "";
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Task t : tasks) {
            if (t == null || t.status() != Task.Status.COMPLETED) continue;
            if (currentInstruction != null
                    && currentInstruction.equals(t.instruction())) continue;
            if (n >= MAX_ITEMS) break;
            if (n == 0) sb.append("Recent tasks:\n");
            n++;
            sb.append(n).append(". ").append(t.instruction()).append('\n');
            String r = snippet(t.result());
            if (!r.isEmpty()) sb.append("   → ").append(r).append('\n');
        }
        return sb.toString();
    }

    private static String snippet(String result) {
        if (result == null || result.trim().isEmpty()) return "";
        String r = result.replaceAll("\\s+", " ").trim();
        return r.length() <= RESULT_SNIPPET ? r : r.substring(0, RESULT_SNIPPET) + "…";
    }
}
