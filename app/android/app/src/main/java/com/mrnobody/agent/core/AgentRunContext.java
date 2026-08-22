package com.mrnobody.agent.core;

import com.mrnobody.agent.ai.AiProvider;
import com.mrnobody.agent.ai.ProviderSnapshot;
import com.mrnobody.agent.policy.BudgetGuard;
import com.mrnobody.agent.policy.RepeatCallGuard;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * Mutable state owned by exactly one task run.
 *
 * <p>The engine and pipeline are process singletons, but scopes, counters,
 * provider choice and planner-visible capabilities must not be. Keeping them
 * here is the prerequisite for a bounded multi-lane local worker.
 */
public final class AgentRunContext {

    private static final ThreadLocal<AgentRunContext> CURRENT = new ThreadLocal<>();

    public final long taskId;
    public final String runId;
    public final ProviderSnapshot primarySnapshot;
    public final List<ProviderSnapshot> fallbackSnapshots;
    public final AiProvider provider;
    public final RepeatCallGuard repeatGuard = new RepeatCallGuard();
    public final BudgetGuard budgetGuard = new BudgetGuard();

    private volatile Set<String> toolScope = Collections.emptySet();
    private volatile Map<String, Tool> tools = Collections.emptyMap();
    private volatile String executionPlatform;

    public AgentRunContext(long taskId, String runId, ProviderSnapshot primarySnapshot,
                           List<ProviderSnapshot> fallbackSnapshots, AiProvider provider,
                           String executionPlatform) {
        this.taskId = taskId;
        this.runId = runId == null ? "" : runId;
        this.primarySnapshot = primarySnapshot == null
                ? new ProviderSnapshot("local", "", "") : primarySnapshot;
        this.fallbackSnapshots = Collections.unmodifiableList(new ArrayList<>(
                fallbackSnapshots == null ? Collections.emptyList() : fallbackSnapshots));
        this.provider = provider;
        this.executionPlatform = executionPlatform == null ? "" : executionPlatform;
    }

    public static void bind(AgentRunContext context) {
        if (context == null) CURRENT.remove();
        else CURRENT.set(context);
    }

    public static AgentRunContext current() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }

    /** Propagate a run onto a pooled tool thread and restore its prior value. */
    public static <T> T callAs(AgentRunContext context, Callable<T> work) throws Exception {
        AgentRunContext previous = CURRENT.get();
        bind(context);
        try {
            return work.call();
        } finally {
            bind(previous);
        }
    }

    public void setToolScope(Collection<String> tools) {
        LinkedHashSet<String> copy = new LinkedHashSet<>();
        if (tools != null) copy.addAll(tools);
        toolScope = Collections.unmodifiableSet(copy);
    }

    public Set<String> toolScope() {
        return toolScope;
    }

    public void setTools(Map<String, Tool> available) {
        LinkedHashMap<String, Tool> copy = new LinkedHashMap<>();
        if (available != null) copy.putAll(available);
        tools = Collections.unmodifiableMap(copy);
    }

    public Map<String, Tool> tools() {
        return tools;
    }

    public String executionPlatform() {
        return executionPlatform;
    }

    public void setExecutionPlatform(String value) {
        executionPlatform = value == null ? "" : value.trim();
    }
}
