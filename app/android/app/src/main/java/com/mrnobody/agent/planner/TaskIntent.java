package com.mrnobody.agent.planner;

/**
 * What a task <em>is</em>, decided before any tool runs.
 *
 * <p>DeepSeek Harness splits a turn at {@code agent/pre-step}: listeners may
 * rewrite or reject the claim before the model is allowed to act. Mr Nobody
 * needs the same seam for a smaller closed union — search vs monitor vs
 * fetch-this-site — because those three jobs share tools and used to be
 * told apart by scanning the user's wording for seed phrases.
 *
 * <p>The labels are the contract. Vocabulary is not. A request to be kept
 * informed is {@link #RECURRING_MONITOR} whether or not it contains any
 * particular verb; a request to obtain something from a named site is
 * {@link #NAMED_SOURCE_FETCH} whether the site was typed as a URL, a host
 * or an {@code @handle}. Structure (a domain, a handle, an explicit
 * interval) is extracted elsewhere; this enum is only the intent.
 */
public enum TaskIntent {

    /** Answer once. After that the job is done. */
    ONE_TIME_ANSWER("one_time_answer"),

    /** Keep checking and report again. Must actually be scheduled. */
    RECURRING_MONITOR("recurring_monitor"),

    /** Obtain something from a site or account the user named. Fetch that source first. */
    NAMED_SOURCE_FETCH("named_source_fetch");

    private final String wire;

    TaskIntent(String wire) {
        this.wire = wire;
    }

    /** The token the classifier writes and reads. */
    public String wire() {
        return wire;
    }

    /**
     * Parse a model token. Unknown or empty is {@code null}, never a guess:
     * a misread label must not schedule background work or skip a search.
     */
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
