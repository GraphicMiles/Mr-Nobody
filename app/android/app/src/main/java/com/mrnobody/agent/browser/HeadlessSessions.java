package com.mrnobody.agent.browser;

import android.content.Context;

import com.mrnobody.agent.tasks.EventLogRecorder;

import java.util.concurrent.ConcurrentHashMap;

/**
 * One headless WebView per agent task.
 *
 * <p>A single shared engine meant two tasks shared cookies: a login, or an
 * attacker's page, leaked into the next job. {@link SessionScope#forTask(long)}
 * already named the isolated profile; this is the missing owner that actually
 * constructs and tears it down.
 *
 * <p>Tools look up the engine for the task bound on this thread (see
 * {@link EventLogRecorder#bind(long)}). A tool called outside a task gets
 * nothing, which is a failed call rather than a silent shared jar.
 */
public final class HeadlessSessions {

    private static final ConcurrentHashMap<Long, HeadlessWebViewEngine> LIVE =
            new ConcurrentHashMap<>();

    private static volatile Context appContext;

    private HeadlessSessions() {
    }

    /** Remember the app context so a late lookup can still construct. */
    public static void init(Context context) {
        if (context != null) appContext = context.getApplicationContext();
    }

    /** The engine for {@code taskId}, created on first use. */
    public static HeadlessWebViewEngine acquire(Context context, long taskId) {
        Context ctx = context != null ? context.getApplicationContext() : appContext;
        if (ctx == null) return null;
        init(ctx);
        return LIVE.computeIfAbsent(taskId,
                id -> new HeadlessWebViewEngine(ctx, SessionScope.forTask(id)));
    }

    /**
     * The engine for the task bound on this thread, or null outside a task.
     * This is what {@code BrowserTool} / {@code SearchTool} call.
     */
    public static BrowserEngine current() {
        long id = EventLogRecorder.currentTask();
        if (id == EventLogRecorder.NO_TASK) return null;
        HeadlessWebViewEngine existing = LIVE.get(id);
        if (existing != null) return existing;
        return acquire(appContext, id);
    }

    /** Close and forget the engine. Deletes the ephemeral profile. */
    public static void release(long taskId) {
        HeadlessWebViewEngine engine = LIVE.remove(taskId);
        if (engine != null) {
            try {
                engine.close();
            } catch (Throwable ignored) {
                // Tearing down a WebView must never take the worker with it.
            }
        }
    }
}
