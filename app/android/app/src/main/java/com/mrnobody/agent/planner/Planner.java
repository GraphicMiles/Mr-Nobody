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
}
