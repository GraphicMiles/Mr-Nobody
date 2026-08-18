package com.mrnobody.agent.planner;

/**
 * What a task is, decided before any tool runs.
 *
 * <p>DeepSeek Harness splits a turn at {@code agent/pre-step}. Mr Nobody
 * needs the same seam for a smaller closed union — search vs monitor vs
 * fetch-this-site — because those three jobs share tools and used to be
 * told apart by scanning the user's wording for seed phrases.
 */
public enum TaskIntent {

    ONE_TIME_ANSWER("one_time_answer"),
    RECURRING_MONITOR("recurring_monitor"),
    NAMED_SOURCE_FETCH("named_source_fetch");

    private final String wire;

    TaskIntent(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public static TaskIntent fromWire(String token) {
        if (token == null) return null;
        String t = token.trim().toLowerCase(java.util.Locale.ROOT)
                .replace('-', '_').replace(' ', '_');
        for (TaskIntent v : values()) {
            if (v.wire.equals(t)) return v;
        }
        return null;
    }
}
