package com.mrnobody.agent.planner;

import com.mrnobody.agent.core.ToolRequest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A sequence of steps that can grow while it runs.
 *
 * <p>Replaces the fixed four-element array the planner used. That array was
 * the reason "find the file, then download it" was impossible: the shape of
 * the work was decided before any of it happened, so nothing a step learned
 * could add a step.
 *
 * <p>Bounded on purpose. A plan that can extend itself is a plan that can
 * extend itself forever, and the failure mode of an agent on someone's phone
 * is not a wrong answer but a flat battery. {@link #MAX_STEPS} is the ceiling
 * and {@link #append} refuses past it rather than throwing, because a plan
 * that stops growing still finishes.
 */
public final class Plan {

    /** Hard ceiling on steps, including any added while running. */
    public static final int MAX_STEPS = 12;

    /** One unit of work. */
    public static final class Step {
        public final String label;
        public final String tool;
        public final String reason;

        /** The arguments for a tool step; null for an internal step. */
        public final ToolRequest request;

        public Step(String label, String tool, String reason) {
            this(label, tool, reason, null);
        }

        public Step(String label, String tool, String reason, ToolRequest request) {
            this.label = label;
            this.tool = tool;
            this.reason = reason == null ? "" : reason;
            this.request = request;
        }

        /** A step the planner runs itself rather than delegating to a tool. */
        public static Step internal(String label) {
            return new Step(label, null, "");
        }

        /** A step that runs a named tool with the given arguments. */
        public static Step tool(String label, String tool, ToolRequest request, String reason) {
            return new Step(label, tool, reason, request);
        }

        public boolean isToolStep() {
            return tool != null && !tool.isEmpty();
        }

        @Override
        public String toString() {
            return isToolStep() ? label + " (" + tool + ")" : label;
        }
    }

    private final List<Step> steps = new ArrayList<>();
    private int cursor = 0;
    private boolean abandoned;
    private String abandonedReason;

    public Plan(List<Step> initial) {
        if (initial != null) {
            for (Step s : initial) {
                if (s != null && steps.size() < MAX_STEPS) steps.add(s);
            }
        }
    }

    public static Plan of(Step... initial) {
        List<Step> list = new ArrayList<>();
        if (initial != null) Collections.addAll(list, initial);
        return new Plan(list);
    }

    public List<Step> steps() {
        return Collections.unmodifiableList(steps);
    }

    public int size() {
        return steps.size();
    }

    public int cursor() {
        return cursor;
    }

    /** The step about to run, or null when the plan is finished or abandoned. */
    public Step current() {
        if (abandoned || cursor >= steps.size()) return null;
        return steps.get(cursor);
    }

    /** Move past the current step. */
    public void advance() {
        if (cursor < steps.size()) cursor++;
    }

    public boolean isFinished() {
        return abandoned || cursor >= steps.size();
    }

    public boolean isAbandoned() {
        return abandoned;
    }

    public String abandonedReason() {
        return abandonedReason;
    }

    /**
     * Stop the plan without finishing it.
     *
     * <p>Distinct from finishing: a task that gave up should say so rather
     * than reporting a result it never reached.
     */
    public void abandon(String reason) {
        this.abandoned = true;
        this.abandonedReason = reason == null ? "stopped" : reason;
    }

    /**
     * Add a step discovered while running.
     *
     * @return false when the ceiling is reached — the caller carries on with
     *         what it has rather than failing
     */
    public boolean append(Step step) {
        if (step == null || abandoned) return false;
        if (steps.size() >= MAX_STEPS) return false;
        return steps.add(step);
    }

    /** Insert a step to run immediately after the current one. */
    public boolean insertNext(Step step) {
        if (step == null || abandoned) return false;
        if (steps.size() >= MAX_STEPS) return false;
        int at = Math.min(cursor + 1, steps.size());
        steps.add(at, step);
        return true;
    }

    /** Progress 0..100, derived from position rather than guessed. */
    public int progress() {
        if (steps.isEmpty()) return 0;
        if (abandoned) return 0;
        if (cursor >= steps.size()) return 100;
        // Never 100 while a step is still outstanding.
        return (int) Math.round(cursor * 100.0 / steps.size());
    }

    /** The plan as a readable line, for the task detail view. */
    /** Cursor plus labels — enough to reconstruct after a process death. */
    public String snapshot() {
        return cursor + "\t" + describe();
    }

    public String describe() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < steps.size(); i++) {
            if (i > 0) sb.append(" → ");
            if (i == cursor && !abandoned) sb.append('[');
            sb.append(steps.get(i).label);
            if (i == cursor && !abandoned) sb.append(']');
        }
        if (abandoned) sb.append(" (stopped: ").append(abandonedReason).append(')');
        return sb.toString();
    }
}
