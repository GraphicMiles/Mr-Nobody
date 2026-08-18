package com.mrnobody.agent.planner;

import java.util.Collection;

/**
 * Turns an instruction into a {@link Plan}.
 *
 * <p>This is the seam between "what the user asked" and "what will actually be
 * done". V1 ships {@link DeterministicPlanner}, which returns the research
 * cascade (or a one-step routed action) as a plan; a model-backed planner
 * later produces the same shape, so the engine never has to learn which kind
 * of planner it is running. A planner produces steps; the engine executes
 * them. Choosing a step is not the same as permitting it — every tool step
 * still passes through the guarded pipeline.
 */
public interface Planner {

    /**
     * Produce the plan for {@code instruction}.
     *
     * @param availableTools the tools the engine currently has, so a planner
     *                       can never route to a tool that is not registered.
     */
    Plan plan(String instruction, Collection<String> availableTools);

    /**
     * Revise the plan after a tool step failed.
     *
     * <p>Called when a step the planner produced could not run. A model-backed
     * planner can produce replacement steps (read a different page, try another
     * source); a deterministic planner has nothing smarter to offer and returns
     * {@code null}, which the engine treats as "give up and fail the task".
     * The returned steps are appended after the failed one, and the plan's own
     * ceiling still applies — a replan cannot make a task unbounded.
     */
    default Plan replan(Plan current, Plan.Step failedStep, String error,
                        Collection<String> availableTools) {
        return null;
    }
}
