package com.mrnobody.agent.design;

import android.content.Context;

import com.mrnobody.agent.core.Cancellation;
import com.mrnobody.agent.core.ToolRequest;
import com.mrnobody.agent.core.ToolResult;
import com.mrnobody.agent.execution.ExecutionIdentity;

import java.util.Collections;
import java.util.Set;

/** Fail-closed adapter used until a real platform connection is configured. */
public final class UnavailableDesignAdapter implements DesignPlatformAdapter {
    private final String reason;

    public UnavailableDesignAdapter(String reason) {
        this.reason = reason == null ? "Design platform is not configured." : reason;
    }
    @Override public String id() { return "unavailable"; }
    @Override public Set<String> capabilities() { return Collections.emptySet(); }
    @Override public boolean isConfigured() { return false; }
    @Override public ToolResult invoke(Context c, ToolRequest r,
                                       ExecutionIdentity e, Cancellation x) {
        return ToolResult.fail(reason);
    }
}
