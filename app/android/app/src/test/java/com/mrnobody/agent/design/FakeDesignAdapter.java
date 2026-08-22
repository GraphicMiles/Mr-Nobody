package com.mrnobody.agent.design;

import android.content.Context;

import com.mrnobody.agent.core.Cancellation;
import com.mrnobody.agent.core.ToolRequest;
import com.mrnobody.agent.core.ToolResult;
import com.mrnobody.agent.execution.ExecutionIdentity;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Stateful fake used to prove design flows without Canva or network access. */
public final class FakeDesignAdapter implements DesignPlatformAdapter {
    public final AtomicInteger calls = new AtomicInteger();
    private final Map<String, ToolResult> byKey = new ConcurrentHashMap<>();

    @Override public String id() { return "fake-design"; }
    @Override public Set<String> capabilities() {
        return new LinkedHashSet<>(Arrays.asList("generate", "select", "edit", "export"));
    }
    @Override public boolean isConfigured() { return true; }

    @Override
    public ToolResult invoke(Context context, ToolRequest request,
                             ExecutionIdentity execution, Cancellation cancellation) {
        ToolResult prior = byKey.get(execution.idempotencyKey());
        if (prior != null) return prior;
        calls.incrementAndGet();
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("action", request.action());
        switch (request.action()) {
            case "generate":
                value.put("candidateRef", "candidate-1");
                value.put("previewRef", "preview://candidate-1");
                break;
            case "select":
            case "edit":
                value.put("artifactRef", request.param("artifactRef", "design-1"));
                value.put("revision", "rev-" + calls.get());
                value.put("previewRef", "preview://design-1/" + calls.get());
                break;
            case "export":
                value.put("artifactRef", request.param("artifactRef", "design-1"));
                value.put("exportRef", "export://design-1/" + request.param("format", "png"));
                break;
            default:
                return ToolResult.fail("unsupported fake action");
        }
        ToolResult result = ToolResult.ok(value);
        byKey.put(execution.idempotencyKey(), result);
        return result;
    }

    @Override
    public ToolResult reconcile(Context context, ToolRequest request,
                                ExecutionIdentity execution) {
        return byKey.get(execution.idempotencyKey());
    }
}
