package com.mrnobody.agent.core;

import java.util.concurrent.Callable;

/**
 * The execution identity of the task currently using the agent.
 *
 * <p>Task identity is needed by logging, the task-scoped headless browser and
 * output handling. Tool bodies run on a pooled executor, so setting a plain
 * {@link ThreadLocal} on the WorkManager thread is not enough: pooled threads
 * do not inherit it. {@link #callAs(long, Callable)} is the explicit propagation
 * boundary used by the tool pipeline.
 */
public final class TaskScope {

    public static final long NO_TASK = 0L;

    private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();

    private TaskScope() {
    }

    /** Bind a task to the current thread. Always pair with {@link #clear()}. */
    public static void bind(long taskId) {
        if (taskId == NO_TASK) {
            CURRENT.remove();
        } else {
            CURRENT.set(taskId);
        }
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static long currentTask() {
        Long id = CURRENT.get();
        return id == null ? NO_TASK : id;
    }

    /**
     * Execute work as {@code taskId}, restoring any prior binding afterwards.
     * This is safe for reused executor threads: no task identity is left behind.
     */
    public static <T> T callAs(long taskId, Callable<T> work) throws Exception {
        Long previous = CURRENT.get();
        bind(taskId);
        try {
            return work.call();
        } finally {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }
}
