package com.mrnobody.agent.core;

import android.content.Context;

/**
 * The agent brain. V1 implements a deterministic engine (no LLM required);
 * V2 swaps in an LLM-backed engine behind the same interface. The engine owns
 * intent routing, planning, tool selection and verification.
 */
public interface AgentEngine {

    /** Handle one user instruction end-to-end, updating the task as it goes. */
    void run(Context context, Task task);
}
