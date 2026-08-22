package com.mrnobody.agent.design;

import android.content.Context;

import com.mrnobody.agent.core.Cancellation;
import com.mrnobody.agent.core.ToolRequest;
import com.mrnobody.agent.core.ToolResult;
import com.mrnobody.agent.execution.ExecutionIdentity;

import java.util.Set;

/** Platform-neutral design execution boundary; Canva MCP is one implementation. */
public interface DesignPlatformAdapter {
    String id();
    Set<String> capabilities();
    boolean isConfigured();
    default boolean supportsIdempotency(String action) { return false; }

    ToolResult invoke(Context context, ToolRequest request,
                      ExecutionIdentity execution, Cancellation cancellation);

    /** Resolve an ambiguous prior operation by the same idempotency key. */
    default ToolResult reconcile(Context context, ToolRequest request,
                                 ExecutionIdentity execution) {
        return null;
    }
}
