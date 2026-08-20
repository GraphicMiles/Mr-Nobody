package com.mrnobody.agent.planner;

import com.mrnobody.agent.core.Task;
import com.mrnobody.agent.core.ToolRequest;

import java.util.Collection;

/**
 * V1's planner: deterministic, auditable, no model required.
 *
 * <p>Returns the research cascade — Search → Read → Answer → Verify — as a
 * plan, or a one-step routed action when the instruction names one. This is
 * the same behaviour the engine used to hard-code, lifted out so the plan is
 * data rather than control flow. An LLM planner later returns a richer plan
 * through the same {@link Planner} interface and the engine does not change.
 *
 * <p>The Search step is the only tool step here. The Read step is internal: it
 * appends one {@code http} step per source as the engine executes it, which is
 * the plan growing in response to what a step learned — the whole point of
 * {@link Plan#append}.
 */
public final class DeterministicPlanner implements Planner {

    @Override
    public Plan plan(String instruction, Collection<String> availableTools) {
        // An instruction that names an action is a one-step plan: do the thing.
        // Otherwise it is the research cascade.
        ToolRouter.Route route = ToolRouter.route(instruction, availableTools);
        if (route != null) {
            return Plan.of(Plan.Step.tool(Task.STEP_ACT, route.tool, route.request, route.reason));
        }

        // A download asked by name, not by link ("download moci"): search for
        // it, read the pages, resolve a file link from them, and download that —
        // instead of the model inventing a URL the page never offered.
        boolean wantsDownload = availableTools.contains("download")
                && ToolRouter.isDownloadIntent(instruction);

        java.util.List<Plan.Step> steps = new java.util.ArrayList<>();
        SearchSkills.Skill skill = SearchSkills.route(instruction);
        java.util.Map<String, String> searchParams = new java.util.LinkedHashMap<>();
        searchParams.put("q", skill.query);
        if (!skill.provider.isEmpty()) searchParams.put("provider", skill.provider);
        steps.add(Plan.Step.tool(Task.STEP_SEARCH, "search",
                new ToolRequest("search", searchParams),
                "find sources with " + skill.id));
        steps.add(Plan.Step.internal(Task.STEP_READ));
        if (wantsDownload) steps.add(Plan.Step.internal(Task.STEP_RESOLVE_DOWNLOAD));
        steps.add(Plan.Step.internal(Task.STEP_ANSWER));
        steps.add(Plan.Step.internal(Task.STEP_VERIFY));
        return new Plan(steps);
    }
}
