package com.mrnobody.debug;

import java.util.ArrayList;
import java.util.List;

/**
 * A tiny in-memory error log that powers the debug FAB's badge. Anything that
 * fails (a tool, a task step, a blocked request, an exception) can call
 * {@link #record(String)}; the debug panel shows the count and the tail.
 *
 * <p>There is a second, quieter channel: {@link #trace(String)}. The tab and
 * WebView lifecycle events that surround the "page goes black after it
 * finishes loading" defect are recorded there — they must be visible in the
 * panel for the bug to be caught, but they are diagnostics, not errors, so
 * they do not inflate the badge count.
 */
public final class ErrorLog {

    public interface Listener {
        void onChanged();
    }

    private static final int MAX_ENTRIES = 200;
    private static final int MAX_TRACE = 300;

    private static final List<String> entries = new ArrayList<>();
    private static final List<String> traceEntries = new ArrayList<>();
    private static final List<Listener> listeners = new ArrayList<>();

    private ErrorLog() {
    }

    public static synchronized void record(String message) {
        entries.add(message);
        while (entries.size() > MAX_ENTRIES) entries.remove(0);
        notifyListeners();
    }

    /** A lifecycle/diagnostic event: shown in the panel, not counted as an error. */
    public static synchronized void trace(String message) {
        traceEntries.add(message);
        while (traceEntries.size() > MAX_TRACE) traceEntries.remove(0);
    }

    public static synchronized int count() {
        return entries.size();
    }

    public static synchronized List<String> tail(int n) {
        int from = Math.max(0, entries.size() - n);
        return new ArrayList<>(entries.subList(from, entries.size()));
    }

    public static synchronized List<String> traceTail(int n) {
        int from = Math.max(0, traceEntries.size() - n);
        return new ArrayList<>(traceEntries.subList(from, traceEntries.size()));
    }

    public static synchronized void clear() {
        entries.clear();
        traceEntries.clear();
        notifyListeners();
    }

    public static void addListener(Listener l) {
        synchronized (listeners) {
            listeners.add(l);
        }
    }

    private static void notifyListeners() {
        synchronized (listeners) {
            for (Listener l : listeners) l.onChanged();
        }
    }
}
