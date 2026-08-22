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
import com.mrnobody.agent.execution.ExecutionIdentity;
import com.mrnobody.agent.util.NetworkTargetPolicy;
import com.mrnobody.browser.net.NetworkGate;
import com.mrnobody.browser.download.DownloadDestination;
import com.mrnobody.browser.download.DownloadEngine;
import com.mrnobody.browser.download.DownloadNaming;
import com.mrnobody.browser.download.DownloadRecord;
import com.mrnobody.browser.download.DownloadRisk;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Enqueues a download with the app's own download engine and waits until the
 * transfer is actually finished.
 *
 * <p>Goes through the same engine as a download the user starts by tapping a
 * link, so an agent download lands in the folder they chose, appears in the
 * same list, and can be paused or cancelled the same way. An agent must not
 * have a private back door to the filesystem.
 *
 * <p>"Downloaded" is only reported after {@link DownloadRecord.Status#COMPLETED}.
 * Enqueue-and-return was a lie: the UI said the file was there while bytes
 * were still moving, or after the transfer had already failed.
 */
public final class DownloadTool implements Tool {

    /** How long we will sit on a single transfer before reporting "still going". */
    static final long WAIT_MS = 180_000L;

    /**
     * Sent on agent-initiated downloads. A device run proved the need: a
     * resolved icon URL came back 403 because the CDN hotlink-checks the
     * client, and "MrNobody/1.0" with no Referer looks exactly like a
     * scraper. This is the same class of client string every browser sends.
     */
    static final String BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 12; Mobile) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36";

    private static final ToolSpec SPEC = ToolSpec.named("download")
            .describedAs("Download a file from a URL to the user's download folder.")
            // Sandbox write: a new file in the app's download folder. Not a
            // click on a live page, not an overwrite of the user's documents.
            .tier(Tier.SANDBOX)
            .param(ParamSpec.url("url", true, "The http(s) file URL to download."))
            .param(ParamSpec.url("referer", false,
                    "The page the file link came from; sent as the Referer header. "
                    + "Image CDNs hotlink-protect: without it they answer 403."))
            .returns(OutputSpec.of(DownloadTool::render, "url", "id", "status"))
            .timeout(WAIT_MS + 15_000L)
            .build();

    @Override
    public ToolSpec spec() {
        return SPEC;
    }

    @Override
    public Tier tierFor(ToolRequest request) {
        String url = request == null ? null : request.param("url");
        String name = DownloadNaming.fileName(url, null, null);
        return DownloadRisk.assess(name, null, url).requiresConfirmation
                ? Tier.EXEC : Tier.SANDBOX;
    }

    @Override
    public ToolResult execute(Context context, ToolRequest request) {
        return executeInternal(context, request, Cancellation.NONE, null);
    }

    @Override
    public ToolResult execute(Context context, ToolRequest request, Cancellation cancellation) {
        return executeInternal(context, request, cancellation, null);
    }

    @Override
    public ToolResult execute(Context context, ToolRequest request, Cancellation cancellation,
                              ExecutionIdentity execution) {
        return executeInternal(context, request, cancellation, execution);
    }

    @Override
    public boolean supportsIdempotency(ToolRequest request) {
        return true;
    }

    @Override
    public ToolResult reconcile(Context context, ToolRequest request,
                                ExecutionIdentity execution) {
        if (context == null || execution == null || execution.idempotencyKey().isEmpty()) {
            return null;
        }
        DownloadRecord existing = DownloadEngine.get(context).store()
                .findByRequestKey(execution.idempotencyKey());
        return existing == null ? null : result(context, request.param("url"), existing);
    }

    private ToolResult executeInternal(Context context, ToolRequest request,
                                       Cancellation cancellation,
                                       ExecutionIdentity execution) {
        String url = request.param("url");
        try {
            if (!NetworkGate.canConnect()) {
                return ToolResult.needsApproval("network", NetworkGate.blockedReason());
            }
            // A public http(s) target is reachable; only non-public (private /
            // local / LAN) and non-http(s) hosts are refused. This is what lets
            // a user-chosen http:// file download instead of being refused for
            // being cleartext — see the cleartext consent just below.
            String targetProblem = NetworkTargetPolicy.publicHostReason(
                    url, NetworkGate.resolvesTargetsLocally());
            if (targetProblem != null) {
                return ToolResult.fail("download: url refused: " + targetProblem);
            }

            // A plain http:// download is insecure transport. It is not silently
            // fetched: the task parks WAITING and the chat shows a warning with
            // Continue / Reject. Only an explicit user decision proceeds, and it
            // is remembered for the session (the existing "allow downloads this
            // session" override), so consent is not re-asked on every step.
            String cleartext = DownloadRisk.cleartextReason(url);
            if (cleartext != null && !downloadApprovedForSession()) {
                return ToolResult.needsApproval("download", cleartext + "\n" + url);
            }

            String name = DownloadNaming.fileName(url, null, null);
            DownloadRisk.Assessment initialRisk = DownloadRisk.assess(name, null, url);
            String referer = request.param("referer");
            DownloadEngine engine = DownloadEngine.get(context);
            String requestKey = execution == null ? null : execution.idempotencyKey();
            DownloadRecord record = engine.enqueue(url, name, null, BROWSER_UA,
                    referer == null || referer.isEmpty() ? null : referer,
                    initialRisk.requiresConfirmation, cleartext != null, requestKey);
            DownloadRecord done = engine.awaitTerminal(record.id, WAIT_MS,
                    () -> cancellation != null && cancellation.isCancelled());
            if (done == null) done = record;
            return result(context, url, done);
        } catch (Exception e) {
            return ToolResult.fail("download failed: " + e.getMessage());
        }
    }

    /**
     * True once the user has allowed downloads for this app session. A cleartext
     * download parks the task for a Continue/Reject; choosing Continue sets the
     * session override (see {@code resolveApproval} in MainActivity), so the
     * re-run proceeds over http without re-prompting. Defaults to false when the
     * override store is unavailable.
     */
    private static boolean downloadApprovedForSession() {
        try {
            com.mrnobody.agent.policy.ApprovalPolicy.MapOverrides overrides =
                    com.mrnobody.browser.MrNobodyApp.approvalOverrides();
            return overrides != null
                    && overrides.forTool("download")
                            == com.mrnobody.agent.policy.ApprovalPolicy.Rule.ALWAYS_ALLOW;
        } catch (Throwable t) {
            return false;
        }
    }

    private static ToolResult result(Context context, String url, DownloadRecord done) {
        boolean customFolder = new DownloadDestination(context).isCustom();
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("url", url == null ? done.url : url);
        value.put("id", done.id);
        value.put("name", done.fileName);
        value.put("folder", done.destLabel);
        value.put("customFolder", customFolder);
        value.put("status", done.status == null ? "UNKNOWN" : done.status.name());
        value.put("bytes", done.bytes);
        value.put("total", done.total);
        if (done.error != null && !done.error.isEmpty()) value.put("error", done.error);
        if (done.status == DownloadRecord.Status.FAILED
                || done.status == DownloadRecord.Status.CANCELLED) {
            String why = done.error == null || done.error.isEmpty()
                    ? done.status.name() : done.error;
            return ToolResult.fail("download failed: " + why);
        }
        return ToolResult.ok(value);
    }

    private static String render(Map<String, Object> value) {
        String status = String.valueOf(value.get("status"));
        String name = String.valueOf(value.get("name"));
        String folder = String.valueOf(value.get("folder"));
        if ("COMPLETED".equals(status)) {
            return "Downloaded " + name + " to " + folder + ".";
        }
        if ("RUNNING".equals(status) || "QUEUED".equals(status)) {
            return "Download still in progress: " + name + " (not finished yet).";
        }
        return "Download " + status.toLowerCase() + ": " + name;
    }
}
