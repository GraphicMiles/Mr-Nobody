package com.mrnobody.agent.tasks;

import com.mrnobody.agent.core.ApprovalDecision;
import com.mrnobody.agent.core.ToolCall;
import com.mrnobody.agent.core.ToolPipeline;
import com.mrnobody.agent.core.ToolResult;
import com.mrnobody.agent.core.TaskScope;

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
 * <p>Which task a call belongs to comes from {@link TaskScope}. The tool
 * pipeline explicitly propagates that scope onto its executor thread; calls
 * made outside a task are recorded against task 0 rather than guessed at.
 */
public final class EventLogRecorder implements ToolPipeline.Recorder {

    /** Calls made outside any task still get recorded, under this id. */
    public static final long NO_TASK = TaskScope.NO_TASK;

    private final TaskEventStore store;

    public EventLogRecorder(TaskEventStore store) {
        this.store = store;
    }

    /** Mark this thread as running {@code taskId}. Always paired with {@link #clear()}. */
    public static void bind(long taskId) {
        TaskScope.bind(taskId);
    }

    public static void clear() {
        TaskScope.clear();
    }

    public static long currentTask() {
        return TaskScope.currentTask();
    }

    @Override
    public void onCall(ToolCall call) {
        write(TaskEventStore.TOOL_CALL, TaskEventDetail.toolCall(call));
    }

    @Override
    public void onResult(ToolCall call, ToolResult result,
                         ApprovalDecision decision, long durationMs) {
        if (result != null && result.needsApproval()) {
            CompletionStats.markConfirm();
        } else if (decision != null && decision.needsConfirmation()) {
            CompletionStats.markConfirm();
        }

        boolean refused = decision != null && decision.isDeny();
        String type = refused ? TaskEventStore.TOOL_DENIED : TaskEventStore.TOOL_RESULT;
        write(type, TaskEventDetail.toolResult(call, result, decision, durationMs));
    }

    private void write(String type, String detail) {
        try {
            store.append(currentTask(), type, detail);
        } catch (Throwable t) {
            // Logging must never break the thing it is logging.
        }
    }
}
