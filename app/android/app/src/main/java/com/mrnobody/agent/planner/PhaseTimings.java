package com.mrnobody.agent.planner;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Wall-clock duration of each phase of one task run.
 *
 * <p>Per-tool timing already lives in the event log ({@code tool.result} carries
 * {@code durationMs}). This is the missing <em>whole-run</em> breakdown: how
 * much time went to planning, tool work, answer synthesis and verification —
 * the numbers that tell you whether the autonomous loop's repeated LLM calls
 * are the real cost, or whether it is the network. A helper, not a subsystem:
 * it just accumulates named phase durations and exposes them in milliseconds.
 *
 * <p>Pure numbers, no content: nothing sensitive is measured, so this is safe
 * to log without redaction. Durations are summed per phase so that repeated
 * steps (several planning calls) read as one number.
 */
public final class PhaseTimings {

    private final Map<String, Long> nanos = new LinkedHashMap<>();
    private String current;
    private long startedAt;
    private long totalNanos;

    public PhaseTimings() {
    }

    /** Begin timing {@code phase}. Any open phase is closed first. */
    public void begin(String phase) {
        end();
        current = phase == null ? "" : phase;
        startedAt = System.nanoTime();
    }

    /** Close the currently-open phase, if any. */
    public void end() {
        if (current == null) return;
        long elapsed = System.nanoTime() - startedAt;
        nanos.merge(current, elapsed, Long::sum);
        totalNanos += elapsed;
        current = null;
    }

    /** The recorded duration of every closed phase, in whole milliseconds. */
    public Map<String, Long> snapshotMs() {
        end(); // close any open phase so the snapshot is complete
        Map<String, Long> out = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e : nanos.entrySet()) {
            out.put(e.getKey(), e.getValue() / 1_000_000L);
        }
        return out;
    }

    /** Total measured time across all phases, in whole milliseconds. */
    public long totalMs() {
        return totalNanos / 1_000_000L;
    }

    /** A single-line, human-readable summary for the debug log. */
    public String describe() {
        end();
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Long> e : snapshotMs().entrySet()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(e.getKey()).append('=').append(e.getValue()).append("ms");
        }
        sb.append(", total=").append(totalMs()).append("ms");
        return sb.toString();
    }
}
