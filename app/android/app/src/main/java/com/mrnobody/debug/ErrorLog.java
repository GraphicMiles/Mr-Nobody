package com.mrnobody.debug;

import java.util.ArrayList;
import java.util.List;

/**
 * A tiny in-memory error log that powers the debug FAB's badge. Anything that
 * fails (a tool, a task step, a blocked request, an exception) can call
 * {@link #record(String)}; the debug panel shows the count and the tail.
 */
public final class ErrorLog {

    public interface Listener {
        void onChanged();
    }

    private static final int MAX_ENTRIES = 200;
    private static final List<String> entries = new ArrayList<>();
    private static final List<Listener> listeners = new ArrayList<>();

    private ErrorLog() {
    }

    public static synchronized void record(String message) {
        entries.add(message);
        while (entries.size() > MAX_ENTRIES) entries.remove(0);
        notifyListeners();
    }

    public static synchronized int count() {
        return entries.size();
    }

    public static synchronized List<String> tail(int n) {
        int from = Math.max(0, entries.size() - n);
        return new ArrayList<>(entries.subList(from, entries.size()));
    }

    public static synchronized void clear() {
        entries.clear();
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
