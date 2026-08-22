package com.mrnobody.agent.tasks;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Carries a task's streamed answer tokens from the engine — which runs inside
 * a {@link TaskWorker} woken by WorkManager — to whatever is showing the task
 * (the Flutter task chat, via the {@code mrnobody/task-stream} EventChannel).
 *
 * <p>The worker and the activity share a process but no object: the worker is
 * instantiated by WorkManager and the activity by the framework, and neither
 * holds a reference to the other. This hub is the seam between them. It is a
 * fire-and-forget pipe: a token emitted while nobody is listening is dropped,
 * which is correct rather than lossy — the whole answer is also persisted to
 * the task row, so a late subscriber reads the finished result instead of a
 * half-replayed stream.
 */
public final class TaskStreamHub {

    /** Receives one task's stream. */
    public interface Listener {
        void onToken(long taskId, String token);

        void onDone(long taskId, String fullText);

        void onError(long taskId, String error);

        /** Byte-level download progress for a task-owned download. */
        default void onDownloadProgress(long taskId, long bytes, long total,
                                        String name, String status) { }
    }

    private static final TaskStreamHub INSTANCE = new TaskStreamHub();

    private final Map<Long, List<Listener>> listeners = new ConcurrentHashMap<>();

    private TaskStreamHub() {
    }

    public static TaskStreamHub instance() {
        return INSTANCE;
    }

    /** Start receiving events for {@code taskId}. Idempotent per listener. */
    public void subscribe(long taskId, Listener listener) {
        listeners.computeIfAbsent(taskId, k -> new CopyOnWriteArrayList<>()).add(listener);
    }

    public void unsubscribe(long taskId, Listener listener) {
        List<Listener> list = listeners.get(taskId);
        if (list == null) return;
        list.remove(listener);
        if (list.isEmpty()) listeners.remove(taskId);
    }

    /** Emit one token to every listener for {@code taskId}. No-op when none. */
    public void emitToken(long taskId, String token) {
        List<Listener> list = listeners.get(taskId);
        if (list == null) return;
        for (Listener listener : list) listener.onToken(taskId, token);
    }

    public void emitDone(long taskId, String fullText) {
        List<Listener> list = listeners.get(taskId);
        if (list == null) return;
        for (Listener listener : list) listener.onDone(taskId, fullText);
    }

    public void emitError(long taskId, String error) {
        List<Listener> list = listeners.get(taskId);
        if (list == null) return;
        for (Listener listener : list) listener.onError(taskId, error);
    }

    public void emitDownloadProgress(long taskId, long bytes, long total,
                                     String name, String status) {
        List<Listener> list = listeners.get(taskId);
        if (list == null) return;
        for (Listener listener : list) {
            listener.onDownloadProgress(taskId, bytes, total, name, status);
        }
    }
}
