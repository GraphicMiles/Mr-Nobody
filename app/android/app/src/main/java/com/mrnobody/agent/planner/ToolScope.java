package com.mrnobody.agent.planner;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Which tools a task is allowed to touch, decided from what the task
 * <em>is</em> — before any step runs.
 *
 * <p>The approval tiers say what a call may do; this says which tools exist
 * for the run at all. Before this layer, a plain question task was still
 * <em>offered</em> download and the terminal — nothing would have planned
 * them on the deterministic path, but nothing structural forbade them
 * either, and the autonomous planner advertises every registered tool to a
 * remote model. Scope turns "would not" into "cannot".
 *
 * <ul>
 *   <li><b>Research</b> (questions): search, http, browser — reading tools
 *       only. Download joins the set only when the instruction is a download.
 *       The terminal is never in research scope.</li>
 *   <li><b>Routed actions</b> (one-step plans — a terminal command, a direct
 *       download): exactly the one tool the router chose.</li>
 * </ul>
 */
public final class ToolScope {

    /** The reading tools every research task may use. */
    private static final String[] RESEARCH_BASE = {"search", "http", "browser"};

    private ToolScope() {
    }

    /**
     * The scope for a research task.
     *
     * @param downloadIntent true when the instruction asks for a download
     * @param registered     the tools actually registered on the engine
     */
    public static Set<String> research(boolean downloadIntent, Set<String> registered) {
        Set<String> out = new LinkedHashSet<>();
        for (String tool : RESEARCH_BASE) {
            if (registered == null || registered.contains(tool)) out.add(tool);
        }
        if (downloadIntent && (registered == null || registered.contains("download"))) {
            out.add("download");
        }
        return Collections.unmodifiableSet(out);
    }

    /** The scope for a routed one-step action: that tool and nothing else. */
    public static Set<String> routed(String tool) {
        if (tool == null || tool.isEmpty()) return Collections.emptySet();
        return Collections.singleton(tool);
    }

    /** The refusal recorded when a call falls outside the run's scope. */
    public static String deniedMessage(String tool) {
        return "The " + tool + " tool is not in scope for this task.";
    }
}
