package com.mrnobody.agent.tasks;

import com.mrnobody.agent.core.ApprovalDecision;
import com.mrnobody.agent.core.ToolCall;
import com.mrnobody.agent.core.ToolPipeline;
import com.mrnobody.agent.core.ToolResult;

/**
 * Writes the pipeline's calls into {@link TaskEventStore}.
 *
 * <p>The {@code Recorder} seam has existed since the pipeline was built and
 * nothing was ever attached to it, so every tool call vanished the moment it
 * returned. This attaches it.
 *
 * <p><b>The call is logged before it runs.</b> That ordering is the point: a
 * call that hangs, is killed by the timeout, or dies with the process still
 * left a record that it was attempted. Logging only on completion would lose
 * exactly the calls worth investigating.
 *
 * <p>Which task a call belongs to is thread-local, because the pipeline is
 * shared and deliberately knows nothing about tasks. A worker marks the thread
 * for the duration of a task; calls from anywhere else are recorded against
 * task 0 rather than being dropped or guessed at.
 */
public final class EventLogRecorder implements ToolPipeline.Recorder {

    /** Calls made outside any task still get recorded, under this id. */
    public static final long NO_TASK = 0L;

    private static final ThreadLocal<Long> CURRENT_TASK = new ThreadLocal<>();

    private final TaskEventStore store;

    public EventLogRecorder(TaskEventStore store) {
        this.store = store;
    }

    /** Mark this thread as running {@code taskId}. Always paired with {@link #clear()}. */
    public static void bind(long taskId) {
        CURRENT_TASK.set(taskId);
    }

    public static void clear() {
        CURRENT_TASK.remove();
    }

    public static long currentTask() {
        Long id = CURRENT_TASK.get();
        return id == null ? NO_TASK : id;
    }

    @Override
    public void onCall(ToolCall call) {
        write(TaskEventStore.TOOL_CALL, call.summary());
    }

    @Override
    public void onResult(ToolCall call, ToolResult result,
                         ApprovalDecision decision, long durationMs) {
        boolean refused = decision != null && decision.isDeny();
        String type = refused ? TaskEventStore.TOOL_DENIED : TaskEventStore.TOOL_RESULT;

        StringBuilder detail = new StringBuilder(call.tool());
        detail.append(refused ? " refused" : (result.isSuccess() ? " ok" : " failed"));
        detail.append(" in ").append(durationMs).append("ms");

        // Record why, not just that: "refused" without a reason is a log entry
        // that raises a question instead of answering one.
        if (refused && decision.reason() != null && !decision.reason().isEmpty()) {
            detail.append(" — ").append(decision.reason());
        } else if (!result.isSuccess() && result.error() != null) {
            detail.append(" — ").append(trim(result.error()));
        }

        write(type, detail.toString());
    }

    private void write(String type, String detail) {
        try {
            store.append(currentTask(), type, detail);
        } catch (Throwable t) {
            // Logging must never break the thing it is logging.
        }
    }

    private static String trim(String s) {
        if (s == null) return "";
        return s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }
}
