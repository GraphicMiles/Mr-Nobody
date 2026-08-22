package com.mrnobody.agent.tools;

import android.content.Context;

import com.mrnobody.agent.core.Cancellation;
import com.mrnobody.agent.core.OutputSpec;
import com.mrnobody.agent.core.ParamSpec;
import com.mrnobody.agent.core.Tier;
import com.mrnobody.agent.core.Tool;
import com.mrnobody.agent.core.ToolRequest;
import com.mrnobody.agent.core.ToolResult;
import com.mrnobody.agent.core.ToolSpec;
import com.mrnobody.agent.design.DesignPlatformAdapter;
import com.mrnobody.agent.design.DesignQuota;
import com.mrnobody.agent.design.DesignSessionRepository;
import com.mrnobody.agent.execution.ExecutionIdentity;

import java.util.function.Supplier;

/** Scoped high-level design operations; no platform credential reaches the model. */
public final class DesignTool implements Tool {

    private final Supplier<DesignPlatformAdapter> adapters;
    private final Supplier<? extends DesignSessionRepository> sessions;

    public DesignTool(Supplier<DesignPlatformAdapter> adapters,
                      Supplier<? extends DesignSessionRepository> sessions) {
        this.adapters = adapters;
        this.sessions = sessions;
    }

    private static final ToolSpec SPEC = ToolSpec.named("design")
            .describedAs("Generate, edit, review, and export a design in the scoped platform.")
            .tier(Tier.EXEC)
            .param(ParamSpec.enumOf("action", false, "Design operation.",
                    "generate", "select", "edit", "export", "status"))
            .param(ParamSpec.integer("sessionId", true, "Durable design session id."))
            .param(ParamSpec.text("instruction", false, "Requested design or edit.", 8000))
            .param(ParamSpec.string("candidateRef", false, "Candidate selected after review."))
            .param(ParamSpec.string("artifactRef", false, "Existing platform design id."))
            .param(ParamSpec.string("expectedRevision", false, "Revision precondition."))
            .param(ParamSpec.enumOf("format", false, "Export format.",
                    "png", "jpg", "pdf", "pptx", "mp4"))
            .returns(OutputSpec.of(DesignTool::render, "action"))
            .timeout(75_000L)
            .build();

    @Override public ToolSpec spec() { return SPEC; }

    @Override
    public Tier tierFor(ToolRequest request) {
        String action = request == null ? "" : request.action();
        if ("status".equals(action)) return Tier.READ;
        if ("edit".equals(action)) return Tier.WRITE;
        return Tier.EXEC;
    }

    @Override public ToolResult execute(Context context, ToolRequest request) {
        return ToolResult.fail("design execution requires a durable run identity");
    }

    @Override
    public ToolResult execute(Context context, ToolRequest request, Cancellation cancellation,
                              ExecutionIdentity execution) {
        DesignPlatformAdapter adapter = adapters == null ? null : adapters.get();
        DesignSessionRepository store = sessions == null ? null : sessions.get();
        if (adapter == null || !adapter.isConfigured()) {
            return ToolResult.fail("Canva MCP is not connected. Open Settings → Design platform.");
        }
        long sessionId = parseLong(request.param("sessionId"));
        if (sessionId <= 0 || store == null) return ToolResult.fail("design session unavailable");
        DesignQuota.Operation operation = quota(request.action());
        if (!store.tryConsume(sessionId, operation, execution.idempotencyKey())) {
            return ToolResult.fail("Design " + operation.name().toLowerCase()
                    + " budget reached for this session.");
        }
        ToolResult result = adapter.invoke(context, request, execution,
                cancellation == null ? Cancellation.NONE : cancellation);
        if (result == null) return ToolResult.fail("design platform returned nothing");
        return result;
    }

    @Override public boolean supportsIdempotency(ToolRequest request) { return true; }

    @Override
    public ToolResult reconcile(Context context, ToolRequest request,
                                ExecutionIdentity execution) {
        DesignPlatformAdapter adapter = adapters == null ? null : adapters.get();
        return adapter == null ? null : adapter.reconcile(context, request, execution);
    }

    private static DesignQuota.Operation quota(String action) {
        if ("edit".equals(action)) return DesignQuota.Operation.EDIT;
        if ("export".equals(action)) return DesignQuota.Operation.EXPORT;
        if ("status".equals(action)) return DesignQuota.Operation.POLL;
        return DesignQuota.Operation.CREATE;
    }

    private static long parseLong(String value) {
        try { return Long.parseLong(value == null ? "" : value.trim()); }
        catch (Exception e) { return -1L; }
    }

    private static String render(java.util.Map<String, Object> value) {
        String action = String.valueOf(value.get("action"));
        Object preview = value.get("previewRef");
        Object export = value.get("exportRef");
        Object artifact = value.get("artifactRef");
        if (export != null) return "Design export ready: " + export;
        if (preview != null) return "Design " + action + " preview: " + preview;
        if (artifact != null) return "Design " + action + ": " + artifact;
        return "Design " + action + " completed.";
    }
}
