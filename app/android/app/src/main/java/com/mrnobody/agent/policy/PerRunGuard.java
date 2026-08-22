package com.mrnobody.agent.policy;

import com.mrnobody.agent.core.AgentRunContext;
import com.mrnobody.agent.core.ToolCall;
import com.mrnobody.agent.core.ToolPipeline;

/** Delegates repeat/work budgets to the context bound to the current run. */
public final class PerRunGuard implements ToolPipeline.Guard {

    private final ThreadLocal<RepeatCallGuard> directRepeat =
            ThreadLocal.withInitial(RepeatCallGuard::new);
    private final ThreadLocal<BudgetGuard> directBudget =
            ThreadLocal.withInitial(BudgetGuard::new);

    @Override
    public String denyReason(ToolCall call) {
        AgentRunContext run = AgentRunContext.current();
        RepeatCallGuard repeat = run == null ? directRepeat.get() : run.repeatGuard;
        BudgetGuard budget = run == null ? directBudget.get() : run.budgetGuard;
        String repeated = repeat.denyReason(call);
        return repeated != null ? repeated : budget.denyReason(call);
    }

    /** Pure JVM/direct engine runs have no LocalWorker context. */
    public void resetDirect() {
        directRepeat.set(new RepeatCallGuard());
        directBudget.set(new BudgetGuard());
    }
}
