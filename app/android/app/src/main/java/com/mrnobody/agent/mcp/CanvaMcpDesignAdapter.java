package com.mrnobody.agent.mcp;

import android.content.Context;

import com.mrnobody.agent.core.Cancellation;
import com.mrnobody.agent.core.ToolRequest;
import com.mrnobody.agent.core.ToolResult;
import com.mrnobody.agent.design.DesignPlatformAdapter;
import com.mrnobody.agent.execution.ExecutionIdentity;
import com.mrnobody.agent.resilience.FailureClassifier;
import com.mrnobody.agent.resilience.FailureKind;
import com.mrnobody.agent.resilience.OperationFailure;
import com.mrnobody.agent.util.PageImage;
import com.mrnobody.browser.download.DownloadEngine;
import com.mrnobody.browser.download.DownloadRecord;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Official Canva remote MCP adapter. OAuth credentials remain outside tool data. */
public final class CanvaMcpDesignAdapter implements DesignPlatformAdapter {

    private static final Set<String> CAPABILITIES = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList("generate", "select", "edit", "export")));
    private static final String UA = "Mozilla/5.0 (Linux; Android 12; Mobile) MrNobody/1.0";

    private final Context appContext;
    private final CanvaOAuthManager oauth;
    private final McpClient client;

    public CanvaMcpDesignAdapter(Context context, CanvaOAuthManager oauth) {
        this.appContext = context.getApplicationContext();
        this.oauth = oauth;
        this.client = new McpClient(CanvaMcpConfig.ENDPOINT,
                new StreamableHttpMcpTransport(), oauth);
    }

    @Override public String id() { return "canva-mcp"; }
    @Override public Set<String> capabilities() { return CAPABILITIES; }
    @Override public boolean isConfigured() {
        return CanvaMcpConfig.isBuildConfigured() && oauth != null && oauth.isConnected();
    }
    /** Canva does not document effect idempotency; ambiguous effects must stop. */
    @Override public boolean supportsIdempotency(String action) { return false; }

    public List<McpCapability> discoveredTools(Cancellation cancellation) throws Exception {
        return client.listTools(cancellation == null ? Cancellation.NONE : cancellation);
    }

    public void resetSession() { client.resetSession(); }

    @Override
    public ToolResult invoke(Context context, ToolRequest request,
                             ExecutionIdentity execution, Cancellation cancellation) {
        if (!isConfigured()) {
            return ToolResult.fail(new OperationFailure(FailureKind.AUTHENTICATION,
                    "Canva MCP is not connected.", 401, 0L, false, false));
        }
        try {
            switch (request.action()) {
                case "generate": return generate(request, execution, cancellation);
                case "select": return select(request, execution, cancellation);
                case "edit": return edit(request, execution, cancellation);
                case "export": return export(request, execution, cancellation);
                case "status": return ToolResult.fail("No pending Canva job requires polling.");
                default: return ToolResult.fail("Unsupported Canva design action: " + request.action());
            }
        } catch (McpException e) {
            return ToolResult.fail(e.failure);
        } catch (Exception e) {
            return ToolResult.fail(FailureClassifier.fromMessage(
                    "Canva MCP failed: " + safe(e)));
        }
    }

    private ToolResult generate(ToolRequest request, ExecutionIdentity execution,
                                Cancellation cancellation) throws Exception {
        String toolName = "generate-design";
        McpCapability tool = required(toolName, cancellation);
        McpResult result = callTool(toolName,
                McpArguments.generate(tool, request.param("instruction", "")),
                execution.idempotencyKey(), cancellation);
        JSONObject value = requireSuccess(result, toolName);
        JSONObject job = value.optJSONObject("job");
        if (job == null) throw protocol("generate-design returned no job");
        String status = job.optString("status", "");
        if (!isSuccess(status)) return jobFailure(job, toolName);
        JSONObject generated = job.optJSONObject("result");
        JSONArray designs = generated == null ? null : generated.optJSONArray("generated_designs");
        if (designs == null || designs.length() == 0) {
            throw protocol("generate-design returned no candidates");
        }
        List<Object> candidates = new ArrayList<>();
        for (int i = 0; i < designs.length(); i++) {
            JSONObject candidate = designs.optJSONObject(i);
            if (candidate == null) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("candidateRef", candidate.optString("candidate_id", ""));
            item.put("previewRef", localPreview(thumbnail(candidate)));
            candidates.add(item);
        }
        if (candidates.isEmpty()) throw protocol("generate-design candidates were malformed");
        Map<?, ?> first = (Map<?, ?>) candidates.get(0);
        Map<String, Object> out = base("generate");
        out.put("generationJobId", job.optString("id", ""));
        out.put("candidateRef", String.valueOf(first.get("candidateRef")));
        out.put("previewRef", String.valueOf(first.get("previewRef")));
        out.put("candidates", candidates);
        return ToolResult.ok(out);
    }

    private ToolResult select(ToolRequest request, ExecutionIdentity execution,
                              Cancellation cancellation) throws Exception {
        McpCapability tool = required("create-design-from-candidate", cancellation);
        McpResult result = callTool("create-design-from-candidate",
                McpArguments.select(tool, request.param("generationJobId", ""),
                        request.param("candidateRef", "")),
                execution.idempotencyKey(), cancellation);
        JSONObject value = requireSuccess(result, "create-design-from-candidate");
        JSONObject summary = value.optJSONObject("design_summary");
        if (summary == null || summary.optString("id", "").isEmpty()) {
            throw protocol("create-design-from-candidate returned no design id");
        }
        JSONObject urls = summary.optJSONObject("urls");
        Map<String, Object> out = base("select");
        out.put("artifactRef", summary.optString("id"));
        out.put("revision", String.valueOf(summary.optLong("updated_at", 0L)));
        out.put("previewRef", urls == null ? "" : urls.optString("view_url", ""));
        out.put("editRef", urls == null ? "" : urls.optString("edit_url", ""));
        return ToolResult.ok(out);
    }

    private ToolResult edit(ToolRequest request, ExecutionIdentity execution,
                            Cancellation cancellation) throws Exception {
        String designId = request.param("artifactRef", "");
        McpCapability start = required("start-editing-transaction", cancellation);
        JSONObject opened = requireSuccess(callTool("start-editing-transaction",
                McpArguments.designId(start, designId), execution.idempotencyKey() + ":start",
                cancellation), "start-editing-transaction");
        CanvaEditPlan plan = CanvaEditPlan.from(opened, request.param("instruction", ""));
        if (plan == null) {
            cancelTransaction(opened, execution, cancellation);
            return ToolResult.fail(new OperationFailure(FailureKind.VALIDATION,
                    "For safe Canva editing, specify an exact text change such as “change ‘Old’ to ‘New’”.",
                    0, 0L, false, false));
        }
        McpCapability perform = required("perform-editing-operations", cancellation);
        JSONObject edited = requireSuccess(callTool("perform-editing-operations",
                McpArguments.performEdit(perform, plan.transactionId, plan.pageIndex,
                        plan.elementId, plan.oldText, plan.newText, plan.responsive),
                execution.idempotencyKey() + ":edit", cancellation),
                "perform-editing-operations");
        ensureEditSucceeded(edited);
        McpCapability commit = required("commit-editing-transaction", cancellation);
        JSONObject committed = requireSuccess(callTool("commit-editing-transaction",
                McpArguments.commit(commit, plan.transactionId),
                execution.idempotencyKey() + ":commit", cancellation),
                "commit-editing-transaction");
        JSONObject transaction = committed.optJSONObject("transaction");
        if (transaction == null || !"committed".equalsIgnoreCase(
                transaction.optString("status", ""))) {
            throw protocol("Canva did not confirm the edit commit");
        }
        Map<String, Object> out = base("edit");
        out.put("artifactRef", designId);
        out.put("revision", plan.transactionId);
        String preview = thumbnail(edited).isEmpty() ? plan.preview : thumbnail(edited);
        out.put("previewRef", localPreview(preview));
        return ToolResult.ok(out);
    }

    private ToolResult export(ToolRequest request, ExecutionIdentity execution,
                              Cancellation cancellation) throws Exception {
        String designId = request.param("artifactRef", "");
        String format = request.param("format", "png").toLowerCase();
        McpCapability tool = required("export-design", cancellation);
        JSONObject value = requireSuccess(callTool("export-design",
                McpArguments.export(tool, designId, format), execution.idempotencyKey(),
                cancellation), "export-design");
        JSONObject job = value.optJSONObject("job");
        if (job == null || !isSuccess(job.optString("status", ""))) {
            return job == null ? ToolResult.fail("Canva returned no export job")
                    : jobFailure(job, "export-design");
        }
        JSONArray urls = job.optJSONArray("urls");
        String signedUrl = urls == null ? "" : urls.optString(0, "");
        if (signedUrl.isEmpty()) throw protocol("export-design returned no download URL");

        String name = "canva-" + safeName(designId) + "." + format;
        DownloadEngine downloads = DownloadEngine.get(appContext);
        DownloadRecord record = downloads.enqueue(signedUrl, name, mime(format), UA,
                null, false, execution.idempotencyKey() + ":download");
        DownloadRecord done = downloads.awaitTerminal(record.id, 60_000L,
                () -> cancellation != null && cancellation.isCancelled());
        if (done == null) done = record;
        Map<String, Object> out = base("export");
        out.put("artifactRef", designId);
        out.put("downloadId", done.id);
        out.put("format", format);
        if (done.status == DownloadRecord.Status.COMPLETED) {
            out.put("exportRef", done.destUri == null ? "" : done.destUri);
            // Signed Canva URLs are operationally retained only during transfer.
            done.url = "canva-export-consumed";
            downloads.store().update(done);
        } else if (done.status == DownloadRecord.Status.FAILED
                || done.status == DownloadRecord.Status.CANCELLED) {
            return ToolResult.fail("Canva export download failed: "
                    + (done.error == null ? done.status.name() : done.error));
        } else {
            out.put("exportRef", "download:" + done.id);
            out.put("pending", true);
        }
        return ToolResult.ok(out);
    }

    private McpResult callTool(String name, JSONObject arguments, String key,
                               Cancellation cancellation) throws Exception {
        if (!CanvaMcpRateLimit.tryAcquire(name)) {
            throw new McpException(new OperationFailure(FailureKind.RATE_LIMIT,
                    "Local Canva MCP rate budget reached for " + name,
                    429, 1_000L, true, false));
        }
        return client.callTool(name, arguments, key, cancellation);
    }

    private McpCapability required(String name, Cancellation cancellation) throws Exception {
        McpCapability tool = client.tool(name, cancellation);
        if (tool == null) throw new McpException(new OperationFailure(FailureKind.UNSUPPORTED,
                "Canva MCP does not expose " + name, 0, 0L, false, false));
        return tool;
    }

    private static JSONObject requireSuccess(McpResult result, String tool) throws McpException {
        if (result == null) throw new McpException(OperationFailure.ambiguous(tool + " returned nothing"));
        if (result.error) throw new McpException(FailureClassifier.fromMessage(
                result.text.isEmpty() ? tool + " failed" : result.text));
        return result.structured;
    }

    private void cancelTransaction(JSONObject opened, ExecutionIdentity execution,
                                   Cancellation cancellation) {
        try {
            JSONObject transaction = opened.optJSONObject("transaction");
            String id = transaction == null ? "" : transaction.optString("transaction_id", "");
            McpCapability cancel = client.tool("cancel-editing-transaction", cancellation);
            if (cancel != null && !id.isEmpty()) {
                callTool("cancel-editing-transaction", McpArguments.commit(cancel, id),
                        execution.idempotencyKey() + ":cancel", cancellation);
            }
        } catch (Exception ignored) { }
    }

    private static void ensureEditSucceeded(JSONObject edited) throws McpException {
        JSONArray results = edited.optJSONArray("edit_operation_results");
        if (results == null || results.length() == 0) {
            throw protocol("Canva returned no edit operation result");
        }
        for (int i = 0; i < results.length(); i++) {
            JSONObject result = results.optJSONObject(i);
            if (result == null || !"success".equalsIgnoreCase(result.optString("status", ""))) {
                throw protocol("A Canva edit operation failed");
            }
        }
    }

    private String localPreview(String remoteUrl) {
        if (remoteUrl == null || remoteUrl.isEmpty()) return "";
        return PageImage.download(appContext, remoteUrl);
    }

    private static ToolResult jobFailure(JSONObject job, String tool) {
        JSONObject error = job.optJSONObject("error");
        String message = error == null ? tool + " job status: " + job.optString("status", "unknown")
                : error.optString("message", error.optString("code", tool + " failed"));
        return ToolResult.fail(FailureClassifier.fromMessage(message));
    }

    private static boolean isSuccess(String status) {
        return "success".equalsIgnoreCase(status) || "completed".equalsIgnoreCase(status);
    }

    private static Map<String, Object> base(String action) {
        Map<String, Object> out = new LinkedHashMap<>(); out.put("action", action); return out;
    }
    private static String thumbnail(JSONObject value) {
        JSONArray thumbs = value == null ? null : value.optJSONArray("thumbnails");
        JSONObject first = thumbs == null ? null : thumbs.optJSONObject(0);
        return first == null ? "" : first.optString("url", "");
    }
    private static McpException protocol(String message) {
        return new McpException(new OperationFailure(FailureKind.PERMANENT,
                message, 0, 0L, false, false));
    }
    private static String mime(String format) {
        if ("pdf".equals(format)) return "application/pdf";
        if ("jpg".equals(format)) return "image/jpeg";
        if ("pptx".equals(format)) return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        if ("mp4".equals(format)) return "video/mp4";
        return "image/png";
    }
    private static String safeName(String value) {
        String clean = value == null ? "design" : value.replaceAll("[^A-Za-z0-9._-]", "-");
        return clean.isEmpty() ? "design" : clean.substring(0, Math.min(80, clean.length()));
    }
    private static String safe(Exception e) {
        String message = e.getMessage();
        return message == null || message.isEmpty() ? e.getClass().getSimpleName() : message;
    }
}
